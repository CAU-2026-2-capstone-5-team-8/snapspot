import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun key(name: String, default: String = ""): String =
    "\"" + (localProps.getProperty(name) ?: System.getenv(name) ?: default) + "\""

android {
    namespace = "com.capstone.snapspot"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.capstone.snapspot"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.2.0"
        buildConfigField("String", "GEMINI_API_KEY", key("GEMINI_API_KEY"))
        buildConfigField("String", "GEMINI_MODEL", key("GEMINI_MODEL", "gemini-3.5-flash-lite"))
        buildConfigField("String", "KAKAO_REST_KEY", key("KAKAO_REST_KEY"))
        buildConfigField("String", "KAKAO_NATIVE_KEY", key("KAKAO_NATIVE_KEY"))
        // Kakao Map ships ARM libraries only; x86_64 emulators run them through ARM translation.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.useJUnitPlatform()
            // ./gradlew :app:testDebugUnitTest -Psim [-PsimWrite]: geofence strategy simulation (docs/simulation.md).
            if (project.hasProperty("sim")) {
                it.filter.includeTestsMatching("*SimulateWindow*")
                it.systemProperty("sim.run", "true")
                it.systemProperty("sim.write", project.hasProperty("simWrite").toString())
                it.systemProperty("sim.out", rootProject.file("docs/simulation.md").absolutePath)
                it.testLogging.showStandardStreams = true
                it.outputs.upToDateWhen { false }
            }
            // ./gradlew :app:testDebugUnitTest -PsimFetch: refresh the simulation's real Seoul data from Kakao.
            if (project.hasProperty("simFetch")) {
                it.filter.includeTestsMatching("*FetchSimData*")
                it.systemProperty("sim.fetch", "true")
                it.systemProperty("sim.kakaoKey", localProps.getProperty("KAKAO_REST_KEY") ?: System.getenv("KAKAO_REST_KEY") ?: "")
                it.systemProperty("sim.dataOut", project.file("src/test/resources/sim/seoul.json").absolutePath)
                it.testLogging.showStandardStreams = true
                it.outputs.upToDateWhen { false }
            }
            // ./gradlew :app:testDebugUnitTest -PuniformSim [-PuniformWrite]: refresh radius over evenly spread branches (docs/refresh-radius.md).
            if (project.hasProperty("uniformSim")) {
                it.filter.includeTestsMatching("*UniformBranches*")
                it.systemProperty("uniform.run", "true")
                it.systemProperty("uniform.write", project.hasProperty("uniformWrite").toString())
                it.systemProperty("uniform.out", rootProject.file("docs/refresh-radius.md").absolutePath)
                it.testLogging.showStandardStreams = true
                it.outputs.upToDateWhen { false }
            }
            // ./gradlew :app:testDebugUnitTest -ProuteBench [-ProuteWrite]: route algorithm benchmark (docs/route.md).
            if (project.hasProperty("routeBench")) {
                it.filter.includeTestsMatching("*RouteBench*")
                it.systemProperty("route.run", "true")
                it.systemProperty("route.write", project.hasProperty("routeWrite").toString())
                it.systemProperty("route.out", rootProject.file("docs/route.md").absolutePath)
                it.testLogging.showStandardStreams = true
                it.outputs.upToDateWhen { false }
            }
        }
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.play.location)
    implementation(libs.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play)
    implementation(libs.kakao.map)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
