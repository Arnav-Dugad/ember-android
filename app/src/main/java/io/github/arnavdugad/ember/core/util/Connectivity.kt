package io.github.arnavdugad.ember.core.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** "Is there a usable network right now?" Used to avoid waiting on the server while offline. */
@Singleton
class Connectivity @Inject constructor(@ApplicationContext private val context: Context) {
    fun isOnline(): Boolean = isOnline(context)

    companion object {
        /** True when the active network has validated internet access. A hint, not a guarantee. */
        fun isOnline(context: Context): Boolean {
            val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
            val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    }
}
