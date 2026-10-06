import java.security.KeyStore
import java.security.cert.X509Certificate

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseKeystore = System.getenv("KEYSTORE_FILE")?.takeIf { it.isNotBlank() }?.let { File(it) }
val releaseStorePassword = System.getenv("KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("KEY_ALIAS")
val releaseKeyPassword = System.getenv("KEY_PASSWORD")
val releaseSigningConfigured = releaseKeystore?.isFile == true &&
    listOf(releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { !it.isNullOrBlank() }

android {
    namespace = "com.inonvation.campbox"
    compileSdk = 35

    signingConfigs {
        create("fixedDebug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // 本机环境变量或 CI Secrets 提供，Release 不再回退调试证书。
        if (releaseSigningConfigured) {
            create("ciRelease") {
                storeFile = releaseKeystore
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    defaultConfig {
        applicationId = "com.inonvation.campbox"
        minSdk = 26
        targetSdk = 35
        versionCode = project.findProperty("buildVersionCode")?.toString()?.toIntOrNull() ?: 30105
        versionName = project.findProperty("buildVersionName")?.toString() ?: "3.1.5"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fixedDebug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("ciRelease")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    lint {
        disable += "NullSafeMutableLiveData"
        // Compose lint 检测器与当前 Kotlin 版本存在兼容性 bug
        disable += "RememberInComposition"
        disable += "FrequentlyChangingValue"
        disable += "AutoboxingStateCreation"
    }
}

dependencies {
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation(platform("androidx.compose:compose-bom:2025.12.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    debugImplementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.3")
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("com.squareup.retrofit2:retrofit:2.12.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.2")
    compileOnly("com.google.errorprone:error_prone_annotations:2.36.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
}

tasks.register<Copy>("archiveDebugApk") {
    dependsOn("assembleDebug")
    val version = android.defaultConfig.versionName
    from(layout.buildDirectory.dir("outputs/apk/debug")) {
        include("*.apk")
    }
    into(rootProject.layout.projectDirectory.dir("archive"))
    rename { "app-debug-v${version}.apk" }
}

// 放在 Release 构建入口检查，未配置正式密钥仍可运行 Debug 编译、测试和 Lint。
val requireReleaseSigning = tasks.register("requireReleaseSigning") {
    doLast {
        check(releaseSigningConfigured) {
            "Release 必须配置 KEYSTORE_FILE、KEYSTORE_PASSWORD、KEY_ALIAS、KEY_PASSWORD，禁止使用调试密钥"
        }
        val signingStore = KeyStore.getInstance(releaseKeystore!!, releaseStorePassword!!.toCharArray())
        check(signingStore.isKeyEntry(releaseKeyAlias)) { "正式签名别名没有对应私钥" }
        val certificate = signingStore.getCertificate(releaseKeyAlias) as? X509Certificate
            ?: error("正式签名证书不可用")
        val debugStore = KeyStore.getInstance(file("debug.keystore"), "android".toCharArray())
        check(!certificate.encoded.contentEquals(debugStore.getCertificate("androiddebugkey").encoded) &&
            !certificate.subjectX500Principal.name.contains("CN=Android Debug", ignoreCase = true)) {
            "Release 不能使用调试证书"
        }
    }
}
tasks.configureEach {
    if (name == "preReleaseBuild") dependsOn(requireReleaseSigning)
}
