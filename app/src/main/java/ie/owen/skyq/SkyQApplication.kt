package ie.owen.skyq

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import ie.owen.skyq.data.api.TvHeadendClient
import ie.owen.skyq.data.settings.AppSettings

class SkyQApplication : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        AppSettings.init(this)
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(TvHeadendClient.authenticatedOkHttpClient())
            .build()
}
