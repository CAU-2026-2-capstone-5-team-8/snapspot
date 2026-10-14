package com.capstone.snapspot

import android.app.Application
import android.content.Context
import com.capstone.snapspot.core.fallbackBundleName
import com.capstone.snapspot.data.FileBranchCache
import com.capstone.snapspot.data.PendingStore
import com.capstone.snapspot.data.RegionNames
import com.capstone.snapspot.data.Repository
import com.capstone.snapspot.geofence.GeofenceSync
import com.capstone.snapspot.location.LocationTracker
import com.capstone.snapspot.net.GeminiClient
import com.capstone.snapspot.net.KakaoHttp
import com.capstone.snapspot.net.TmapWalk
import com.capstone.snapspot.notify.AlertService
import com.capstone.snapspot.notify.AndroidNotifier
import com.capstone.snapspot.notify.ensureChannel
import com.kakao.vectormap.KakaoMapSdk
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** One instance of everything, shared by screens, receivers and workers in this process. */
class AppContainer(ctx: Context) {
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    private val dir = File(ctx.filesDir, "store")
    val repo = Repository(dir)
    val kakao = KakaoHttp(BuildConfig.KAKAO_REST_KEY, http)
    val walk = TmapWalk(BuildConfig.TMAP_APP_KEY, http)
    val gemini = GeminiClient(BuildConfig.GEMINI_API_KEY, BuildConfig.GEMINI_MODEL.ifEmpty { "gemini-3.5-flash-lite" }, http)
    val branchCache = FileBranchCache(dir)
    val pending = PendingStore(dir)
    val regionNames = RegionNames(dir)
    /** Set by the home screen's lifecycle; read by [AlertService]. */
    @Volatile var onScreen = false
    val alerts = AlertService(repo, AndroidNotifier(ctx), onScreen = { onScreen }, bundleName = { b -> regionNames.cached(b.center) ?: fallbackBundleName(b) })
    val geofenceSync = GeofenceSync(ctx, repo, kakao, branchCache)
    val location = LocationTracker(ctx)
}

class SnapSpotApp : Application() {
    lateinit var container: AppContainer
        private set

    /** False when the map SDK could not start (no native key, or no native library for this CPU). */
    var mapReady = false
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        ensureChannel(this)
        mapReady = BuildConfig.KAKAO_NATIVE_KEY.isNotEmpty() &&
            runCatching { KakaoMapSdk.init(this, BuildConfig.KAKAO_NATIVE_KEY) }.isSuccess
    }
}
