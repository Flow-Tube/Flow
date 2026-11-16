// ==================================================================================================
// This implementation was based on metrolist's (https://github.com/MetrolistGroup/Metrolist)
// ==================================================================================================

package io.github.aedev.flow.data.lyrics.kugou.models

import com.google.gson.annotations.SerializedName

data class SearchLyricsResponse(
    val status: Int = 0,
    val info: String = "",
    val errcode: Int = 0,
    val errmsg: String = "",
    val expire: Int = 0,
    val candidates: List<Candidate> = emptyList(),
) {
    data class Candidate(
        val id: Long = 0,
        @SerializedName("product_from")
        val productFrom: String = "",
        val duration: Long = 0,
        val accesskey: String = "",
        val song: String = "",
        val singer: String = "",
    )
}
