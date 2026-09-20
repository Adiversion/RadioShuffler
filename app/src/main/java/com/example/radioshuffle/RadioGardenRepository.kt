package com.example.radioshuffle

import android.util.Log
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// --- API models ---
data class PlacesEnvelope(@SerializedName("data") val data: PlacesData?)
data class PlacesData(@SerializedName("list") val list: List<PlaceRecord>?)
data class PlaceRecord(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String?,
    @SerializedName("country") val country: String?,
    @SerializedName("size") val size: Int?,
    @SerializedName("boost") val boost: Boolean? = false
)

data class ChannelsEnvelope(@SerializedName("data") val data: ChannelsData?)
data class ChannelsData(@SerializedName("content") val content: List<ContentBlock>?)
data class ContentBlock(@SerializedName("items") val items: List<ItemWrapper>?)
data class ItemWrapper(@SerializedName("page") val page: PageDetails?)
data class PageDetails(
    @SerializedName("url") val url: String?,
    @SerializedName("href") val href: String?,
    @SerializedName("title") val title: String?,
    @SerializedName("subtitle") val subtitle: String? = null,
    @SerializedName("type") val type: String? = null,
    @SerializedName("place") val place: PlaceInfo?,
    @SerializedName("country") val country: CountryInfo?
) {
    val isChannel: Boolean
        get() = type.equals("channel", ignoreCase = true) ||
            (url ?: href)?.contains("/listen/", ignoreCase = true) == true
}
data class PlaceInfo(@SerializedName("title") val title: String?)
data class CountryInfo(@SerializedName("title") val title: String?)

data class SearchEnvelope(@SerializedName("hits") val hits: SearchHits?)
data class SearchHits(@SerializedName("hits") val hits: List<SearchHit>?)
data class SearchHit(@SerializedName("_source") val source: SearchSource?)
data class SearchSource(
    @SerializedName("type") val type: String?,
    @SerializedName("page") val page: PageDetails?,
    @SerializedName("title") private val rawTitle: String? = null,
    @SerializedName("subtitle") private val rawSubtitle: String? = null,
    @SerializedName("url") private val rawUrl: String? = null
) {
    val title: String?
        get() = page?.title ?: rawTitle

    val subtitle: String?
        get() = page?.subtitle ?: page?.let { p ->
            val city = p.place?.title
            val country = p.country?.title
            when {
                !city.isNullOrBlank() && !country.isNullOrBlank() -> "$city, $country"
                !city.isNullOrBlank() -> city
                !country.isNullOrBlank() -> country
                else -> null
            }
        } ?: rawSubtitle

    val url: String?
        get() = page?.url ?: page?.href ?: rawUrl
}

data class ResolvedStation(
    @SerializedName("channelId") val channelId: String,
    @SerializedName("title") val title: String,
    @SerializedName("city") val city: String,
    @SerializedName("country") val country: String
) {
    val streamUrl: String
        get() = "https://radio.garden/api/ara/content/listen/$channelId/channel.mp3"

    val location: String
        get() = listOf(city, country)
            .filter { it.isNotBlank() }
            .joinToString(", ")
            .ifBlank { "Worldwide" }
}

// --- Service ---
interface RadioGardenService {
    @GET("ara/content/places")
    suspend fun fetchPlaces(): PlacesEnvelope

    @GET("ara/content/page/{placeId}/channels")
    suspend fun fetchChannelsForPlace(@Path("placeId") placeId: String): ChannelsEnvelope

    @GET("ara/content/page/{pageId}")
    suspend fun fetchPage(@Path("pageId") pageId: String): ChannelsEnvelope

    @GET("search")
    suspend fun search(@Query("q") query: String): SearchEnvelope

    companion object {
        val instance: RadioGardenService by lazy {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .connectionPool(okhttp3.ConnectionPool(5, 5, TimeUnit.MINUTES))
                .retryOnConnectionFailure(true)
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
                        .header("Accept", "application/json")
                        .header("Referer", "https://radio.garden/")
                        .header("Origin", "https://radio.garden")
                        .build()
                    chain.proceed(request)
                }
                .build()

            Retrofit.Builder()
                .baseUrl("https://radio.garden/api/")
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(RadioGardenService::class.java)
        }

        fun create(): RadioGardenService = instance
    }
}

class RadioGardenRepository(
    private val service: RadioGardenService = RadioGardenService.instance,
    private val random: Random = Random.Default
) {
    companion object {
        @Volatile
        private var sharedPlaces: List<PlaceRecord>? = null
        @Volatile
        private var sharedPlacesByCountry: Map<String, List<PlaceRecord>>? = null
        private val placesMutex = Mutex()

        // History tracking to eliminate repetition and country clumping
        private val sharedRecentlyPlayedIds = ArrayDeque<String>(50)
        private val sharedRecentCountries = ArrayDeque<String>(35)

        // Lean instant prefetch station pool for zero-latency shuffling with minimal battery usage
        private val prefetchStationPool = ArrayDeque<ResolvedStation>(10)
        private val poolMutex = Mutex()
        private val isRefilling = AtomicBoolean(false)

        // Search cache pool
        @Volatile
        private var cachedSearchQuery: String? = null
        private val cachedSearchStations = ArrayDeque<ResolvedStation>(40)
        private val searchMutex = Mutex()

        private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private const val POOL_TARGET_SIZE = 3
        private const val POOL_MIN_THRESHOLD = 1
        private const val MAX_RECENT_CHANNELS = 50
        private const val MAX_RECENT_COUNTRIES = 25

        private val fallbackPlaces = listOf(
            PlaceRecord("eR8K4rBb", "Tokyo", "Japan", 20),
            PlaceRecord("4lZ7Z1hR", "London", "United Kingdom", 30),
            PlaceRecord("rJ4s1P7Y", "Paris", "France", 25),
            PlaceRecord("W6sK8r2d", "Berlin", "Germany", 20),
            PlaceRecord("V1sT8p3L", "New York NY", "United States", 35),
            PlaceRecord("G3mD8n2K", "Sydney", "Australia", 15),
            PlaceRecord("L2jK9x4P", "Toronto", "Canada", 15),
            PlaceRecord("m6kL2d8Z", "Amsterdam", "Netherlands", 20),
            PlaceRecord("No4sZS1r", "Gandra", "Portugal", 5),
            PlaceRecord("lZ4L8dvl", "Crato CE", "Brazil", 5),
            PlaceRecord("5yYRzag0", "Dhuusamareeb", "Somalia", 5),
            PlaceRecord("vK8m3P1s", "Rome", "Italy", 15),
            PlaceRecord("Q2vM8x4R", "Seoul", "South Korea", 15),
            PlaceRecord("P7mL3x9T", "Dublin", "Ireland", 15),
            PlaceRecord("R5kP8m2V", "Vienna", "Austria", 15),
            PlaceRecord("T2nL8x3M", "Prague", "Czechia", 15),
            PlaceRecord("M9xK2p3L", "Stockholm", "Sweden", 15),
            PlaceRecord("L7kP3m9R", "Oslo", "Norway", 15),
            PlaceRecord("N2mK8x3P", "Copenhagen", "Denmark", 15),
            PlaceRecord("R2nL9x4P", "Athens", "Greece", 15),
            PlaceRecord("M5kP8m2L", "Mumbai", "India", 15),
            PlaceRecord("L9kM3p2R", "Singapore", "Singapore", 15),
            PlaceRecord("P2mL8x3T", "Melbourne", "Australia", 15),
            PlaceRecord("W3kL8m2P", "Chicago IL", "United States", 20)
        )

        private val fallbackStations = listOf(
            ResolvedStation("OjFb4M9Q", "AFN Eagle 810 AM", "Tokyo", "Japan"),
            ResolvedStation("e8CURqFY", "LBC London", "London", "United Kingdom"),
            ResolvedStation("zY5P7lth", "France Info", "Paris", "France"),
            ResolvedStation("kCs714b4", "RBB Radio Eins", "Berlin", "Germany"),
            ResolvedStation("DiitcqaM", "2GB 873 AM", "Sydney", "Australia"),
            ResolvedStation("B3DfRJRH", "680 News", "Toronto", "Canada"),
            ResolvedStation("L1PNTsX0", "JOE", "Amsterdam", "Netherlands"),
            ResolvedStation("VgSq5B1V", "RAI Radio 1", "Rome", "Italy"),
            ResolvedStation("sjd0AW7d", "Big B Radio", "Seoul", "South Korea"),
            ResolvedStation("fqwA9Cxk", "RTÉ Radio 1", "Dublin", "Ireland"),
            ResolvedStation("kF06RKTW", "ORF Hitradio Ö3", "Vienna", "Austria"),
            ResolvedStation("EwiN37X4", "Evropa 2", "Prague", "Czechia"),
            ResolvedStation("4v2sLRQX", "Rix FM", "Stockholm", "Sweden"),
            ResolvedStation("BlJY41MK", "NRK Alltid Nyheter", "Oslo", "Norway"),
            ResolvedStation("PfxGZa0B", "DR P3", "Copenhagen", "Denmark"),
            ResolvedStation("Z3mEL1oa", "Melodia FM", "Athens", "Greece"),
            ResolvedStation("sG9ZZzTf", "Kishore Kumar Radio", "Mumbai", "India"),
            ResolvedStation("rG2tiYAQ", "Warna 94.2 FM", "Singapore", "Singapore"),
            ResolvedStation("hitz0edo", "Smooth FM", "Melbourne", "Australia"),
            ResolvedStation("6vPGh40Q", "Rock 95.5", "Chicago", "United States"),
            ResolvedStation("Ae6kS7C7", "Radio Redes Na Maré", "Gandra", "Portugal"),
            ResolvedStation("pJekqgRL", "Radio Dayax", "Dhuusamareeb", "Somalia")
        )
    }

    suspend fun warmUp() {
        getPlaces()
        repositoryScope.launch {
            refillPrefetchPool()
        }
    }

    suspend fun nextStation(query: String? = null): ResolvedStation? {
        val cleanedQuery = query?.trim()?.takeIf { it.isNotBlank() }
        return if (cleanedQuery == null) {
            nextShuffledStation()
        } else {
            nextSearchStation(cleanedQuery)
        }
    }

    suspend fun searchStations(query: String): List<ResolvedStation> {
        val cleanedQuery = query.trim()
        if (cleanedQuery.isBlank()) return emptyList()

        val directResults = executeSearch(cleanedQuery)
        if (directResults.isNotEmpty()) return directResults

        val localPlaces = searchLocalPlaces(cleanedQuery)
        val gathered = mutableListOf<ResolvedStation>()
        for (place in localPlaces.take(3)) {
            gathered.addAll(fetchStationsForPlace(place))
            if (gathered.size >= 12) break
        }
        return gathered.distinctBy { it.channelId }
    }

    private suspend fun nextShuffledStation(): ResolvedStation {
        // 1. Instant pop from prefetch queue, strictly preferring a different country from the last played station
        val candidate = poolMutex.withLock {
            var found: ResolvedStation? = null
            val lastCountry = synchronized(sharedRecentCountries) { sharedRecentCountries.lastOrNull() }

            // First pass: find an unplayed candidate from a different country
            val iterator = prefetchStationPool.iterator()
            while (iterator.hasNext()) {
                val popped = iterator.next()
                if (!isRecentlyPlayed(popped.channelId)) {
                    if (lastCountry == null || !popped.country.equals(lastCountry, ignoreCase = true) || prefetchStationPool.size == 1) {
                        found = popped
                        iterator.remove()
                        break
                    }
                }
            }

            // Second pass fallback: any unplayed candidate in pool
            if (found == null && prefetchStationPool.isNotEmpty()) {
                val fallbackIter = prefetchStationPool.iterator()
                while (fallbackIter.hasNext()) {
                    val popped = fallbackIter.next()
                    if (!isRecentlyPlayed(popped.channelId)) {
                        found = popped
                        fallbackIter.remove()
                        break
                    }
                }
            }
            found
        }

        checkAndRefillPool()

        if (candidate != null) {
            rememberStation(candidate)
            return candidate
        }

        // 2. Synchronous balanced fallback if queue was empty
        val liveStation = resolveBalancedStation()
        if (liveStation != null) {
            rememberStation(liveStation)
            checkAndRefillPool()
            return liveStation
        }

        // 3. Fallback station
        return fallbackStation(null)
    }

    private fun checkAndRefillPool() {
        repositoryScope.launch {
            val poolSize = poolMutex.withLock { prefetchStationPool.size }
            if (poolSize < POOL_MIN_THRESHOLD) {
                refillPrefetchPool()
            }
        }
    }

    private suspend fun refillPrefetchPool() {
        if (!isRefilling.compareAndSet(false, true)) return
        try {
            val placesByCountry = getPlacesByCountry()
            if (placesByCountry.isEmpty()) return

            val countries = placesByCountry.keys.toList()
            if (countries.isEmpty()) return

            var attempts = 0
            while (attempts < 6) {
                val currentSize = poolMutex.withLock { prefetchStationPool.size }
                if (currentSize >= POOL_TARGET_SIZE) break

                attempts++
                val recentCountriesSnapshot = synchronized(sharedRecentCountries) { sharedRecentCountries.toSet() }
                val poolCountries = poolMutex.withLock { prefetchStationPool.map { it.country }.toSet() }

                // Strict global diversity: prioritize countries that are neither in recent history nor already in the pool
                val eligibleCountries = countries.filter { it !in recentCountriesSnapshot && it !in poolCountries }
                    .ifEmpty { countries.filter { it !in poolCountries } }
                    .ifEmpty { countries }
                val chosenCountry = eligibleCountries.random(random)

                val countryPlaces = placesByCountry[chosenCountry].orEmpty()
                if (countryPlaces.isEmpty()) continue

                val curatedPlaces = countryPlaces.filter { (it.size ?: 0) >= 2 || it.boost == true }
                val chosenPlace = if (curatedPlaces.isNotEmpty() && random.nextDouble() < 0.80) {
                    curatedPlaces.random(random)
                } else {
                    countryPlaces.random(random)
                }

                val stations = fetchStationsForPlace(chosenPlace)
                if (stations.isEmpty()) continue

                // Exactly 1 station per country for pure worldwide diversity
                val singleStation = poolMutex.withLock {
                    val poolIds = prefetchStationPool.map { it.channelId }.toSet()
                    stations.filter { station ->
                        !isRecentlyPlayed(station.channelId) && station.channelId !in poolIds
                    }.shuffled(random).firstOrNull()
                }

                if (singleStation != null) {
                    poolMutex.withLock {
                        if (prefetchStationPool.size < POOL_TARGET_SIZE) {
                            prefetchStationPool.addLast(singleStation)
                        }
                    }
                    rememberCountry(chosenCountry)
                }
            }
        } catch (e: Exception) {
            Log.w("RadioGarden", "Prefetch refill failed: ${e.message}")
        } finally {
            isRefilling.set(false)
        }
    }

    private suspend fun resolveBalancedStation(): ResolvedStation? {
        val placesByCountry = getPlacesByCountry()
        if (placesByCountry.isEmpty()) return null

        val countries = placesByCountry.keys.toList()
        val recentCountriesSnapshot = synchronized(sharedRecentCountries) { sharedRecentCountries.toSet() }
        val eligibleCountries = countries.filter { it !in recentCountriesSnapshot }.ifEmpty { countries }

        repeat(8) {
            val country = eligibleCountries.random(random)
            val countryPlaces = placesByCountry[country].orEmpty()
            if (countryPlaces.isNotEmpty()) {
                val curatedPlaces = countryPlaces.filter { (it.size ?: 0) >= 2 || it.boost == true }
                val place = if (curatedPlaces.isNotEmpty() && random.nextDouble() < 0.80) {
                    curatedPlaces.random(random)
                } else {
                    countryPlaces.random(random)
                }

                val stations = fetchStationsForPlace(place)
                val unplayed = stations.firstOrNull { !isRecentlyPlayed(it.channelId) } ?: stations.firstOrNull()
                if (unplayed != null) {
                    rememberCountry(country)
                    return unplayed
                }
            }
        }
        return null
    }

    private suspend fun fetchStationsForPlace(place: PlaceRecord): List<ResolvedStation> {
        val channelsEnv = runCatching { service.fetchChannelsForPlace(place.id) }
            .getOrNull()
            ?: runCatching { service.fetchPage(place.id) }.getOrNull()

        return channelsEnv?.data?.content
            ?.flatMap { it.items ?: emptyList() }
            ?.mapNotNull { it.page }
            ?.filter { it.isChannel && !it.channelPath.isNullOrBlank() }
            ?.mapNotNull { page ->
                val channelId = extractIdFromUrl(page.channelPath) ?: return@mapNotNull null
                ResolvedStation(
                    channelId = channelId,
                    title = page.title ?: "World Radio",
                    city = page.place?.title ?: place.title ?: "Unknown City",
                    country = page.country?.title ?: place.country ?: "Worldwide"
                )
            }.orEmpty()
    }

    private suspend fun nextSearchStation(query: String): ResolvedStation? {
        val cached = searchMutex.withLock {
            if (query.equals(cachedSearchQuery, ignoreCase = true) && cachedSearchStations.isNotEmpty()) {
                while (cachedSearchStations.isNotEmpty()) {
                    val candidate = cachedSearchStations.removeFirst()
                    if (!isRecentlyPlayed(candidate.channelId)) {
                        return@withLock candidate
                    }
                }
            }

            cachedSearchQuery = query
            cachedSearchStations.clear()

            val freshStations = executeSearch(query)
            if (freshStations.isNotEmpty()) {
                val (primaryMatches, secondaryMatches) = freshStations.partition { it.isRelevantMatch(query) }
                if (primaryMatches.isNotEmpty()) {
                    cachedSearchStations.addAll(primaryMatches.shuffled(random))
                    cachedSearchStations.addAll(secondaryMatches.shuffled(random))
                } else {
                    cachedSearchStations.addAll(freshStations.shuffled(random))
                }

                val chosen = cachedSearchStations.firstOrNull { !isRecentlyPlayed(it.channelId) }
                    ?: cachedSearchStations.first()
                cachedSearchStations.remove(chosen)
                return@withLock chosen
            }
            null
        }

        if (cached != null) {
            rememberStation(cached)
            return cached
        }

        // Local search in places list (for cities, countries)
        val localPlaceMatches = searchLocalPlaces(query)
        for (place in localPlaceMatches.shuffled(random).take(6)) {
            val stations = fetchStationsForPlace(place).shuffled(random)
            if (stations.isNotEmpty()) {
                val chosen = stations.firstOrNull { !isRecentlyPlayed(it.channelId) } ?: stations.first()
                rememberStation(chosen)
                return chosen
            }
        }

        return fallbackStation(query)
    }

    private suspend fun executeSearch(query: String): List<ResolvedStation> {
        val results = runCatching { service.search(query) }
            .onFailure { Log.w("RadioGarden", "Search failed: ${it.message}") }
            .getOrNull()
            ?.hits
            ?.hits
            ?.mapNotNull { it.source }
            .orEmpty()

        if (results.isEmpty()) return emptyList()

        val gathered = mutableListOf<ResolvedStation>()

        // 1. Direct channel hits
        for (source in results) {
            if (source.type.equals("channel", ignoreCase = true) && !source.url.isNullOrBlank()) {
                val channelId = extractIdFromUrl(source.url)
                if (!channelId.isNullOrBlank()) {
                    gathered.add(
                        ResolvedStation(
                            channelId = channelId,
                            title = source.title ?: "Radio Station",
                            city = source.page?.place?.title ?: source.subtitle.cityPart(),
                            country = source.page?.country?.title ?: source.subtitle.countryPart()
                        )
                    )
                }
            }
        }

        // 2. Resolve top place or country hits (up to 2)
        val placeSources = results.filter {
            (it.type.equals("place", ignoreCase = true) || it.type.equals("country", ignoreCase = true)) &&
                !it.url.isNullOrBlank()
        }.take(2)

        for (source in placeSources) {
            val placeId = extractIdFromUrl(source.url) ?: continue
            val tempPlace = PlaceRecord(
                id = placeId,
                title = source.title ?: source.subtitle.cityPart(),
                country = source.subtitle.countryPart(),
                size = 1
            )
            gathered.addAll(fetchStationsForPlace(tempPlace))
        }

        val distinctList = gathered.distinctBy { it.channelId }
        val (primary, secondary) = distinctList.partition { it.isRelevantMatch(query) }
        return primary + secondary
    }

    private fun ResolvedStation.isRelevantMatch(query: String): Boolean {
        val cleanQ = query.trim().lowercase()
        if (cleanQ.isBlank()) return true

        val strippedQ = cleanQ.replace("-", "").replace(" ", "")
        val titleLower = title.lowercase()
        val locationLower = location.lowercase()
        val strippedCombined = (titleLower + " " + locationLower).replace("-", "").replace(" ", "")

        if (titleLower.contains(cleanQ) || locationLower.contains(cleanQ) || strippedCombined.contains(strippedQ)) {
            return true
        }

        val queryWords = cleanQ.split("-", " ").filter { it.length >= 3 }
        if (queryWords.isNotEmpty() && queryWords.all { titleLower.contains(it) || locationLower.contains(it) }) {
            return true
        }

        return false
    }

    private suspend fun searchLocalPlaces(query: String): List<PlaceRecord> {
        val places = getPlaces()
        return places.filter { place ->
            place.title.containsQuery(query) || place.country.containsQuery(query)
        }
    }

    private suspend fun getPlaces(): List<PlaceRecord> {
        sharedPlaces?.let { return it }

        return placesMutex.withLock {
            sharedPlaces?.let { return@withLock it }
            val fetched = runCatching { service.fetchPlaces() }
                .onFailure { Log.w("RadioGarden", "Failed to fetch places list: ${it.message}") }
                .getOrNull()
                ?.data
                ?.list
                ?.filter { (it.size ?: 0) > 0 && it.id.isNotBlank() }
                .orEmpty()

            if (fetched.isNotEmpty()) {
                sharedPlaces = fetched
                sharedPlacesByCountry = fetched
                    .filter { !it.country.isNullOrBlank() }
                    .groupBy { it.country!! }
                fetched
            } else {
                fallbackPlaces
            }
        }
    }

    private suspend fun getPlacesByCountry(): Map<String, List<PlaceRecord>> {
        sharedPlacesByCountry?.let { return it }
        getPlaces()
        return sharedPlacesByCountry ?: fallbackPlaces.groupBy { it.country ?: "Worldwide" }
    }

    private fun isRecentlyPlayed(channelId: String): Boolean {
        return synchronized(sharedRecentlyPlayedIds) {
            sharedRecentlyPlayedIds.contains(channelId)
        }
    }

    private fun rememberStation(station: ResolvedStation) {
        synchronized(sharedRecentlyPlayedIds) {
            if (sharedRecentlyPlayedIds.size >= MAX_RECENT_CHANNELS) {
                sharedRecentlyPlayedIds.removeFirst()
            }
            sharedRecentlyPlayedIds.addLast(station.channelId)
        }
        if (station.country.isNotBlank()) {
            rememberCountry(station.country)
        }
    }

    private fun rememberCountry(country: String) {
        synchronized(sharedRecentCountries) {
            if (sharedRecentCountries.size >= MAX_RECENT_COUNTRIES) {
                sharedRecentCountries.removeFirst()
            }
            sharedRecentCountries.addLast(country)
        }
    }

    private fun fallbackStation(query: String? = null): ResolvedStation {
        val matching = if (query.isNullOrBlank()) {
            fallbackStations
        } else {
            fallbackStations.filter { s ->
                s.title.containsQuery(query) ||
                    s.city.containsQuery(query) ||
                    s.country.containsQuery(query)
            }.ifEmpty { fallbackStations }
        }

        val unplayed = matching.firstOrNull { !isRecentlyPlayed(it.channelId) }
            ?: matching.random(random)

        rememberStation(unplayed)
        return unplayed
    }

    private fun extractIdFromUrl(url: String?): String? {
        val pathSegments = url
            ?.substringBefore('?')
            ?.trim()
            ?.trimEnd('/')
            ?.split('/')
            ?.filter { it.isNotBlank() }
            .orEmpty()

        if (pathSegments.isEmpty()) return null

        val last = pathSegments.last()
        return when {
            last.equals("channels", ignoreCase = true) && pathSegments.size >= 2 -> pathSegments[pathSegments.lastIndex - 1]
            last.equals("channel.mp3", ignoreCase = true) && pathSegments.size >= 2 -> pathSegments[pathSegments.lastIndex - 1]
            else -> last
        }.takeIf { it.isNotBlank() }
    }

    private val PageDetails.channelPath: String?
        get() = url ?: href

    private fun String?.containsQuery(query: String): Boolean {
        return this?.contains(query, ignoreCase = true) == true
    }

    private fun String?.cityPart(): String {
        return this
            ?.substringBefore(",")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "Unknown City"
    }

    private fun String?.countryPart(): String {
        return this
            ?.substringAfterLast(",", missingDelimiterValue = "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "Worldwide"
    }
}
