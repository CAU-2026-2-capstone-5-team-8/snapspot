package com.capstone.snapspot

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.IntentCompat
import com.capstone.snapspot.ui.SnapSpotNav
import com.capstone.snapspot.ui.theme.SnapColors
import com.capstone.snapspot.ui.theme.SnapTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    /** snapspot://coupon/{id} or snapspot://place/{id}, from a tapped notification. */
    private val deepLink = MutableStateFlow<Uri?>(null)
    /** Images another app shared to SnapSpot (one or several). */
    private val shared = MutableStateFlow<List<Uri>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(SnapColors.paper.toArgb(), SnapColors.paper.toArgb()),
            navigationBarStyle = SystemBarStyle.light(SnapColors.paper.toArgb(), SnapColors.paper.toArgb()),
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) receive(intent)
        setContent {
            val link by deepLink.collectAsState()
            val images by shared.collectAsState()
            SnapTheme { SnapSpotNav(link, { deepLink.value = null }, images, { shared.value = null }) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receive(intent)
    }

    private fun receive(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND -> shared.value = listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> shared.value = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> deepLink.value = intent.data
        }
    }
}
