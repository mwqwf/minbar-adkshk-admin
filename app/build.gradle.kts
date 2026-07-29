import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val signingPropertiesFile = rootProject.file("signing.properties")
val signingProperties = Properties()
if (signingPropertiesFile.exists()) {
    signingPropertiesFile.inputStream().use(signingProperties::load)
}

fun signingValue(property: String, environment: String): String? =
    signingProperties.getProperty(property)?.takeIf(String::isNotBlank)
        ?: providers.environmentVariable(environment).orNull?.takeIf(String::isNotBlank)

val releaseKeyAlias = signingValue("keyAlias", "MINBAR_ADMIN_SIGNING_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "MINBAR_ADMIN_SIGNING_KEY_PASSWORD")
val releaseStorePath = signingValue("storeFile", "MINBAR_ADMIN_SIGNING_STORE_FILE")
val releaseStorePassword = signingValue("storePassword", "MINBAR_ADMIN_SIGNING_STORE_PASSWORD")
val hasReleaseSigning = listOf(
    releaseKeyAlias,
    releaseKeyPassword,
    releaseStorePath,
    releaseStorePassword,
).all { !it.isNullOrBlank() } && releaseStorePath?.let(::file)?.exists() == true

android {
    // الحزمة ثابتة: عميل Google OAuth وبصمة SHA-1 مربوطان بها في mxqp-8d1e8.
    namespace = "com.ali.ishaqiyin_admin"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ali.ishaqiyin_admin"
        minSdk = 23
        targetSdk = 36
        // نسخة Flutter المثبَّتة على جهاز المشرف تحمل versionCode=2001 (بادئة
        // ABI التي يضيفها Flutter)، والتثبيت فوقها يتطلّب رقماً أعلى — وإلا
        // فُقدت جلسة الدخول بإلغاء التثبيت. الاسم يبقى كما هو.
        versionCode = 2002
        versionName = "1.0.0"
        manifestPlaceholders["appLabel"] = "إدارة منبر ادكصهك"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                storeFile = file(checkNotNull(releaseStorePath))
                storePassword = releaseStorePassword
            }
        }
    }

    buildTypes {
        debug {
            // بلا applicationIdSuffix: عميل Google OAuth مسجَّل على الحزمة نفسها،
            // وتغييرها يكسر تسجيل الدخول (وهو الطريق الوحيد للوحة).
            manifestPlaceholders["appLabel"] = "إدارة منبر ادكصهك"
        }
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // اللوحة عربيّة حرفيّاً، ومكتبات AndroidX/Compose/media3/Firebase تشحن
    // ترجماتها بـ85+ لغة تصير أقساماً لغويّة في الحزمة. الإبقاء على العربيّة
    // والافتراضيّة يقلّص الحزمة دون أيّ أثر على الواجهة.
    androidResources {
        localeFilters += listOf("ar")
    }
    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            // بيانات وصفيّة لا يقرأها شيء وقت التشغيل: مِجسّات تصحيح
            // الكوروتينات، وبصمات إصدارات SDK، وبيانات أدوات البناء.
            "META-INF/*.version",
            "META-INF/*.kotlin_module",
            "kotlin-tooling-metadata.json",
            "DebugProbesKt.bin",
        )
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    // طابور رفع الدروس دون اتصال (يستأنف تلقائياً بعد انقطاع أو إغلاق).
    implementation("androidx.work:work-runtime-ktx:2.11.2")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // معاينة الصوت (مساهمات/دروس مشبوهة) وفقاعات صوت الدردشة + فيديو الدردشة.
    val media3Version = "1.10.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-common-ktx:$media3Version")

    // صور الأعضاء وصورة المجموعة (بديل cached_network_image).
    implementation("io.coil-kt.coil3:coil-compose:3.2.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.2.0")

    val firebaseBom = platform("com.google.firebase:firebase-bom:34.16.0")
    implementation(firebaseBom)
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-functions")
    implementation("com.google.firebase:firebase-storage")
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.google.firebase:firebase-appcheck-playintegrity")
    debugImplementation("com.google.firebase:firebase-appcheck-debug")

    // تسجيل الدخول بـ Google (بديل google_sign_in) عبر Credential Manager.
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    // المكالمات الصوتيّة في الخاص (WebRTC): حزمة الأصناف org.webrtc.* — وهي
    // النسخة الحيّة الوحيدة من WebRTC لأندرويد على Maven Central بعد موت
    // org.webrtc:google-webrtc مع JCenter.
    implementation("io.getstream:stream-webrtc-android:1.3.8")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("androidx.test:core-ktx:1.7.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

tasks.matching {
    it.name == "bundleRelease" || it.name == "assembleRelease"
}.configureEach {
    doFirst {
        check(hasReleaseSigning) {
            "Missing release signing values. Use signing.properties or the MINBAR_ADMIN_SIGNING_* environment variables."
        }
    }
}
