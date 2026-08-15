package com.ali.ishaqiyin_admin.core

/**
 * إعدادات لوحة إدارة منبر ادكصهك.
 * القيم ثابتة في الكود كما كانت في نسخة Flutter (`lib/core/config.dart`)،
 * ولا تُخزَّن هنا أيّ أسرار: مفتاح المالك بريد معلن، ومعرّف عميل Google عامّ.
 */
object AppConfig {
    /**
     * بريد المالك المخوّل بالكتابة — نفس البريد المستعمل في لوحة نبراس.
     * يجب أن يطابق القيمة في قواعد Firestore/Storage.
     */
    const val OWNER_EMAIL = "bdalmjydtbwn812@gmail.com"

    /**
     * Web OAuth client (نوع 3) لمشروع mxqp-8d1e8 — مطلوب ليُصدِر تسجيل الدخول
     * بـ Google على Android رمز ID صالحاً لـ Firebase Auth (وهو الطريقة
     * الوحيدة للدخول، نظير نبراس).
     */
    const val GOOGLE_SERVER_CLIENT_ID =
        "502388954405-gak0aso996p37run9ohovjbinv31r3vk.apps.googleusercontent.com"

    val googleSignInConfigured: Boolean get() = GOOGLE_SERVER_CLIENT_ID.isNotEmpty()

    /**
     * خوادم ICE للمكالمات الصوتيّة في الخاص (WebRTC).
     *
     * ⚠️ قيد معروف: STUN وحده يكشف العنوان العامّ ولا يمرّر الصوت. خلف
     * NAT صارم (symmetric NAT، بعض شبكات الجوّال ومقاسم الشركات) يفشل
     * الاتّصال المباشر وتحتاج المكالمة خادم TURN لتمرير الصوت — وهو خدمة
     * مدفوعة، فإضافته قرار مالك لاحق. يُضاف هنا بالصيغة نفسها مع
     * اسم مستخدم وكلمة مرور عبر IceServer.builder(...).
     */
    val ICE_SERVERS = listOf("stun:stun.l.google.com:19302")

    /** مهلة الرنين قبل اعتبار المكالمة فائتة (نمط واتساب). */
    const val CALL_RING_TIMEOUT_MS = 45_000L

    /**
     * ⬆️ `versionCode` لآخر بناء صدر من **التطبيق العام** — تعرفه اللوحة كي
     * تملأ به حقل «أحدث إصدار منشور» بضغطة، بدل أن يُكتب من الذاكرة.
     *
     * ⚠️ يُرفَع مع كل رفع لإصدار التطبيق العام. نسيانه لا يكسر شيئاً لكنّه
     * يُعيد الخطأ الذي حدث فعلاً: بقي الرقم المنشور 10 والتطبيق 13، فلم
     * يُذكَّر أحد بالتحديث.
     */
    const val PUBLIC_APP_VERSION_CODE = 18
}

/** إعداد Firebase لمشروع التطبيق نفسه (mxqp-8d1e8) بحزمة اللوحة. */
object FirebaseConfig {
    const val API_KEY = "AIzaSyCWAHqbzhfQ-ZcjSSVCAhFFqCTgQ66SdCs"

    /** appId الخاص بحزمة com.ali.ishaqiyin_admin (مُسجَّلة في mxqp-8d1e8). */
    const val APP_ID = "1:502388954405:android:21ab2f65113166c689b6cc"
    const val SENDER_ID = "502388954405"
    const val PROJECT_ID = "mxqp-8d1e8"
    const val STORAGE_BUCKET = "mxqp-8d1e8.firebasestorage.app"
}
