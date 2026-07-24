# قواعد keep لنسخة release المقلَّصة بـ R8.
# Firebase وCompose وmedia3 تشحن قواعدها الاستهلاكية تلقائياً؛ ما يلي يغطّي
# ما لا تغطّيه: نماذج Firestore المُفكَّكة يدويّاً لا تحتاج keep، لكن خدمة
# الرسائل ومزوّد الملفات يُشار إليهما من الـmanifest فقط.
-keep class com.ali.ishaqiyin_admin.data.AdminMessagingService { *; }
-keepattributes Signature
-keepattributes *Annotation*
