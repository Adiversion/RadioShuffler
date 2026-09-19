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
import kotlin.random.Random
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// --- API models ---
data class PlacesEnvelope(@SerializedName("data") val data: PlacesData?)
data class PlacesData(@SerializedName("list") val list: List<PlaceRecord>?)
data class PlaceRecord(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String?,
    @SerializedName("country") val country: String?,
    @SerializedName("size") val size: Int?
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
    val channelId: String,
    val title: String,
    val city: String,
    val country: String
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
        fun create(): RadioGardenService {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
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

            return Retrofit.Builder()
                .baseUrl("https://radio.garden/api/")
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(RadioGardenService::class.java)
        }
    }
}

class RadioGardenRepository(
    private val service: RadioGardenService = RadioGardenService.create(),
    private val random: Random = Random.Default
) {
    companion object {
        @Volatile
        private var sharedPlaces: List<PlaceRecord>? = null
        private val placesMutex = Mutex()
        private val sharedRecentlyPlayedIds = ArrayDeque<String>(40)

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
    }

    suspend fun nextStation(query: String? = null): ResolvedStation? {
        val cleanedQuery = query?.trim()?.takeIf { it.isNotBlank() }
        return if (cleanedQuery == null) {
            randomStation()
        } else {
            searchStation(cleanedQuery) ?: randomStation(cleanedQuery)
        }
    }

    private suspend fun searchStation(query: String): ResolvedStation? {
        val results = runCatching { service.search(query) }
            .onFailure { Log.w("RadioGarden", "Search failed: ${it.message}") }
            .getOrNull()
            ?.hits
            ?.hits
            ?.mapNotNull { it.source }
            .orEmpty()

        val channelSources = results
            .filter { it.type.equals("channel", ignoreCase = true) && !it.url.isNullOrBlank() }
            .shuffled(random)

        chooseFresh(channelSources, { extractIdFromUrl(it.url) })?.let { source ->
            val channelId = extractIdFromUrl(source.url)
            if (!channelId.isNullOrBlank()) {
                remember(channelId)
                return ResolvedStation(
                    channelId = channelId,
                    title = source.title ?: "Radio Station",
                    city = source.page?.place?.title ?: source.subtitle.cityPart(),
                    country = source.page?.country?.title ?: source.subtitle.countryPart()
                )
            }
        }

        val placeSources = results
            .filter {
                (it.type.equals("place", ignoreCase = true) ||
                    it.type.equals("country", ignoreCase = true)) &&
                    !it.url.isNullOrBlank()
            }
            .shuffled(random)

        for (source in placeSources) {
            val placeId = extractIdFromUrl(source.url) ?: continue
            val place = PlaceRecord(
                id = placeId,
                title = source.title ?: source.subtitle.cityPart(),
                country = source.subtitle.countryPart(),
                size = 1
            )

            resolveFromPlace(place, query)?.let { return it }
        }

        return null
    }

    private suspend fun randomStation(query: String? = null): ResolvedStation? {
        val places = getPlaces().ifEmpty { fallbackPlaces }

        val filteredPlaces = if (query.isNullOrBlank()) {
            places
        } else {
            places.filter { place ->
                place.title.containsQuery(query) || place.country.containsQuery(query)
            }.ifEmpty { places }
        }

        val attempts = if (query.isNullOrBlank()) 10 else 15
        repeat(attempts) {
            val place = filteredPlaces[random.nextInt(filteredPlaces.size)]
            resolveFromPlace(place, query)?.let { return it }
        }

        return fallbackStation(query)
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

        val fresh = chooseFresh(matching.shuffled(random)) { it.channelId }
            ?: matching.random(random)

        remember(fresh.channelId)
        return fresh
    }

    private suspend fun resolveFromPlace(place: PlaceRecord, query: String? = null): ResolvedStation? {
        val page = runCatching { service.fetchPage(place.id) }.getOrNull()
            ?: runCatching { service.fetchChannelsForPlace(place.id) }
                .onFailure { Log.w("RadioGarden", "Failed to fetch channels for ${place.id}: ${it.message}") }
                .getOrNull()

        val stations = page?.data?.content
            ?.flatMap { it.items ?: emptyList() }
            ?.mapNotNull { it.page }
            ?.filter { it.isChannel && !it.channelPath.isNullOrBlank() }
            ?.shuffled(random)
            .orEmpty()

        if (stations.isEmpty()) return null

        val queryMatches = query?.takeIf { it.isNotBlank() }?.let { cleanQuery ->
            stations.filter { station ->
                station.title.containsQuery(cleanQuery) ||
                    station.place?.title.containsQuery(cleanQuery) ||
                    station.country?.title.containsQuery(cleanQuery)
            }
        }.orEmpty()

        val orderedStations = if (queryMatches.isEmpty()) stations else queryMatches + (stations - queryMatches.toSet())
        val candidate = chooseFresh(orderedStations, { extractIdFromUrl(it.channelPath) }) ?: return null
        val channelId = extractIdFromUrl(candidate.channelPath) ?: return null

        if (channelId.isBlank()) return null

        remember(channelId)
        return ResolvedStation(
            channelId = channelId,
            title = candidate.title ?: "World Radio",
            city = candidate.place?.title ?: place.title ?: "Unknown City",
            country = candidate.country?.title ?: place.country ?: "Worldwide"
        )
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
                ?.shuffled(random)
                .orEmpty()

            if (fetched.isNotEmpty()) {
                sharedPlaces = fetched
                fetched
            } else {
                fallbackPlaces
            }
        }
    }

    private fun remember(channelId: String) {
        synchronized(sharedRecentlyPlayedIds) {
            if (sharedRecentlyPlayedIds.size >= 40) {
                sharedRecentlyPlayedIds.removeFirst()
            }
            sharedRecentlyPlayedIds.addLast(channelId)
        }
    }

    private fun <T> chooseFresh(items: List<T>, idFor: (T) -> String?): T? {
        if (items.isEmpty()) return null
        val recentSnapshot = synchronized(sharedRecentlyPlayedIds) { sharedRecentlyPlayedIds.toSet() }
        return items.firstOrNull { item ->
            val id = idFor(item)
            !id.isNullOrBlank() && !recentSnapshot.contains(id)
        } ?: items.firstOrNull()
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
