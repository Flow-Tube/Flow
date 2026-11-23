package io.github.aedev.flow.data.local.dao

/**
 * The rows a move has to write to store [wantedIds] as a playlist's order. When [current] (in
 * stored order) already sits at 0 to n-1 and holds the same videos, only the rows whose index
 * changed are returned, so moving one video writes just the range it crossed. Anything else, such as
 * legacy negative or gapped positions, gets every row written, which also renumbers them.
 */
internal fun changedPositions(
    current: List<PlaylistVideoPosition>,
    wantedIds: List<String>,
): List<PlaylistVideoPosition> {
    val wanted = wantedIds.mapIndexed { index, videoId -> PlaylistVideoPosition(videoId, index.toLong()) }
    val contiguous = current.withIndex().all { (index, row) -> row.position == index.toLong() }
    val sameVideos = current.size == wantedIds.size && current.mapTo(HashSet()) { it.videoId } == wantedIds.toSet()
    if (!contiguous || !sameVideos) return wanted
    return wanted.filterIndexed { index, row -> current[index].videoId != row.videoId }
}
