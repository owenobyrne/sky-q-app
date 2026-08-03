package ie.owen.skyq.data.model

import com.google.gson.annotations.SerializedName

data class Channel(
    val uuid: String,
    val name: String,
    val number: Int,
    @SerializedName("icon_public_url") val iconPublicUrl: String? = null
)
