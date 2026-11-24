import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// ── 签名材料（v1.0.2 起固定，不再用 AGP 随机生成的 debug keystore）─────────────
//
// 两个来源，按优先级：
// 1. 环境变量 —— CI 用：workflow 把 Secrets 里的 keystore 解码成临时文件，
//    ANDROID_KEYSTORE_PATH 指过去，密码走 ANDROID_KEYSTORE_PASSWORD 等；
// 2. signing/keystore.properties —— 本地开发用，配合 signing 目录下的 jks（已 gitignore）。
//
// 两边必须是同一把钥匙，否则本地包和 CI 包不能互相覆盖安装。
val signingPropsFile = rootProject.file("signing/keystore.properties")
val signingProps = Properties().apply {
    if (signingPropsFile.isFile) signingPropsFile.inputStream().use { load(it) }
}
fun signingValue(propKey: String, envKey: String): String? =
    signingProps.getProperty(propKey)?.takeIf { it.isNotBlank() }
        ?: System.getenv(envKey)?.takeIf { it.isNotBlank() }

private val ksPath = System.getenv("ANDROID_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
    ?: signingProps.getProperty("storeFile")?.takeIf { it.isNotBlank() }
    ?: "signing/baiyunkeys-release.jks"
private val keystoreFile = rootProject.file(ksPath)
private val ksStorePassword = signingValue("storePassword", "ANDROID_KEYSTORE_PASSWORD")
private val ksKeyAlias = signingValue("keyAlias", "ANDROID_KEY_ALIAS")
private val ksKeyPassword = signingValue("keyPassword", "ANDROID_KEY_PASSWORD")
private val hasReleaseSigning =
    keystoreFile.isFile && !ksStorePassword.isNullOrEmpty() && !ksKeyAlias.isNullOrEmpty()

/** CI 环境：GitHub Actions 会设置 CI=true。 */
private val isCi = System.getenv("CI") != null

android {
    namespace = "com.xavior.baiyunkeys"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.xavior.baiyunkeys"
        // 方案原定 29（主楼门禁机是 Android 10）。下探到 28 是为了让 Android 9 的实机
        // 也能装上来做真机验证；代码里所有 API 差异都有 SDK_INT 分支，
        // 28 只是少一个 foregroundServiceType 属性（29 才引入，低版本自动忽略）。
        minSdk = 28
        targetSdk = 35
        versionCode = 5
        versionName = "1.1.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = keystoreFile
                storePassword = ksStorePassword
                keyAlias = ksKeyAlias
                keyPassword = ksKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            // debug 包也用同一把钥匙：README 上「滚动 latest」渠道发的就是 debug 产物，
            // 若它与正式包证书不同，用户从两个入口混装就得反复卸载。
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                // 本地没放签名材料时退回 debug key，方便跑通构建；
                // CI 上不允许静默降级，见文件末尾的 taskGraph 守卫。
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// CI 守卫：构建 release APK 时若没有签名材料，直接失败。
// 宁可红一次，也不能悄悄退回 debug keystore —— 那样发出去的包签名又和上一版不同，
// 用户装完下次还得卸载。
gradle.taskGraph.whenReady {
    val wantsReleaseApk = allTasks.any {
        it.name == "assembleRelease" || it.name == "packageRelease" || it.name == "bundleRelease"
    }
    if (wantsReleaseApk && isCi && !hasReleaseSigning) {
        throw GradleException(
            "CI 上构建 release APK 需要签名材料（ANDROID_KEYSTORE_PATH / ANDROID_KEYSTORE_PASSWORD / " +
                "ANDROID_KEY_ALIAS / ANDROID_KEY_PASSWORD）；拒绝回落到 debug 签名。",
        )
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.zxing.core)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.glance.appwidget)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
