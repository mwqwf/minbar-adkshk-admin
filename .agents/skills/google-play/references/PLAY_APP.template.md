# ثوابت النشر لهذا التطبيق (تقرؤها مهارة google-play)

| المفتاح | القيمة |
|---|---|
| الحزمة `applicationId` | `com.example.app` |
| المسار الافتراضي المأذون | internal / alpha / production |
| سير عمل البناء والنشر | `.github/workflows/<file>.yml` |
| الزناد | مثال: دفع `ops/release-request.json` إلى `main` / دمج يغيّر `versionCode` |
| أسماء الأسرار (لا قيم) | `PLAY_SERVICE_ACCOUNT_JSON`, `UPLOAD_KEYSTORE_B64`, ... |
| ملفات يجب تحديثها مع كل إصدار | `ui/AboutScreen.kt`, `ReleaseNotes`, `README.md` |
| حرّاس خاصّون | ... |
| سياسة الخصوصية المنشورة | URL |
| إيقاع الإصدار | مثال: أسبوعيّ (الثلاثاء) |
