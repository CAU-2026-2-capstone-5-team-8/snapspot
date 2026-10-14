package com.capstone.snapspot.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.capstone.snapspot.ui.add.AddScreen
import com.capstone.snapspot.ui.add.AddViewModel
import com.capstone.snapspot.ui.add.MAX_BATCH
import com.capstone.snapspot.ui.coupon.CouponScreen
import com.capstone.snapspot.ui.home.HomeScreen
import com.capstone.snapspot.ui.home.HomeViewModel
import com.capstone.snapspot.ui.status.StatusScreen
import com.capstone.snapspot.ui.theme.SnapColors
import com.capstone.snapspot.ui.theme.SnapShapes
import com.capstone.snapspot.ui.theme.SnapType

/**
 * Screens: home (map + list), add, status, coupon/{id}. `deepLink` comes from tapped notifications,
 * `shared` from images other apps shared to SnapSpot.
 */
@Composable
fun SnapSpotNav(deepLink: Uri?, onDeepLinkHandled: () -> Unit, shared: List<Uri>?, onSharedHandled: () -> Unit) {
    val nav = rememberNavController()
    val home: HomeViewModel = viewModel()
    val add: AddViewModel = viewModel()
    val ctx = LocalContext.current
    var explainBackground by remember { mutableStateOf(false) }
    var missingKey by remember { mutableStateOf(false) }
    var askedOnce by rememberSaveable { mutableStateOf(false) }

    // Re-check permissions and reload data written by background work whenever the app comes back;
    // stop following the position while it is out of sight.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> home.onResume()
                Lifecycle.Event.ON_STOP -> home.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { home.refreshPerms() }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        home.refreshPerms()
        if (home.perms.value.fg && !home.perms.value.bg) explainBackground = true
    }
    val foreground = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        home.refreshPerms()
        when {
            !home.perms.value.fg -> Unit
            Build.VERSION.SDK_INT >= 33 && !home.perms.value.notif -> notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            !home.perms.value.bg -> explainBackground = true
        }
    }
    fun askPermissions() {
        val p = home.perms.value
        when {
            !p.fg -> foreground.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            Build.VERSION.SDK_INT >= 33 && !p.notif -> notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            !p.bg -> explainBackground = true
            !p.notif -> ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
            !p.net -> ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
    }
    LaunchedEffect(Unit) {
        if (!askedOnce && !home.perms.value.fg) {
            askedOnce = true
            askPermissions()
        }
    }

    fun addScreenshots(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (!add.hasGeminiKey()) {
            missingKey = true
            return
        }
        val left = add.start(uris)
        if (left > 0) Toast.makeText(ctx, "한 번에 ${MAX_BATCH}장까지 넣을 수 있어서 앞의 ${MAX_BATCH}장만 읽을게요.", Toast.LENGTH_LONG).show()
        nav.navigate("add") { launchSingleTop = true }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_BATCH)) { uris ->
        addScreenshots(uris)
    }

    LaunchedEffect(shared) {
        val uris = shared ?: return@LaunchedEffect
        addScreenshots(uris)
        onSharedHandled()
    }

    LaunchedEffect(deepLink) {
        val uri = deepLink ?: return@LaunchedEffect
        val id = uri.lastPathSegment
        when (uri.host) {
            "coupon" -> if (id != null) nav.navigate("coupon/$id")
            "place" -> {
                nav.popBackStack("home", inclusive = false)
                if (id != null) home.focusOn(id)
            }
            "bundle" -> {
                nav.popBackStack("home", inclusive = false)
                if (id != null) home.openBundleFor(id)
            }
        }
        onDeepLinkHandled()
    }

    NavHost(nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                vm = home,
                onAdd = {
                    if (!add.hasGeminiKey()) missingKey = true
                    else picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onStatus = { nav.navigate("status") },
                onCoupon = { nav.navigate("coupon/$it") },
                onAskPermissions = ::askPermissions,
            )
        }
        composable("add") {
            // Back closes the whole batch, so screenshots still waiting are not read for nothing.
            BackHandler { add.cancel(); nav.popBackStack() }
            AddScreen(
                vm = add,
                onClose = { nav.popBackStack() },
                onSaved = { kind, id ->
                    nav.popBackStack("home", inclusive = false)
                    if (kind == "place") home.focusOn(id) else nav.navigate("coupon/$id")
                },
                onFinished = { summary ->
                    nav.popBackStack("home", inclusive = false)
                    Toast.makeText(ctx, summary, Toast.LENGTH_LONG).show()
                },
                onOpenPlace = { id -> nav.popBackStack("home", inclusive = false); home.focusOn(id) },
                onOpenCoupon = { id -> nav.popBackStack("home", inclusive = false); nav.navigate("coupon/$id") },
            )
        }
        composable("status") {
            StatusScreen(
                vm = home,
                onBack = { nav.popBackStack() },
                onAskPermissions = ::askPermissions,
                onLocationSettings = { ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
            )
        }
        composable("coupon/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            CouponScreen(vm = home, id = entry.arguments?.getString("id").orEmpty(), onClose = { nav.popBackStack() })
        }
    }

    if (explainBackground) {
        AlertDialog(
            onDismissRequest = { explainBackground = false },
            title = { Text("백그라운드 위치", style = SnapType.title) },
            text = { Text("앱을 닫아도 알림을 받으려면 위치 권한을 \"항상 허용\"으로 바꿔 주세요. 다음 화면에서 \"항상 허용\"을 골라 주세요.", style = SnapType.body) },
            confirmButton = {
                TextButton(onClick = {
                    explainBackground = false
                    if (Build.VERSION.SDK_INT >= 29) background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }) { Text("설정하기", color = SnapColors.ink, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { explainBackground = false }) { Text("나중에", color = SnapColors.muted) } },
            containerColor = SnapColors.surface,
            shape = SnapShapes.card,
        )
    }
    if (missingKey) {
        AlertDialog(
            onDismissRequest = { missingKey = false },
            title = { Text("설정이 필요해요", style = SnapType.title) },
            text = { Text("local.properties 파일에 GEMINI_API_KEY를 넣고 다시 빌드해 주세요.", style = SnapType.body) },
            confirmButton = { TextButton(onClick = { missingKey = false }) { Text("확인", color = SnapColors.ink) } },
            containerColor = SnapColors.surface,
            shape = SnapShapes.card,
        )
    }
}
