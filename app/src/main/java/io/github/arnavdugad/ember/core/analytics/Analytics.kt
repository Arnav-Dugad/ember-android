package io.github.arnavdugad.ember.core.analytics

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.arnavdugad.ember.data.repo.BackendMode
import javax.inject.Inject
import javax.inject.Singleton

/** Product analytics. Event names only, never profile content or message text. No-op in Preview. */
@Singleton
class Analytics @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mode: BackendMode,
) {
    private val firebase by lazy { FirebaseAnalytics.getInstance(context) }

    fun log(event: String, vararg params: Pair<String, Any>) {
        if (mode != BackendMode.Firebase) return
        val bundle = Bundle()
        params.forEach { (key, value) ->
            when (value) {
                is String -> bundle.putString(key, value)
                is Int -> bundle.putLong(key, value.toLong())
                is Long -> bundle.putLong(key, value)
                is Boolean -> bundle.putString(key, value.toString())
                else -> bundle.putString(key, value.toString())
            }
        }
        firebase.logEvent(event, bundle)
    }
}
