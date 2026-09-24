import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.aksulightning.flyby"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "io.github.aksulightning.flyby"
        minSdk = 26
        targetSdk = 35
        testInstrumentationRunner = "io.github.aksulightning.flyby.VmInstrumentation"
        versionCode = providers.gradleProperty("flybyVersionCode").getOrElse("2").toInt()
        versionName = providers.gradleProperty("flybyVersionName").getOrElse("0.2.0-dev")
        val revision = providers.gradleProperty("flybyCommit").getOrElse("local-unpublished")
        require(revision.matches(Regex("[a-z0-9-]{7,40}")))
        buildConfigField("String", "REVISION", "\"$revision\"")
        // x86_64 is an explicit Android-emulator test build; device APKs stay ARM64.
        val abi = providers.gradleProperty("flybyAbi").getOrElse("arm64-v8a")
        require(abi in setOf("arm64-v8a", "x86_64")) { "Unsupported flybyAbi: $abi" }
        ndk { abiFilters += abi }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_shared" } }
    }
    if (providers.gradleProperty("flybyNightly").orNull == "true") {
        signingConfigs.getByName("debug") {
            storeFile = file("../out/signing/debug.keystore")
            require(storeFile!!.isFile) { "Provision the nightly development key before building" }
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets.getByName("main").assets.srcDir("../docs/licenses")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        // MVP targets physical ARM64 Android, not ChromeOS/x86 translation.
        disable += "ChromeOsAbiSupport"
    }
    externalNativeBuild {
        cmake {
            path = file("../native/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    androidResources { noCompress += listOf("kernel", "firmware", "initrd", "seed") }

}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.03.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
}

val verifyGuestAssets by tasks.registering {
    doLast {
        listOf("kernel", "firmware", "initrd", "manifest.json", "disk.seed", "system.seed").forEach { name ->
            check(file("src/main/assets/vm/$name").let { it.isFile && it.length() > 0 }) {
                "Guest assets missing. Run python3 scripts/prepare-alpine-riscv64.py before assembling an APK."
            }
        }
    }
}
tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("Assets")) dependsOn(verifyGuestAssets)
}

// aapt expands .gz assets. Catch filename/content changes in the final APK,
// not just the source asset directory (the first Android disk test found this).
val verifyDebugApkAssets by tasks.registering {
    dependsOn("packageDebug")
    doLast {
        ZipFile(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile).use { apk ->
            for (name in listOf("disk.seed", "system.seed")) {
            val seed = checkNotNull(apk.getEntry("assets/vm/$name")) { "Disk seed missing from APK" }
            apk.getInputStream(seed).use {
                check(it.read() == 0x1f && it.read() == 0x8b) { "APK disk seed must retain its gzip encoding" }
            }
            }
        }
    }
}
tasks.configureEach {
    if (name == "assembleDebug") dependsOn(verifyDebugApkAssets)
}
