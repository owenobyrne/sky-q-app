package ie.owen.skyq.data.api

import ie.owen.skyq.data.model.ChannelGridResponse
import ie.owen.skyq.data.model.EpgGridResponse
import retrofit2.http.GET
import retrofit2.http.Query

interface TvHeadendApi {

    @GET("api/channel/grid")
    suspend fun getChannels(
        @Query("limit") limit: Int = 999,
        @Query("sort_key") sortKey: String = "number",
        @Query("sort_dir") sortDir: String = "ASC"
    ): ChannelGridResponse

    @GET("api/epg/events/grid")
    suspend fun getEpgEvents(
        @Query("limit") limit: Int = 9999,
        @Query("start") start: Int = 0,
        @Query("channel") channel: String? = null
    ): EpgGridResponse
}
