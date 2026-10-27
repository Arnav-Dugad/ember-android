package io.github.arnavdugad.ember

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dagger.hilt.android.AndroidEntryPoint
import io.github.arnavdugad.ember.ui.root.DeepLinks
import io.github.arnavdugad.ember.ui.root.EmberRoot
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var deepLinks: DeepLinks

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) deepLinks.handle(intent)
        setContent { EmberRoot() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        deepLinks.handle(intent)
    }
}
