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
import com.capstone.snapspot.ui.SnapSpotNav
import com.capstone.snapspot.ui.theme.SnapColors
import com.capstone.snapspot.ui.theme.SnapTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    /** snapspot://coupon/{id} or snapspot://place/{id}, from a tapped notification. */
    private val deepLink = MutableStateFlow<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(SnapColors.paper.toArgb(), SnapColors.paper.toArgb()),
            navigationBarStyle = SystemBarStyle.light(SnapColors.paper.toArgb(), SnapColors.paper.toArgb()),
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) deepLink.value = intent?.data
        setContent {
            val link by deepLink.collectAsState()
            SnapTheme { SnapSpotNav(link) { deepLink.value = null } }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        deepLink.value = intent.data
    }
}
