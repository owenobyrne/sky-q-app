package ie.owen.skyq.data.model

data class ChannelGridResponse(
    val entries: List<Channel>,
    val total: Int = 0
)

data class EpgGridResponse(
    val entries: List<EpgEvent>,
    val totalCount: Int = 0
)

data class ChannelTagEntry(val key: String, val `val`: String)
data class ChannelTagListResponse(val entries: List<ChannelTagEntry> = emptyList())

data class ServiceEntry(
    val channel: List<String> = emptyList()
)

data class ServiceGridResponse(
    val entries: List<ServiceEntry>,
    val total: Int = 0
)

