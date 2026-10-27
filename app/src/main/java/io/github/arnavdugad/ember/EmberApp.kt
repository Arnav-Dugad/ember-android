package io.github.arnavdugad.ember

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import dagger.hilt.android.HiltAndroidApp
import io.github.arnavdugad.ember.push.Notifications
import io.github.arnavdugad.ember.update.UpdateWorker
import okhttp3.OkHttpClient
import javax.inject.Inject

@HiltAndroidApp
class EmberApp :
    Application(),
    Configuration.Provider,
    SingletonImageLoader.Factory {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var okHttp: OkHttpClient

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        if (FirebaseApp.getApps(this).isNotEmpty()) configureFirebase()
        Notifications.createChannels(this)
        // Debug builds have a different application id and signing key, so a release APK could
        // never install over them; only real releases poll for updates in the background.
        if (!BuildConfig.DEBUG) UpdateWorker.schedule(this)
    }

    private fun configureFirebase() {
        FirebaseAppCheck.getInstance().installAppCheckProviderFactory(appCheckFactory())
        FirebaseRemoteConfig.getInstance().apply {
            setConfigSettingsAsync(FirebaseRemoteConfigSettings.Builder().setMinimumFetchIntervalInSeconds(3600).build())
            setDefaultsAsync(mapOf("hide_demo_profiles" to false, "demo_hide_threshold" to 500L))
            fetchAndActivate()
        }
    }

    /**
     * Play Integrity in release. Debug builds use the debug provider, which prints a token to
     * Logcat that you register in the Firebase console. It is a debug-only dependency, hence
     * the reflective lookup.
     */
    private fun appCheckFactory(): AppCheckProviderFactory {
        if (BuildConfig.DEBUG) {
            runCatching {
                val clazz = Class.forName("com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory")
                return clazz.getMethod("getInstance").invoke(null) as AppCheckProviderFactory
            }
        }
        return PlayIntegrityAppCheckProviderFactory.getInstance()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { okHttp }))
            add(SvgDecoder.Factory())
        }
        .crossfade(true)
        .build()
}
