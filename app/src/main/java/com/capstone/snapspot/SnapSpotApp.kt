package com.capstone.snapspot

import android.app.Application
import android.content.Context
import com.capstone.snapspot.data.FileBranchCache
import com.capstone.snapspot.data.Repository
import com.capstone.snapspot.geofence.GeofenceSync
import com.capstone.snapspot.net.GeminiClient
import com.capstone.snapspot.net.KakaoHttp
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
    val gemini = GeminiClient(BuildConfig.GEMINI_API_KEY, BuildConfig.GEMINI_MODEL.ifEmpty { "gemini-3.5-flash-lite" }, http)
    val branchCache = FileBranchCache(dir)
    val alerts = AlertService(repo, AndroidNotifier(ctx))
    val geofenceSync = GeofenceSync(ctx, repo, kakao, branchCache)
}

class SnapSpotApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        ensureChannel(this)
        if (BuildConfig.KAKAO_NATIVE_KEY.isNotEmpty()) KakaoMapSdk.init(this, BuildConfig.KAKAO_NATIVE_KEY)
    }
}
