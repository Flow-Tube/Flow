package io.github.aedev.flow.data.local

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Reads and writes the stored history blob. The JSON keeps the keys and values Gson wrote (enum
 * names, no null fields), so blobs written before and after this codec read the same either way.
 *
 * Enums are carried as names: one this build does not know, from a newer version or a backup,
 * falls back to its default, and an unknown feature is dropped, instead of failing the whole list.
 */
internal object SearchHistoryCodec {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }
    private val listSerializer = ListSerializer(StoredSearch.serializer())

    fun decode(stored: String?): List<SearchHistoryItem> {
        if (stored.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(listSerializer, stored) }
            .getOrDefault(emptyList())
            .mapNotNull(StoredSearch::toItem)
    }

    fun encode(items: List<SearchHistoryItem>): String = json.encodeToString(listSerializer, items.map(::StoredSearch))
}

@Serializable
private data class StoredSearch(
    val id: String? = null,
    val query: String? = null,
    val timestamp: Long = 0L,
    val type: String? = null,
    val filters: StoredSearchFilter? = null,
) {
    constructor(item: SearchHistoryItem) : this(
        id = item.id,
        query = item.query,
        timestamp = item.timestamp,
        type = item.sanitized().type.name,
        filters = item.sanitized().filters?.let(::StoredSearchFilter),
    )

    fun toItem(): SearchHistoryItem? {
        val text = query?.takeIf(String::isNotBlank) ?: return null
        return SearchHistoryItem(
            // Gson stored no id only if one was never written; a stable stand-in keeps delete working.
            id = id ?: "legacy:$timestamp:$text",
            query = text,
            timestamp = timestamp,
            type = enumOrDefault(type, SearchType.TEXT),
            filters = filters?.toFilter(),
        )
    }
}

@Serializable
private data class StoredSearchFilter(
    val contentType: String? = null,
    val duration: String? = null,
    val uploadDate: String? = null,
    val sortType: String? = null,
    val features: List<String> = emptyList(),
    val watchState: String? = null,
) {
    constructor(filter: SearchFilter) : this(
        contentType = filter.contentType.name,
        duration = filter.duration.name,
        uploadDate = filter.uploadDate.name,
        sortType = filter.sortType.name,
        features = filter.features.map { it.name },
        watchState = filter.watchState.name,
    )

    fun toFilter() =
        SearchFilter(
            contentType = enumOrDefault(contentType, ContentType.ALL),
            duration = enumOrDefault(duration, Duration.ANY),
            uploadDate = enumOrDefault(uploadDate, UploadDate.ANY),
            sortType = enumOrDefault(sortType, SortType.RELEVANCE),
            features = features.mapNotNullTo(LinkedHashSet()) { name -> SearchFeature.entries.firstOrNull { it.name == name } },
            watchState = enumOrDefault(watchState, WatchState.ANY),
        )
}

private inline fun <reified T : Enum<T>> enumOrDefault(
    name: String?,
    default: T,
): T = enumValues<T>().firstOrNull { it.name == name } ?: default
