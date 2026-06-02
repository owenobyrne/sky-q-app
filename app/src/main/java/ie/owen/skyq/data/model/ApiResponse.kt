package ie.owen.skyq.data.model

data class ChannelGridResponse(
    val entries: List<Channel>,
    val total: Int = 0
)

data class EpgGridResponse(
    val entries: List<EpgEvent>,
    val totalCount: Int = 0
)

data class ServiceEntry(
    val channel: List<String> = emptyList()
)

data class ServiceGridResponse(
    val entries: List<ServiceEntry>,
    val total: Int = 0
)
