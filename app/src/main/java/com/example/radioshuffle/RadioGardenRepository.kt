package com.example.radioshuffle

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
    @SerializedName("place") val place: PlaceInfo?,
    @SerializedName("country") val country: CountryInfo?
)
data class PlaceInfo(@SerializedName("title") val title: String?)
data class CountryInfo(@SerializedName("title") val title: String?)

data class SearchEnvelope(@SerializedName("hits") val hits: SearchHits?)
data class SearchHits(@SerializedName("hits") val hits: List<SearchHit>?)
data class SearchHit(@SerializedName("_source") val source: SearchSource?)
data class SearchSource(
    @SerializedName("title") val title: String?,
    @SerializedName("subtitle") val subtitle: String?,
    @SerializedName("type") val type: String?,
    @SerializedName("url") val url: String?
)

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

    @GET("search")
    suspend fun search(@Query("q") query: String): SearchEnvelope

    companion object {
        fun create(): RadioGardenService {
            val client = OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
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
        val results = runCatching { service.search(query) }.getOrNull()
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
                    city = source.subtitle.cityPart(),
                    country = source.subtitle.countryPart()
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
        val places = getPlaces()
        if (places.isEmpty()) return null

        val filteredPlaces = if (query.isNullOrBlank()) {
            places
        } else {
            places.filter { place ->
                place.title.containsQuery(query) || place.country.containsQuery(query)
            }.ifEmpty { places }
        }

        val attempts = if (query.isNullOrBlank()) 7 else 12
        repeat(attempts) {
            val place = filteredPlaces[random.nextInt(filteredPlaces.size)]
            resolveFromPlace(place, query)?.let { return it }
        }

        return null
    }

    private suspend fun resolveFromPlace(place: PlaceRecord, query: String? = null): ResolvedStation? {
        val page = runCatching { service.fetchChannelsForPlace(place.id) }.getOrNull()

        val stations = page?.data?.content
            ?.flatMap { it.items ?: emptyList() }
            ?.mapNotNull { it.page }
            ?.filter { !it.channelPath.isNullOrBlank() }
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
                .getOrNull()
                ?.data
                ?.list
                ?.filter { (it.size ?: 0) > 0 && it.id.isNotBlank() }
                ?.shuffled(random)
                .orEmpty()

            if (fetched.isNotEmpty()) {
                sharedPlaces = fetched
            }
            fetched
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
