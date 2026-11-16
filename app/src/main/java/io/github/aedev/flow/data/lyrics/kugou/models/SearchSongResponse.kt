// ==================================================================================================
// This implementation was based on metrolist's (https://github.com/MetrolistGroup/Metrolist)
// ==================================================================================================

package io.github.aedev.flow.data.lyrics.kugou.models

data class SearchSongResponse(
    val status: Int = 0,
    val errcode: Int = 0,
    val error: String = "",
    val data: Data = Data(),
) {
    data class Data(
        val info: List<Info> = emptyList(),
    ) {
        data class Info(
            val duration: Int = 0,
            val hash: String = "",
            val songname: String = "",
            val singername: String = "",
        )
    }
}
