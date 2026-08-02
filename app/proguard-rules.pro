# قواعد keep لنسخة release المقلَّصة بـ R8.
# Firebase وCompose وmedia3 تشحن قواعدها الاستهلاكية تلقائياً؛ ما يلي يغطّي
# ما لا تغطّيه: نماذج Firestore المُفكَّكة يدويّاً لا تحتاج keep، لكن خدمة
# الرسائل ومزوّد الملفات يُشار إليهما من الـmanifest فقط.
#
# ⚠️ الخدمة يُنشئها النظام بالانعكاس عبر المُنشئ الفارغ وحده، فيكفي إبقاؤه:
# `{ *; }` كان يمنع تقليص أعضائها الخاصّة بلا فائدة (وAGP يولّد هذه القاعدة
# أصلاً في aapt_rules.txt من الـmanifest).
-keep class com.ali.ishaqiyin_admin.data.AdminMessagingService { <init>(); }

# ملاحظة: لا -keepattributes هنا عمداً.
# • Signature يحويها proguard-android-optimize.txt أصلاً فكانت تكراراً.
# • *Annotation* كانت تُبقي RuntimeInvisible* وهي Retention.CLASS لا تُقرأ
#   انعكاسيّاً؛ وبحثٌ شامل في كود اللوحة عن toObject/@PropertyName/@DocumentId/
#   Gson/Moshi/kotlinx.serialization/Class.forName/TypeToken/@Keep = صفر نتيجة.
# إن أُضيف لاحقاً أيّ ربط انعكاسيّ (toObject مثلاً) فأعِد ما يلزم منها.

# WebRTC (io.getstream:stream-webrtc-android): مكتبة org.webrtc تستدعي أصنافها
# وتوابعها من كود أصليّ عبر JNI، فلا يراها R8 مستعمَلة ويحذفها أو يعيد
# تسميتها — فتفشل المكالمات في نسخة release وحدها بصمت تامّ.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
