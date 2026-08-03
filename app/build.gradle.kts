import java.util.Properties

plugins {
    id("com.android.application")
    // ⛔ لا تُعِد `org.jetbrains.kotlin.android`: دعم Kotlin مدمج في AGP 9
    // فأصبح الملحق يرفض التطبيق ويوقف البناء.
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

// الاسم الظاهر للمستخدم. ثابت واحد لكل أنواع البناء بلا أي لاحقة
// («تجريبي»/dev/beta/…). الحارس أسفل كتلة `android` يوقف البناء إن عاد
// أحد فأضاف لاحقة.
val canonicalAppLabel = "إدارة منبر ادكصهك"

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
        versionCode = 2006
        versionName = "1.1.3"
        manifestPlaceholders["appLabel"] = canonicalAppLabel

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
            // الاسم الظاهر يرث `canonicalAppLabel` بلا أي لاحقة.
            manifestPlaceholders["appLabel"] = canonicalAppLabel
        }
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            // رموز تصحيح المكتبات الأصليّة (WebRTC خصوصاً) كي تصل أعطال
            // Play مفهومة بدل عناوين خام.
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
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
            // واصفات protobuf النصّية (~152KB تُسلَّم لكل جهاز). المكتبة تقرأ
            // الواصفات المُصرَّفة داخل dex ولا تفتح هذه الملفات وقت التشغيل.
            "**/*.proto",
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
    // صريحة رغم أنّها تأتي غير مباشرة (تُحلّ إلى 1.5.7): فحص AGP 9 الحاسم
    // `InvalidFragmentVersionForActivityResult` لا يرى النسخة المُحلَّلة حين
    // تكون غير مباشرة فيوقف بناء النشر بخطأ كاذب.
    implementation("androidx.fragment:fragment:1.8.9")
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
    // قصّ صور صفحات الكتاب في محرر «النص المشروح».
    implementation("com.vanniktech:android-image-cropper:4.6.0")

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

// حارس دائم لاسم اللوحة الظاهر: لا لاحقة («تجريبي»/dev/beta) في أي نوع بناء
// أبداً. يعمل بعد اكتمال كل كتل الـDSL فيلتقط أي لاحقة تُضاف لاحقاً.
androidComponents {
    finalizeDsl { extension ->
        extension.buildTypes.forEach { buildType ->
            val label = buildType.manifestPlaceholders["appLabel"]
            check(label == null || label == canonicalAppLabel) {
                "اسم اللوحة الظاهر في نوع البناء «${buildType.name}» صار «$label». " +
                    "يجب أن يبقى «$canonicalAppLabel» بلا أي لاحقة في كل الأنواع."
            }
        }
    }
}

// تحذير Play «لم يتم تحميل أي رموز لتصحيح الأخطاء»: مكتبات AndroidX الأصليّة
// تأتي مجرّدةً من جدول الرموز الكامل (.symtab)، فمهمة AGP
// extractReleaseNativeSymbolTables تخرج صفر ملفات ولا يُضمَّن شيء في الحزمة
// فيبقى التحذير. المكتبات تحتفظ بجدولها الديناميكي (.dynsym) — وهو كل ما
// يملكه أحد أصلاً لها — فنضمّنه بأنفسنا بصيغة <lib>.so.sym التي تلتقطها حزمة
// AAB في BUNDLE-METADATA/com.android.tools.build.debugsymbols.
tasks.matching { it.name == "extractReleaseNativeSymbolTables" }.configureEach {
    doLast {
        val mergedLibs = layout.buildDirectory
            .dir("intermediates/merged_native_libs/release/mergeReleaseNativeLibs/out/lib")
            .get().asFile
        val symbolsOut = layout.buildDirectory
            .dir("intermediates/native_symbol_tables/release/extractReleaseNativeSymbolTables/out")
            .get().asFile
        mergedLibs.walkTopDown().filter { it.isFile && it.extension == "so" }.forEach { so ->
            val target = File(symbolsOut, "${so.parentFile.name}/${so.name}.sym")
            target.parentFile.mkdirs()
            so.copyTo(target, overwrite = true)
        }
    }
}
