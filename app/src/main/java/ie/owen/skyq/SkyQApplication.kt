package ie.owen.skyq

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import ie.owen.skyq.data.api.TvHeadendClient
import ie.owen.skyq.data.settings.AppSettings
import ie.owen.skyq.ui.video.isAmlogicDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Request

class SkyQApplication : Application(), ImageLoaderFactory {

    private val appScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        // Non-blocking: kicks the keystore-backed settings load onto an IO thread.
        AppSettings.init(this)

        // Warm the codec query. MediaCodecList(ALL_CODECS).codecInfos talks to the media
        // server and can take hundreds of milliseconds on low-end TV hardware; touching the
        // lazy here overlaps that cost with Activity/Compose startup instead of paying it on
        // the main thread when VideoViewModel builds its players.
        appScope.launch { isAmlogicDevice }
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            // Resolved per request rather than captured once. Two reasons: nothing is built
            // on the main thread here (Coil calls this from its IO dispatcher), and the
            // client always reflects the current credentials — which matters because the
            // settings load is asynchronous and the user can change the server at runtime.
            .callFactory(object : Call.Factory {
                override fun newCall(request: Request): Call =
                    TvHeadendClient.authenticatedOkHttpClient().newCall(request)
            })
            .build()
}
