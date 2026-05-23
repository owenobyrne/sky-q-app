package ie.owen.skyq.data.model

import com.google.gson.annotations.SerializedName

data class EpgEvent(
    val eventId: Int,
    val channelUuid: String,
    val channelName: String,
    val channelNumber: String,
    val channelIcon: String? = null,
    val start: Long,
    val stop: Long,
    val title: String,
    val subtitle: String? = null,
    val summary: String? = null,
    val description: String? = null,
    val image: String? = null,
    val episodeOnscreen: String? = null,
    val genre: List<Int>? = null,
    val ageRating: Int? = null,
    val starRating: Int? = null,
    @SerializedName("copyright_year") val copyrightYear: Int? = null,
    val seriesId: String? = null,
    val episodeId: String? = null,
    val nextEventId: Int? = null
) {
    val durationMinutes: Int get() = ((stop - start) / 60).toInt()
    val isLive: Boolean get() {
        val now = System.currentTimeMillis() / 1000L
        return now in start..stop
    }
}
