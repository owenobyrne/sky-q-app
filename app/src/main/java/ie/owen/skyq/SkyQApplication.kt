package ie.owen.skyq

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import ie.owen.skyq.data.api.TvHeadendClient

class SkyQApplication : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(TvHeadendClient.authenticatedOkHttpClient())
            .build()
}
