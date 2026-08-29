/**
 * Backend المرجعي المشترك لتطبيقي منبر ادكصهك (mxqp-8d1e8).
 *
 * مبادئ الحماية:
 * - العمليات الحساسة onCall تتطلب Firebase App Check.
 * - المستمع يسجل دخولاً مجهولاً قبل إنشاء مساهمة/ملاحظة/مشاهدة.
 * - صلاحية الإدارة = بريد المالك أو role=supervisor و blocked!=true.
 * - البيانات الخاصة بالمالك لا تُكتب من أي عميل، بل عبر Admin SDK فقط.
 */
const functions = require("firebase-functions/v1");
const admin = require("firebase-admin");
const crypto = require("crypto");

admin.initializeApp();

const db = admin.firestore();
// اسم الحاوية صراحةً: admin.storage().bucket() بلا اسم يرمي خطأً عند تحميل
// الوحدة إذا خلا FIREBASE_CONFIG من storageBucket (كما في فحص النشر المحلي)،
// فيفشل اكتشاف الدوال كلها بمهلة. المشاريع الجديدة حاويتها *.firebasestorage.app.
const bucket = admin.storage().bucket(
  JSON.parse(process.env.FIREBASE_CONFIG || "{}").storageBucket
    || "mxqp-8d1e8.firebasestorage.app",
);
const TOPIC = "content";
const OWNER_EMAIL = "bdalmjydtbwn812@gmail.com";
const ADMINS_COLLECTION = "dashboard_admins";
const CODE_TTL_MS = 10 * 60 * 1000;
const CODE_REQUEST_INTERVAL_MS = 60 * 1000;
const MAX_CODE_ATTEMPTS = 5;
// حدّ محاولات مهام تنظيف التخزين: بعده تُوقَف المهمّة ويُنبَّه المالك،
// بدل إعادة محاولة يوميّة أبديّة على ملفٍّ يعصى الحذف بلا علم أحد.
const MAX_CLEANUP_ATTEMPTS = 5;
const MAX_SUBMISSION_BYTES = 100 * 1024 * 1024;
const VIEW_MILESTONES = [100, 500, 1000, 5000, 10000];

function normalizeEmail(value) {
  return String(value || "").trim().toLowerCase();
}

function cleanString(value, maxLength) {
  return String(value || "").trim().slice(0, maxLength);
}

function requireString(value, field, minLength, maxLength) {
  const result = cleanString(value, maxLength);
  if (result.length < minLength) {
    throw new functions.https.HttpsError(
      "invalid-argument",
      `الحقل ${field} غير صالح.`,
    );
  }
  return result;
}

function contextEmail(context) {
  return normalizeEmail(context.auth && context.auth.token
    ? context.auth.token.email
    : "");
}

// وضع الإنفاذ (قرار 2026-08-26): كل الطلبات الشرعية تحمل رمز App Check —
// نسخ debug عبر DebugAppCheckProviderFactory ونسخ release عبر Play
// Integrity في التطبيقين — فيُرفض أي طلب بلا رمز.
const APP_CHECK_ENFORCED = true;

function assertAppCheck(context) {
  if (!context.app) {
    if (APP_CHECK_ENFORCED) {
      throw new functions.https.HttpsError(
        "failed-precondition",
        "تعذر التحقق من سلامة التطبيق (App Check).",
      );
    }
    console.warn("App Check token missing — monitoring mode, request allowed.");
  }
}

function assertSignedIn(context) {
  if (!context.auth || !context.auth.uid) {
    throw new functions.https.HttpsError("unauthenticated", "يجب تسجيل الدخول.");
  }
  return context.auth.uid;
}

async function assertAuthorized(context) {
  assertAppCheck(context);
  assertSignedIn(context);
  const email = contextEmail(context);
  if (!email) {
    throw new functions.https.HttpsError(
      "permission-denied",
      "الحساب لا يملك بريداً موثقاً.",
    );
  }
  if (email === OWNER_EMAIL) return { email, owner: true };
  const snap = await db.collection(ADMINS_COLLECTION).doc(email).get();
  const data = snap.data() || {};
  if (snap.exists && data.role === "supervisor" && data.blocked !== true) {
    return { email, owner: false };
  }
  throw new functions.https.HttpsError("permission-denied", "الحساب غير مخول.");
}

async function assertOwner(context) {
  assertAppCheck(context);
  assertSignedIn(context);
  if (contextEmail(context) !== OWNER_EMAIL) {
    throw new functions.https.HttpsError(
      "permission-denied",
      "هذه العملية خاصة بمالك التطبيق.",
    );
  }
  return OWNER_EMAIL;
}

function hashId(value) {
  return crypto.createHash("sha256").update(String(value)).digest("hex");
}

function safeData(data) {
  const out = {};
  Object.entries(data || {}).forEach(([key, value]) => {
    if (value !== undefined && value !== null) out[key] = String(value);
  });
  return out;
}

// dryRun: فحص الحد دون استهلاكه — يُستعمل قبل كتابة رئيسية قد تفشل، ثم
// يُستهلك الحد بعد نجاحها؛ وإلا حُرم المستخدم نافذته كاملة بلا أثر مكتوب.
async function consumeRateLimit({
  uid, action, limit, windowMs, minIntervalMs, dryRun = false,
}) {
  const ref = db.collection("private_rate_limits")
    .doc(hashId(`${action}:${uid}`));
  const now = Date.now();
  await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const current = snap.data() || {};
    let windowStart = Number(current.windowStart || 0);
    let count = Number(current.count || 0);
    const lastAt = Number(current.lastAt || 0);
    if (!windowStart || now - windowStart >= windowMs) {
      windowStart = now;
      count = 0;
    }
    if (lastAt && now - lastAt < minIntervalMs) {
      throw new functions.https.HttpsError(
        "resource-exhausted",
        "طلبات متتابعة بسرعة كبيرة. حاول لاحقاً.",
      );
    }
    if (count >= limit) {
      throw new functions.https.HttpsError(
        "resource-exhausted",
        "تم بلوغ الحد المسموح مؤقتاً.",
      );
    }
    if (dryRun) return;
    tx.set(ref, {
      uid,
      action,
      windowStart,
      count: count + 1,
      lastAt: now,
      expiresAt: now + windowMs * 2,
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    });
  });
}

async function auditOwnerAction(actorEmail, action, targetId, details) {
  await db.collection("owner_audit_logs").add({
    actorEmail,
    action,
    targetId: targetId || "",
    details: details || {},
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
    createdAtMs: Date.now(),
  });
}

async function writeAdminAlert(email, title, body, data) {
  const metadata = safeData(data);
  const type = cleanString(metadata.type, 40);
  const refId = cleanString(
    metadata.refId
      || metadata.submissionId
      || metadata.lessonId
      || metadata.candidateEmail
      || metadata.id,
    180,
  );
  await db.collection("admin_alerts").add({
    email: normalizeEmail(email),
    excludeEmail: normalizeEmail(metadata.excludeEmail),
    title: cleanString(title, 120),
    body: cleanString(body, 700),
    type,
    refId,
    data: metadata,
    readBy: [],
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
    createdAtMs: Date.now(),
  });
}

async function clearAdminAlerts(type, refId) {
  const normalizedType = cleanString(type, 40);
  const normalizedRef = cleanString(refId, 180);
  if (!normalizedType || !normalizedRef) return 0;
  // استعلام مركّب على الحقول الجذرية (writeAdminAlert يكتبها جذرياً دائماً)
  // بدل قراءة المجموعة كاملة وترشيحها في الذاكرة عند كل حسم. تنبيهات النسخ
  // القديمة بلا حقول جذرية تلتقطها cleanupResolvedAdminAlerts اليدوية.
  const snap = await db.collection("admin_alerts")
    .where("type", "==", normalizedType)
    .where("refId", "==", normalizedRef)
    .get();
  const refs = snap.docs.map((doc) => doc.ref);
  for (let offset = 0; offset < refs.length; offset += 400) {
    const batch = db.batch();
    refs.slice(offset, offset + 400).forEach((ref) => batch.delete(ref));
    await batch.commit();
  }
  return refs.length;
}

async function writeUserNotification(uid, title, body, data) {
  const userId = cleanString(uid, 180);
  if (!userId) return null;
  const metadata = safeData(data);
  return db.collection("user_notifications").doc(userId).collection("items").add({
    title: cleanString(title, 120),
    body: cleanString(body, 700),
    type: cleanString(metadata.type, 40),
    refId: cleanString(metadata.refId || metadata.id, 180),
    data: metadata,
    read: false,
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
    createdAtMs: Date.now(),
  });
}

async function logPublicNotification(title, body, data) {
  await db.collection("notifications").add({
    title: cleanString(title || "منبر ادكصهك", 100),
    body: cleanString(body, 500),
    type: cleanString(data && data.type || "manual", 40),
    // معرّف الهدف: المفاتيح الصريحة أولاً ثم `id` العام (توافق خلفي).
    refId: cleanString(
      data && (data.refId || data.id || data.lessonId
        || data.subcategoryId || data.categoryId || data.bookId),
      160,
    ) || null,
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
    createdAtMs: Date.now(),
  });
}

// ⚠️ لا مفتاح `click_action` في خريطة `data`: هو مخلَّف من نسخة الفلاتر
// (كانت مكتبة flutter_local_notifications تقرؤه) وخامل تماماً على أندرويد
// الأصلي — التوجيه هناك يقرأ `type` ومعرّف الهدف من الحمولة/الـextras.
async function pushToTopic(title, body, data) {
  const t = cleanString(title || "منبر ادكصهك", 100);
  const b = cleanString(body, 500);
  await logPublicNotification(t, b, data);
  return admin.messaging().send({
    topic: TOPIC,
    notification: { title: t, body: b },
    data: safeData(data),
    android: {
      priority: "high",
      notification: { channelId: "minbar_content", sound: "default" },
    },
  });
}

async function pushToCondition(title, body, data, condition) {
  const t = cleanString(title || "منبر ادكصهك", 100);
  const b = cleanString(body, 500);
  await logPublicNotification(t, b, data);
  return admin.messaging().send({
    condition,
    notification: { title: t, body: b },
    data: safeData(data),
    android: {
      priority: "high",
      notification: { channelId: "minbar_content", sound: "default" },
    },
  });
}

async function pushToToken(token, title, body, data) {
  if (!token) return null;
  try {
    return await admin.messaging().send({
      token,
      notification: {
        title: cleanString(title, 100),
        body: cleanString(body, 500),
      },
      data: safeData(data),
      android: {
        priority: "high",
        notification: { channelId: "minbar_content", sound: "default" },
      },
    });
  } catch (error) {
    console.error("pushToToken failed", error);
    return null;
  }
}

// 💸 كاش على مستوى الوحدة (90 ثانية) لنتيجة activeAdminTokens: كل بثّ —
// وخاصّة رسائل الدردشة المتتابعة — كان يعيد قراءة admin_device_tokens
// وdevices وdashboard_admins كاملة. أقصى أثر: التقاط جهاز جديد يتأخّر
// 90 ثانية — مقبول. يُخزَّن الوعد نفسه فلا تتوازى قراءتان لنفس المفتاح.
const ADMIN_TOKENS_CACHE_MS = 90 * 1000;
const adminTokensCache = new Map(); // "all" | "owner" → {at, promise}

function activeAdminTokens(ownerOnly) {
  const key = ownerOnly ? "owner" : "all";
  const now = Date.now();
  const cached = adminTokensCache.get(key);
  if (cached && now - cached.at < ADMIN_TOKENS_CACHE_MS) return cached.promise;
  const promise = loadActiveAdminTokens(ownerOnly).catch((error) => {
    // فشل القراءة لا يُخزَّن — المحاولة التالية تقرأ من جديد.
    adminTokensCache.delete(key);
    throw error;
  });
  adminTokensCache.set(key, { at: now, promise });
  return promise;
}

// 📱 تعدّد أجهزة المشرف: رموز كل مشرف = اتحاد {حقل token في وثيقته
// القديمة} ∪ {المجموعة الفرعية devices} بلا تكرار. هويّة الجهاز (البريد
// والدور والكتم) تبقى من الوثيقة الأمّ وحدها — وثائق devices لا تحمل
// إلا token/updatedAt/model.
async function loadActiveAdminTokens(ownerOnly) {
  const [snap, devicesSnap] = await Promise.all([
    db.collection("admin_device_tokens").get(),
    db.collectionGroup("devices").get().catch((error) => {
      console.error("admin devices collectionGroup read failed", error);
      return { docs: [] };
    }),
  ]);
  if (snap.empty) return [];
  // الأمّ بلا token تبقى مصدر هويّة صالحاً لأجهزتها — الترشيح بالبريد وحده.
  const parentItems = snap.docs
    .map((doc) => {
      const value = doc.data() || {};
      return {
        ref: doc.ref,
        isParent: true,
        token: cleanString(value.token, 4096),
        email: normalizeEmail(value.email),
        uid: cleanString(value.uid || doc.id, 180),
        chatMuted: value.chatMuted === true,
      };
    })
    .filter((item) => item.email);
  // فهرس الوثائق الأمّ بالـuid لإسناد هويّة كل جهاز فرعي إليها.
  const parents = new Map(parentItems.map((item) => [item.uid, item]));
  // صفوف الإرسال: الأمّ ذات الرمز فقط (الأمّ بلا رمز هويّة لا هدف).
  const rows = parentItems.filter((item) => item.token);
  const seen = new Set(rows.map((item) => item.token));
  for (const doc of devicesSnap.docs) {
    // المسار: admin_device_tokens/{uid}/devices/{tokenHash}.
    const parentRef = doc.ref.parent.parent;
    if (!parentRef || parentRef.parent.id !== "admin_device_tokens") continue;
    const parent = parents.get(cleanString(parentRef.id, 180));
    if (!parent) continue;
    const token = cleanString((doc.data() || {}).token, 4096);
    if (!token || seen.has(token)) continue;
    seen.add(token);
    rows.push({
      ref: doc.ref,
      token,
      email: parent.email,
      uid: parent.uid,
      chatMuted: parent.chatMuted,
    });
  }
  // قراءات dashboard_admins بالتوازي لا واحدة-واحدة: القراءة التسلسلية كانت
  // تضاعف زمن كلّ بثّ/إشعار بعدد الأجهزة.
  const emails = [...new Set(
    rows.filter((item) => item.email !== OWNER_EMAIL).map((item) => item.email),
  )];
  const adminCache = new Map();
  await Promise.all(emails.map(async (email) => {
    const adminSnap = await db.collection(ADMINS_COLLECTION).doc(email).get();
    const data = adminSnap.data() || {};
    adminCache.set(
      email,
      adminSnap.exists && data.role === "supervisor" && data.blocked !== true,
    );
  }));
  return rows.filter((item) => {
    if (item.email === OWNER_EMAIL) return true;
    if (ownerOnly) return false;
    return adminCache.get(item.email) === true;
  });
}

async function pushToAdmins(title, body, data, ownerOnly = false) {
  const targets = await activeAdminTokens(ownerOnly);
  return sendToAdminTargets(targets, title, body, data);
}

async function pushToAdminsFiltered(title, body, data, options = {}) {
  const targets = (await activeAdminTokens(options.ownerOnly === true)).filter((item) => {
    if (options.targetEmail && item.email !== normalizeEmail(options.targetEmail)) return false;
    if (options.excludeEmail && item.email === normalizeEmail(options.excludeEmail)) return false;
    if (options.excludeUid && item.uid === cleanString(options.excludeUid, 180)) return false;
    if (options.respectChatMute && item.chatMuted) return false;
    return true;
  });
  return sendToAdminTargets(targets, title, body, data);
}

async function sendToAdminTargets(targets, title, body, data) {
  if (!targets.length) return { successCount: 0, failureCount: 0 };
  let successCount = 0;
  let failureCount = 0;
  for (let offset = 0; offset < targets.length; offset += 500) {
    const chunk = targets.slice(offset, offset + 500);
    const response = await admin.messaging().sendEachForMulticast({
      tokens: chunk.map((item) => item.token),
      notification: {
        title: cleanString(title, 100),
        body: cleanString(body, 500),
      },
      data: safeData(data),
      android: {
        priority: "high",
        notification: { channelId: "admin_alerts", sound: "default" },
      },
    });
    successCount += response.successCount;
    failureCount += response.failureCount;
    const removals = [];
    response.responses.forEach((item, index) => {
      const code = item.error && item.error.code || "";
      if (code.includes("registration-token-not-registered")
          || code.includes("invalid-registration-token")) {
        // صفّ الأمّ: لا تُحذف وثيقتها (هي مصدر هويّة أجهزة devices) —
        // يُمحى حقل token وحده. صفّ device: تُحذف وثيقته كاملة.
        if (chunk[index].isParent) {
          removals.push(chunk[index].ref.update({
            token: admin.firestore.FieldValue.delete(),
          }).catch(() => null));
        } else {
          removals.push(chunk[index].ref.delete());
        }
      }
    });
    await Promise.all(removals);
  }
  return { successCount, failureCount };
}

function unwrapLegacy(raw) {
  if (raw && raw.data && typeof raw.data === "object") {
    return Object.assign({}, raw.data, raw);
  }
  return raw || {};
}

// ─── المحتوى المنشور وإشعاراته ─────────────────────────────────────
exports.onLessonCreated = functions.firestore
  .document("lessons/{id}")
  .onCreate(async (snap) => {
    const d = unwrapLegacy(snap.data());
    // ♻️ الاستعادة من السلة تُعيد كتابة وثيقة الدرس كما كانت فيُطلق هذا
    // المُشغِّل ثانيةً. الوثيقة المستعادة تحمل publishNotified=true إن سبق
    // إشعار نشرها، فلا يُعاد الإشعار. بلا هذا الفحص كان درس قديم
    // يصل لكل المستمعين بوصفه «درساً جديداً» بمجرّد التراجع عن حذفه.
    if (d.publishNotified === true) return null;
    // ♻️ ووسم الاستعادة نفسه حارسٌ ثانٍ لا غنى عنه: الحارس أعلاه يعتمد على
    // حقل `publishNotified`، وهو **غائب تماماً** من كل درس أُنشئ قبل وجود
    // هذا الحقل (وهي أغلب المكتبة). فاستعادة درس قديم من السلة كانت تصل
    // كإشعار «درس جديد» لكل المستمعين — نفس الفاجعة التي يمنعها السطر
    // أعلاه للدروس الحديثة وحدها. `restoredAtMs` تكتبه restoreDeletedLesson
    // لحظة الاستعادة، والنافذة قصيرة (١٠ دقائق) كي لا يُسكِت إضافةً لاحقة
    // بالمعرّف نفسه — مطابقة لنافذة onLessonSuspicionCreated حرفياً.
    const restoredAtMs = Number(d.restoredAtMs || 0);
    if (restoredAtMs > 0 && Date.now() - restoredAtMs < 10 * 60 * 1000) {
      await snap.ref.set({ publishNotified: true }, { merge: true })
        .catch((error) => console.error("mark restored publishNotified failed", error));
      return null;
    }
    const title = cleanString(d.title || d.name, 180);
    const subId = cleanString(d.subcategoryId, 160);
    if (subId) {
      await pushToCondition(
        "درس جديد",
        title || "أُضيف درس صوتي جديد",
        {
          type: "lesson",
          id: snap.id,
          lessonId: snap.id,
          subId,
          subcategoryId: subId,
        },
        `'${TOPIC}' in topics || 'sec_${subId}' in topics`,
      );
    } else {
      await pushToTopic(
        "درس جديد",
        title || "أُضيف درس صوتي جديد",
        { type: "lesson", id: snap.id, lessonId: snap.id },
      );
    }
    // وسمٌ يمنع تكرار الإشعار إن أُعيدت كتابة الوثيقة (استعادة من السلة).
    await snap.ref.set({ publishNotified: true }, { merge: true })
      .catch((error) => console.error("mark publishNotified failed", error));
    return null;
  });

// 🔔 إعلان الإصدار الجديد لكل المستخدمين عبر FCM لحظة رفع رقمه في
// `app_config/android`. ضروريّ خصوصاً للنسخ المثبَّتة القديمة التي كان
// فحص التحديث فيها يقرأ من الكاش إلى الأبد فلا يرى الرقم الجديد أبداً —
// دفعة FCM تصلها مهما كان حال فحصها الداخلي.
// 📣 الإعلان الذاتيّ عن إصدار جديد — بلا أيّ خطوة يدويّة.
//
// **المشكلة**: كان نشرُ رقم الإصدار ورسالته يدوياً من لوحة التحكّم بعد كل
// رفعٍ إلى المتجر. خطوةٌ تُنسى — ونُسيت — فيبقى الناس على نسخة قديمة لا
// يعلمون أنّ بعدها شيئاً.
//
// **الحلّ**: النسخة الجديدة تُعلن عن نفسها. حين تعمل نسخةٌ أحدث ممّا في
// `app_config` على جهاز، تُبلّغ هذه الدالةَ برقمها وموجزها. ولا يقع ذلك إلا
// بعد أن ينشر المتجر النسخة فعلاً، فالإعلان لا يسبق التوفّر أبداً.
//
// ⚠️ **النصاب هو الأمان**: جهازٌ واحد لا يكفي. لو كفى لاستطاع متلاعبٌ أن
// يدّعي رقماً ضخماً فيُطلق إشعاراً كاذباً لكل المستخدمين، ويحبس الناس على
// شاشة «حدِّث» إلى نسخةٍ لا وجود لها. فنشترط **ثلاثة أجهزة متمايزة** تُبلّغ
// بالرقم نفسه، ونحدّ القفزة بخمسين إصداراً فوق المنشور.
const VERSION_REPORT_QUORUM = 3;
const VERSION_MAX_JUMP = 50;

// ⏳ **مهلة النضج قبل الإعلان.**
//
// درسٌ من عطل واقع (2026-08-16): بلّغت نسخةٌ قيد التجربة عن نفسها فانطلق
// إشعار «تتوفّر نسخة أحدث» إلى كل المستخدمين **قبل نشر الإصدار على المتجر**
// بساعات — فمن ضغط الإشعار وجد المتجر بلا جديد. والنصاب وحده لم يمنع ذلك:
// كل مسحٍ لبيانات التطبيق يولّد هويّة مجهولة جديدة، فبدا الجهاز الواحد
// أجهزةً عدّة.
//
// ⛔ **وقد سقطت المهلة نهائياً (2026-08-25).** كانت شرطاً بديلاً: «نصابٌ **أو**
// مهلةُ ساعة» — والمهلة لا تُثبت نشراً، إنّما تُثبت مرورَ وقت. فعاد الإنذار
// الكاذب: يجرّب المطوّر النسخة على أجهزته، فيكتمل النصاب، وتمضي ساعة،
// فينطلق الإشعار إلى كل الناس لنسخةٍ لم تُنشر.
//
// فصار الشرط **برهاناً لا انتظاراً**: جهازٌ واحد على الأقلّ نال النسخة من
// متجر Play (انظر `PLAY_INSTALLER` أدناه والحارس في `reportAppVersion`).
// لا تُعِد المهلة بديلاً عنه مهما بدا الإعلان بطيئاً.

// 🎯 **البرهان المباشر يُغني عن المهلة.**
//
// إن كان مثبِّت التطبيق على الجهاز المبلِّغ هو متجر Play، فالنسخة منشورةٌ
// بالضرورة — لا يسلّم المتجر ما لم ينشره. فالانتظار عندئذ تأخيرٌ بلا فائدة،
// والإعلان يخرج لحظة رفعها. أمّا ما ثُبِّت بـADB أو من ملف APK فلا يبرهن
// شيئاً، فيبقى خاضعاً للمهلة.
const PLAY_INSTALLER = "com.android.vending";

/// ⛔ حزمة التطبيق العام وحدها. نسخة التطوير لاحقتها `.dev`، وتبليغها
/// يعني إعلاناً عن نسخة لم تُنشر — وهو ما وقع فعلاً.
const PUBLIC_PACKAGE = "com.ali.menbaradkshk";

exports.reportAppVersion = functions.https.onCall(async (data, context) => {
  const versionCode = Math.trunc(Number(data && data.versionCode) || 0);
  if (!versionCode || versionCode <= 0) {
    throw new functions.https.HttpsError("invalid-argument", "رقم إصدار غير صالح");
  }
  // اسم الحزمة اختياريّ في النسخ القديمة، فلا نرفض غيابه — لكن وجودَه
  // مخالفاً يُرفض قطعاً.
  const pkg = cleanString(data && data.packageName, 100);
  if (pkg && pkg !== PUBLIC_PACKAGE) {
    return { ok: true, published: false, reason: "not-public-build" };
  }
  const configRef = db.collection("app_config").doc("android");
  const config = await configRef.get();
  const published = Number((config.exists && config.data().latestVersionCode) || 0);
  // لا شيء يُفعل: المنشور أحدث أو مساوٍ.
  if (versionCode <= published) return { ok: true, published: false, reason: "not-newer" };
  if (versionCode > published + VERSION_MAX_JUMP) {
    throw new functions.https.HttpsError("out-of-range", "قفزة إصدار غير معقولة");
  }

  // مُعرّف الجهاز: مُعرّف التثبيت من App Check إن وُجد، وإلا مُعرّف المستخدم
  // المجهول. كلاهما يتمايز بين الأجهزة، وهو كلّ ما يلزم للنصاب.
  const device = (context.app && context.app.appId)
    || (context.auth && context.auth.uid)
    || (context.rawRequest && context.rawRequest.ip)
    || "unknown";
  const reportRef = db.collection("app_version_reports").doc(String(versionCode));

  const outcome = await db.runTransaction(async (tx) => {
    const snap = await tx.get(reportRef);
    const previous = snap.exists ? snap.data() : null;
    const devices = new Set((previous && previous.devices) || []);
    devices.add(String(device).slice(0, 200));
    // أجهزة برهنت أنّها نالت النسخة من المتجر نفسه — تُعدّ على حدة لأنّها
    // وحدها التي تُسقط المهلة.
    const proven = new Set((previous && previous.provenDevices) || []);
    if (cleanString(data && data.installer, 100) === PLAY_INSTALLER) {
      proven.add(String(device).slice(0, 200));
    }
    // الموجز يُؤخذ من **أوّل** مُبلِّغ ويُثبَّت: لو أُخذ من الأخير لاستطاع
    // مُبلِّغٌ متأخّر أن يبدّل نصّ إشعارٍ سيصل إلى كل الناس.
    const summary = (previous && previous.summary)
      || cleanString(data && data.summary, 300);
    const versionName = (previous && previous.versionName)
      || cleanString(data && data.versionName, 40);
    // ⏱️ الختم بالمللي ثانية لا `serverTimestamp` وحده: قراءة الطابع داخل
    // المعاملة نفسها التي تكتبه تعيد رمزاً غير محلول، فيتعذّر حساب المهلة.
    const firstSeenMs = (previous && previous.firstSeenMs) || Date.now();
    tx.set(reportRef, {
      versionCode,
      versionName,
      summary,
      devices: Array.from(devices).slice(0, 50),
      count: devices.size,
      provenDevices: Array.from(proven).slice(0, 50),
      provenCount: proven.size,
      firstSeenMs,
      firstSeenAt: (previous && previous.firstSeenAt) || admin.firestore.FieldValue.serverTimestamp(),
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    }, { merge: true });
    return { count: devices.size, proven: proven.size, summary, firstSeenMs };
  });

  // ⛔ **البرهان شرطٌ لازم، لا بديلٌ عن المهلة.**
  //
  // كانت القاعدة: «نصابٌ من الأجهزة **أو** مهلةُ ساعة» — والمهلة وحدها لا
  // تُثبت شيئاً. فمن جرّب النسخة على أجهزته قبل نشرها (أو مسح بيانات التطبيق
  // مرّاتٍ فتولّدت هويّات مجهولة تبدو أجهزةً متعدّدة) اكتمل نصابه، ثم مضت
  // ساعة، فانطلق إشعار «تتوفّر نسخة أحدث» إلى **كل** المستخدمين لنسخةٍ ليست
  // على المتجر — ومن يضغطه يجد المتجر بلا جديد. هذا هو الإنذار الكاذب بعينه.
  //
  // فالشرط الآن **واحدٌ قاطع**: جهازٌ واحد على الأقلّ نال هذه النسخة من متجر
  // Play فعلاً (`installer == com.android.vending`). والمتجر لا يسلّم ما لم
  // ينشره — فالبرهان يقين لا ترجيح.
  //
  // ولا يؤخّر هذا الإعلانَ عملياً: من يحدّث تلقائياً من المتجر يحمل البرهان
  // نفسه، فيصل أوّلُ برهانٍ خلال دقائق من بدء الطرح ويُعلَن حينها. أمّا قبل
  // النشر فلا يصل برهانٌ أبداً — وهو المطلوب.
  if (outcome.proven < 1) {
    return {
      ok: true,
      published: false,
      reason: "no-store-proof",
      count: outcome.count,
    };
  }
  // والنصاب يبقى حارساً ثانياً: جهازٌ واحد قد يكون مختبِراً في مسار داخليّ
  // لم يُطرح للعامّة بعد، فننتظر أن تبلغ الأجهزة النصاب.
  if (outcome.count < VERSION_REPORT_QUORUM) {
    return { ok: true, published: false, count: outcome.count };
  }

  // بلغ النصاب: نكتب الإعداد، ويتكفّل `onAppUpdatePublished` بدفعة FCM.
  // ⚠️ `hasOnly` في قواعد Firestore يحصر المفاتيح الستّة — نلتزم بها هنا
  // أيضاً وإن كانت الكتابة من الخادم، كي يبقى شكل الوثيقة واحداً.
  await configRef.set({
    latestVersionCode: versionCode,
    minSupportedVersionCode: Number(
      (config.exists && config.data().minSupportedVersionCode) || 0,
    ),
    message: outcome.summary || "",
    storeUrl: (config.exists && config.data().storeUrl)
      || "https://play.google.com/store/apps/details?id=com.ali.menbaradkshk",
    updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    updatedBy: "auto:reportAppVersion",
  });
  return { ok: true, published: true, count: outcome.count };
});

exports.onAppUpdatePublished = functions.firestore
  .document("app_config/android")
  .onWrite(async (change) => {
    const before = change.before.exists ? change.before.data() : {};
    const after = change.after.exists ? change.after.data() : null;
    if (!after) return null;
    const latest = Number(after.latestVersionCode || 0);
    const previous = Number((before && before.latestVersionCode) || 0);
    // إشعار عند ارتفاع الرقم فقط — تعديل الرسالة أو الرابط لا يُزعج أحداً.
    if (!(latest > previous)) return null;
    const body = cleanString(after.message, 500)
      || "اضغط هنا للتحديث الآن — نسخة أحدث من منبر ادكصهك جاهزة.";
    // ⚠️ النوع `update` لا `manual`: التطبيق يفتح به **المتجر مباشرة** عند
    // النقر (شريط الإشعارات أو شاشة الإشعارات) بلا إظهار أي رابط للمستخدم.
    // كان `manual` يعني «لا وجهة» فينتهي النقر بفتح الرئيسية ولا يُحدَّث شيء.
    await pushToTopic("تتوفّر نسخة أحدث من منبر ادكصهك", body, {
      type: "update",
      route: "store",
      storeUrl: cleanString(after.storeUrl, 512) || "",
    });
    return null;
  });

exports.onSubcategoryCreated = functions.firestore
  .document("subcategories/{id}")
  .onCreate((snap) => {
    const d = unwrapLegacy(snap.data());
    return pushToTopic(
      "قسم فرعي جديد",
      cleanString(d.name, 180) || "أُضيف قسم فرعي جديد",
      { type: "subcategory", id: snap.id, subcategoryId: snap.id },
    );
  });

exports.onCategoryCreated = functions.firestore
  .document("categories/{id}")
  .onCreate((snap) => {
    const d = unwrapLegacy(snap.data());
    return pushToTopic(
      "قسم جديد",
      cleanString(d.name, 180) || "أُضيف قسم رئيسي جديد",
      { type: "category", id: snap.id, categoryId: snap.id },
    );
  });

// ⚠️ لا مُشغِّل لمجموعة books: الكتب أُزيلت من التطبيق (التوجيه يردّ
// «الكتب لم تعد ضمن التطبيق»)، فبثّ «كتاب جديد» كان إشعاراً بلا وجهة
// لو كُتبت وثيقة كتاب بأي طريق.

exports.onLessonMilestone = functions.firestore
  .document("lessons/{id}")
  .onUpdate(async (change) => {
    const before = unwrapLegacy(change.before.data());
    const after = unwrapLegacy(change.after.data());
    const previousViews = Number(before.views || 0);
    const nextViews = Number(after.views || 0);
    if (nextViews <= previousViews) return null;
    const crossed = VIEW_MILESTONES.find(
      (milestone) => previousViews < milestone && nextViews >= milestone,
    );
    if (!crossed) return null;
    const lessonTitle = cleanString(after.title || "درس", 180);
    const authorEmail = normalizeEmail(after.createdByEmail || after.addedBy);
    const body = `الدرس «${lessonTitle}» وصل إلى ${crossed} استماع.`;
    // صاحب الدرس يُخاطَب باسم «درسك» (تنبيهاً ودفعاً)، والبقية بالنص العام.
    const tasks = [
      writeAdminAlert("", "🎉 إنجاز استماع جديد", body, {
        type: "engagement",
        lessonId: change.after.id,
        refId: change.after.id,
        excludeEmail: authorEmail,
      }),
      pushToAdminsFiltered("🎉 إنجاز استماع جديد", body, {
        type: "engagement",
        lessonId: change.after.id,
        refId: change.after.id,
      }, { excludeEmail: authorEmail }),
    ];
    if (authorEmail) {
      tasks.push(writeAdminAlert(authorEmail, "🎉 إنجاز جديد لدرسك", body, {
        type: "engagement",
        lessonId: change.after.id,
        refId: change.after.id,
      }));
      tasks.push(pushToAdminsFiltered("🎉 إنجاز جديد لدرسك", body, {
        type: "engagement",
        lessonId: change.after.id,
        refId: change.after.id,
      }, { targetEmail: authorEmail }));
    }
    await Promise.all(tasks);
    return null;
  });

exports.weeklyDigest = functions.pubsub
  .schedule("0 9 * * 1")
  .timeZone("Asia/Riyadh")
  .onRun(async () => {
    const weekAgo = Date.now() - 7 * 24 * 60 * 60 * 1000;
    let newCount = 0;
    let totalViews = 0;
    try {
      // 💸 استعلامات تجميعيّة خادميّة بدل تنزيل مجموعة lessons كاملة:
      // - الجديد أسبوعيّاً: count بشرط createdAtTs (كلّ مسارات الإنشاء
      //   تكتبه Timestamp منذ البداية — فحديث الأسبوع يحمله يقيناً).
      // - إجمالي الاستماع: sum(views) + sum(data.views) − sum(views حيث
      //   توجد data.views): الوثيقة المغلّفة تحمل الحقلَين متساويين
      //   (incrementLessonView يكتبهما معاً) فيُطرح المكرَّر — النتيجة
      //   مطابقة لقراءة unwrapLegacy القديمة تماماً.
      const { AggregateField } = require("firebase-admin/firestore");
      const lessons = db.collection("lessons");
      const [countSnap, rootSum, wrappedSum, overlapSum] = await Promise.all([
        lessons
          .where("createdAtTs", ">=", admin.firestore.Timestamp.fromMillis(weekAgo))
          .count().get(),
        lessons.aggregate({ v: AggregateField.sum("views") }).get(),
        lessons.aggregate({ v: AggregateField.sum("data.views") }).get(),
        lessons.where("data.views", ">=", 0)
          .aggregate({ v: AggregateField.sum("views") }).get(),
      ]);
      newCount = Number(countSnap.data().count || 0);
      totalViews = Number(rootSum.data().v || 0)
        + Number(wrappedSum.data().v || 0)
        - Number(overlapSum.data().v || 0);
    } catch (error) {
      // الصحّة قبل التوفير: أيّ تعثّر تجميعيّ (فهرس ناقص مثلاً) يعيدنا
      // للمسار القديم كاملاً فلا يتغيّر الرقم المعلَن أبداً.
      console.error("weeklyDigest aggregates failed; falling back", error);
      const snap = await db.collection("lessons").get();
      newCount = 0;
      totalViews = 0;
      snap.forEach((doc) => {
        const d = unwrapLegacy(doc.data());
        totalViews += Number(d.views || 0);
        let createdAt = 0;
        if (d.createdAtTs && typeof d.createdAtTs.toMillis === "function") {
          createdAt = d.createdAtTs.toMillis();
        } else {
          createdAt = Date.parse(d.createdAt || "") || 0;
        }
        if (createdAt >= weekAgo) newCount += 1;
      });
    }
    const title = "📊 تقرير الأسبوع";
    const body = `دروس جديدة هذا الأسبوع: ${newCount} · إجمالي الاستماع: ${totalViews}.`;
    await writeAdminAlert("", title, body, { type: "weekly_digest" });
    await pushToAdmins(title, body, { type: "weekly_digest" });
    return null;
  });

// ─── الاستدعاءات العامة المحمية بـ App Check وحدود المعدل ───────────
exports.incrementLessonView = functions.https.onCall(async (data, context) => {
  assertAppCheck(context);
  const uid = assertSignedIn(context);
  const lessonId = requireString(data && data.lessonId, "lessonId", 1, 180);
  await consumeRateLimit({
    uid,
    action: "lesson-views-day",
    limit: 500,
    windowMs: 24 * 60 * 60 * 1000,
    minIntervalMs: 1200,
  });
  const perLessonRef = db.collection("private_rate_limits")
    .doc(hashId(`lesson-view:${uid}:${lessonId}`));
  const lessonRef = db.collection("lessons").doc(lessonId);
  const now = Date.now();
  const counted = await db.runTransaction(async (tx) => {
    const [rateSnap, lessonSnap] = await Promise.all([
      tx.get(perLessonRef),
      tx.get(lessonRef),
    ]);
    if (!lessonSnap.exists) {
      throw new functions.https.HttpsError("not-found", "الدرس غير موجود.");
    }
    const lastAt = Number((rateSnap.data() || {}).lastAt || 0);
    if (lastAt && now - lastAt < 30 * 1000) return false;
    const raw = lessonSnap.data() || {};
    const current = unwrapLegacy(raw);
    tx.set(perLessonRef, {
      uid,
      action: "lesson-view",
      lessonId,
      lastAt: now,
      expiresAt: now + 7 * 24 * 60 * 60 * 1000,
    });
    // ⚠️ التطبيق يقرأ الوثائق القديمة المغلّفة `{data:{...}}` من الخريطة
    // الداخلية حصراً — الكتابة في الجذر وحده تُجمّد عدّادها الظاهر للأبد.
    // يُكتب الموضعان معاً للوثيقة المغلّفة (نفس نمط انتهاء التمييز).
    const nextViews = Number(current.views || 0) + 1;
    const viewsUpdate = { views: nextViews };
    if (raw.data && typeof raw.data === "object") {
      viewsUpdate["data.views"] = nextViews;
    }
    tx.update(lessonRef, viewsUpdate);
    return true;
  });
  return { ok: true, counted };
});

exports.sendFeedback = functions.https.onCall(async (data, context) => {
  assertAppCheck(context);
  const uid = assertSignedIn(context);
  await consumeRateLimit({
    uid,
    action: "feedback",
    limit: 12,
    windowMs: 24 * 60 * 60 * 1000,
    minIntervalMs: 10 * 1000,
  });
  const lessonId = requireString(data && data.lessonId, "lessonId", 1, 180);
  const type = requireString(data && data.type, "type", 1, 40);
  const allowedTypes = ["benefited", "audio_issue", "other", "copyright", "abuse"];
  if (!allowedTypes.includes(type)) {
    throw new functions.https.HttpsError("invalid-argument", "نوع الملاحظة غير صالح.");
  }
  const note = cleanString(data && data.note, 500);
  const lessonSnap = await db.collection("lessons").doc(lessonId).get();
  if (!lessonSnap.exists) {
    throw new functions.https.HttpsError("not-found", "الدرس غير موجود.");
  }
  // ⚠️ لا يُخزَّن المعرّف الخام: سياسة الخصوصية المنشورة تنصّ صراحةً على أن
  // الملاحظات والبلاغات لا تُربط بهويّة المرسِل. نخزّن بصمة أحاديّة الاتجاه
  // تكفي وحدها لحذف بيانات المستخدم عند طلبه (deleteMyData يجزّئ المعرّف
  // نفسه فيطابقها) ولا تصلح للتعرّف عليه ولا للربط بين بلاغاته وحسابه.
  const ref = await db.collection("feedback").add({
    uidHash: hashId(uid),
    lessonId,
    type,
    note,
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
    createdAtMs: Date.now(),
  });
  const lesson = unwrapLegacy(lessonSnap.data());
  const lessonTitle = cleanString(lesson.title || "درس", 180);
  const authorEmail = normalizeEmail(lesson.createdByEmail || lesson.addedBy);
  const labels = {
    benefited: "أفاد مستمع بأنه انتفع بالدرس",
    audio_issue: "أبلغ مستمع عن مشكلة في الصوت",
    other: "أرسل مستمع ملاحظة على الدرس",
    copyright: "ورد بلاغ حقوق نشر على الدرس",
    abuse: "ورد بلاغ إساءة على الدرس",
  };
  const alertTitle = labels[type] || "تفاعل جديد مع درس";
  const alertBody = `${alertTitle}: «${lessonTitle}»${note ? ` — ${note}` : "."}`;
  // صاحب الدرس يُخاطَب باسم «درسك» (تنبيهاً ودفعاً)، والبقية بالنص العام —
  // بلا ازدواج إشعارات لأي أحد.
  const tasks = [
    writeAdminAlert("", alertTitle, alertBody, {
      type: "engagement",
      lessonId,
      refId: lessonId,
      feedbackId: ref.id,
      excludeEmail: authorEmail,
    }),
    pushToAdminsFiltered(alertTitle, alertBody, {
      type: "engagement",
      lessonId,
      refId: lessonId,
      feedbackId: ref.id,
    }, { excludeEmail: authorEmail }),
  ];
  if (authorEmail) {
    tasks.push(writeAdminAlert(authorEmail, "تفاعل جديد مع درسك", alertBody, {
      type: "engagement",
      lessonId,
      refId: lessonId,
      feedbackId: ref.id,
    }));
    tasks.push(pushToAdminsFiltered("تفاعل جديد مع درسك", alertBody, {
      type: "engagement",
      lessonId,
      refId: lessonId,
      feedbackId: ref.id,
    }, { targetEmail: authorEmail }));
  }
  await Promise.all(tasks);
  return { ok: true, feedbackId: ref.id };
});

exports.createSubmission = functions.https.onCall(async (data, context) => {
  assertAppCheck(context);
  const uid = assertSignedIn(context);

  const title = requireString(data && data.title, "title", 3, 120);
  const submitterName = cleanString(data && data.submitterName, 60);
  const note = cleanString(data && data.note, 500);
  const categoryId = cleanString(data && data.categoryId, 180);
  const categoryName = cleanString(data && data.categoryName, 180);
  const subcategoryId = cleanString(data && data.subcategoryId, 180);
  const subcategoryName = cleanString(data && data.subcategoryName, 180);
  const storagePath = requireString(data && data.storagePath, "storagePath", 1, 700);
  const fileName = cleanString(data && data.fileName, 255);
  const audioUrl = cleanString(data && data.audioUrl, 2500);
  const fcmToken = cleanString(data && data.fcmToken, 4096);
  const termsAcceptedAt = cleanString(data && data.termsAcceptedAt, 80);
  const parsedTermsAcceptedAt = Date.parse(termsAcceptedAt);
  const accepted = Boolean(termsAcceptedAt)
    && !Number.isNaN(parsedTermsAcceptedAt)
    && parsedTermsAcceptedAt <= Date.now() + 5 * 60 * 1000;
  // ⚠️ الإقرار **ليس شرطاً** لقبول المساهمة: المشرفون يتحقّقون من كل درس
  // بأنفسهم قبل النشر ولا يبنون قرارهم على ادّعاء المرسِل. كان هنا رفضٌ يمنع
  // الإرسال بلا إقرار فيبدو زرّ «إرسال للمراجعة» صامتاً بلا تفسير.
  // نسجّل ما أقرّ به المستخدم فعلاً ليظهر للمشرف في شاشة المراجعة.
  const rightsConfirmed = data && data.rightsConfirmed === true;
  const policyAccepted = data && data.contentPolicyAccepted === true;
  const requiredPrefix = `submissions/${uid}/`;
  if (!storagePath.startsWith(requiredPrefix) || storagePath.includes("..")) {
    throw new functions.https.HttpsError("permission-denied", "مسار الملف غير صالح.");
  }
  let metadata;
  try {
    [metadata] = await bucket.file(storagePath).getMetadata();
  } catch (error) {
    console.error("submission metadata failed", error);
    throw new functions.https.HttpsError("not-found", "ملف المساهمة غير موجود.");
  }
  const size = Number(metadata.size || 0);
  const contentType = String(metadata.contentType || "");
  if (size <= 0 || size > MAX_SUBMISSION_BYTES || !contentType.startsWith("audio/")) {
    throw new functions.https.HttpsError("invalid-argument", "ملف الصوت غير صالح.");
  }

  const pathParts = storagePath.split("/");
  const pathSubmissionId = pathParts.length >= 4 ? pathParts[2] : "";
  const requestedId = cleanString(
    data && data.submissionId || pathSubmissionId,
    180,
  );
  const ref = requestedId && /^[A-Za-z0-9_-]+$/.test(requestedId)
    ? db.collection("lesson_submissions").doc(requestedId)
    : db.collection("lesson_submissions").doc();
  if (!storagePath.includes(`/${ref.id}/`) && requestedId) {
    throw new functions.https.HttpsError(
      "invalid-argument",
      "معرف المساهمة لا يطابق مسار الملف.",
    );
  }
  const existing = await ref.get();
  if (existing.exists) {
    const existingData = existing.data() || {};
    if (existingData.uid === uid && existingData.storagePath === storagePath) {
      return { ok: true, id: ref.id, submissionId: ref.id, existing: true };
    }
    throw new functions.https.HttpsError("already-exists", "المساهمة موجودة مسبقاً.");
  }
  // «النص المشروح» الاختياري المرافق للمساهمة: نص/صور صفحات تُنشر مع
  // الدرس تلقائياً عند اعتماده. صوره تُرفع لمساحة اقتراحات النصوص بنفس
  // معرّف المساهمة، ويتحقق منها هنا كما في createTranscriptSubmission.
  const transcriptText = cleanString(data && data.transcriptText, 20000);
  const transcriptBookTitle = cleanString(data && data.transcriptBookTitle, 200);
  const transcriptSourceRef = cleanString(data && data.transcriptSourceRef, 300);
  const transcriptImagePaths = await validateTranscriptImages(
    data && data.transcriptImagePaths,
    `transcript_submissions/${uid}/${ref.id}/`,
  );
  // فحص الحد قبل الكتابة دون استهلاك، والاستهلاك بعد نجاحها — كي لا يخسر
  // المساهم محاولة من نافذته إن فشلت كتابة المساهمة نفسها.
  const submissionRateLimit = {
    uid,
    action: "submission",
    limit: 5,
    windowMs: 24 * 60 * 60 * 1000,
    minIntervalMs: 60 * 1000,
  };
  await consumeRateLimit({ ...submissionRateLimit, dryRun: true });
  await ref.set({
    uid,
    submitterName,
    title,
    categoryId,
    categoryName,
    subcategoryId,
    subcategoryName,
    note,
    audioUrl,
    storagePath,
    fileName: fileName || storagePath.split("/").pop(),
    fileSize: size,
    contentType,
    fcmToken,
    status: "pending",
    rejectReason: "",
    // ما أقرّ به المرسِل فعلاً (لا قيمة ثابتة) — يظهر للمشرف عند المراجعة.
    rightsConfirmed,
    termsAccepted: policyAccepted,
    termsAcceptedAt,
    termsAcceptedAtTs: admin.firestore.FieldValue.serverTimestamp(),
    contentPolicyVersion: cleanString(data && data.contentPolicyVersion, 40) || "2026-07",
    transcriptText,
    transcriptBookTitle,
    transcriptSourceRef,
    transcriptImagePaths,
    createdAt: new Date().toISOString(),
    createdAtTs: admin.firestore.FieldValue.serverTimestamp(),
    createdAtMs: Date.now(),
  });
  await consumeRateLimit(submissionRateLimit).catch((error) => {
    console.error("submission rate limit consume failed", ref.id, error);
  });
  return { ok: true, id: ref.id, submissionId: ref.id };
});

// ينشئ الدرس من هوية Firebase الموثقة، ولا يثق بأي createdBy يرسله العميل.
exports.createLesson = functions.https.onCall(async (data, context) => {
  const actor = await assertAuthorized(context);
  const input = data && data.lesson && typeof data.lesson === "object"
    ? data.lesson
    : data || {};
  const title = requireString(input.title || input.name, "title", 2, 180);
  const audioUrl = requireString(input.audioUrl || input.url, "audioUrl", 8, 2500);
  try {
    const parsed = new URL(audioUrl);
    if (!["https:", "http:"].includes(parsed.protocol)) throw new Error("protocol");
  } catch (_) {
    throw new functions.https.HttpsError("invalid-argument", "رابط الصوت غير صالح.");
  }
  const storagePath = cleanString(
    input.storagePath || input.audioStoragePath,
    700,
  );
  if (storagePath.startsWith("submissions/")) {
    throw new functions.https.HttpsError(
      "invalid-argument",
      "لا يجوز نشر درس مباشر من مجلد المساهمات الخاص.",
    );
  }
  const serverNowIso = new Date().toISOString();
  // زمن الإضافة: طابور الرفع دون اتصال يختم لحظة ضغط المشرف «رفع» ويرسلها
  // هنا، فيبقى ترتيب الدروس في التطبيق العام مطابقاً لترتيب إضافتها لا
  // لترتيب اكتمال رفعها. يُقبل فقط من حساب مخوَّل، وبتاريخ صالح غير
  // مستقبليّ ولا أقدم من 30 يوماً؛ وإلّا فزمن الخادم.
  const requestedCreatedAt = cleanString(input.createdAt, 40);
  const requestedMs = requestedCreatedAt ? Date.parse(requestedCreatedAt) : NaN;
  const MAX_BACKDATE_MS = 30 * 24 * 60 * 60 * 1000;
  const acceptableCreatedAt = !Number.isNaN(requestedMs) &&
    requestedMs <= Date.now() + 60 * 1000 &&
    requestedMs >= Date.now() - MAX_BACKDATE_MS;
  const nowIso = acceptableCreatedAt
    ? new Date(requestedMs).toISOString()
    : serverNowIso;
  const lessonData = {
    title,
    normalizedTitle: title.toLocaleLowerCase("ar").replace(/\s+/g, " ").trim(),
    audioUrl,
    categoryId: cleanString(input.categoryId, 180),
    categoryName: cleanString(input.categoryName, 180),
    subcategoryId: cleanString(input.subcategoryId, 180),
    subcategoryName: cleanString(input.subcategoryName, 180),
    description: cleanString(input.description, 3000),
    // اسم المتحدّث بمفتاحين: التطبيق يقرأ speaker/sheikh/reader ولا يعرف
    // sheikhName، فيُكتب المرآة حتى يظهر سطر «المتحدّث» في التطبيق كما
    // يظهر في الويب، ويطابقه بحث التطبيق.
    sheikhName: cleanString(input.sheikhName, 180),
    speaker: cleanString(input.sheikhName, 180),
    // مدّة التمييز: بانقضائها يسقط الدرس من «مختارات المنبر». غياب المدّة
    // مع featured=true = تمييز دائم.
    // ⚠️ درس بقي في طابور الرفع حتى انقضت مدّة تمييزه يجب أن يصل **غير
    // مميّز**؛ إسقاط المدّة وحدها كان يحوّله إلى مميّز إلى الأبد، وهو عكس
    // المقصود تماماً (وexpireFeaturedLessons لا تلمس ما لا مدّة له).
    ...(function () {
      if (input.featured !== true) return { featured: false };
      const until = cleanString(input.featuredUntil, 40);
      if (!until) return { featured: true }; // تمييز دائم مقصود.
      const ms = Date.parse(until);
      if (Number.isNaN(ms)) return { featured: true };
      if (ms <= Date.now()) return { featured: false }; // انقضت قبل الوصول.
      return { featured: true, featuredUntil: new Date(ms).toISOString() };
    })(),
    views: 0,
    createdAt: nowIso,
    createdAtTs: admin.firestore.FieldValue.serverTimestamp(),
    createdByUid: context.auth.uid,
    createdByEmail: actor.email,
    addedBy: actor.email,
    updatedByUid: context.auth.uid,
    updatedByEmail: actor.email,
    updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    publishNotified: false,
  };
  if (storagePath) {
    lessonData.storagePath = storagePath;
    lessonData.audioStoragePath = storagePath;
  }
  if (Number.isFinite(Number(input.duration))) {
    lessonData.duration = Number(input.duration);
  }
  if (Number.isFinite(Number(input.durationSeconds))) {
    lessonData.durationSeconds = Number(input.durationSeconds);
  }
  if (Number.isFinite(Number(input.order))) lessonData.order = Number(input.order);
  const optionalStrings = [
    "source", "sourceUrl", "bookId", "imageUrl", "transcript",
  ];
  optionalStrings.forEach((key) => {
    const value = cleanString(input[key], key === "transcript" ? 10000 : 2500);
    if (value) lessonData[key] = value;
  });
  if (Array.isArray(input.tags)) {
    lessonData.tags = input.tags
      .slice(0, 20)
      .map((item) => cleanString(item, 60))
      .filter(Boolean);
  }
  // منع التكرار: طابور الرفع يعيد المحاولة إن ضاع الردّ بعد نجاح الكتابة
  // (حالة معتادة على شبكة ضعيفة)، فبلا مفتاح ثابت يُنشأ درسان متطابقان.
  // المفتاح معرّف العنصر في الطابور، ومعرّف الوثيقة يُشتقّ منه حتميّاً.
  const clientKey = cleanString(input.clientKey, 120);
  const lessonRef = clientKey
    ? db.collection("lessons").doc(
      crypto.createHash("sha1")
        .update(`${context.auth.uid}:${clientKey}`)
        .digest("hex")
        .slice(0, 20),
    )
    : db.collection("lessons").doc();
  if (clientKey) {
    const existing = await lessonRef.get();
    if (existing.exists) return { ok: true, id: lessonRef.id, duplicate: true };
  }
  await lessonRef.set(lessonData);
  await auditOwnerAction(actor.email, "create_lesson", lessonRef.id, { title });
  return { ok: true, id: lessonRef.id };
});

async function anonymizePublishedLessons(uid) {
  const snap = await db.collection("lessons")
    .where("submittedByUid", "==", uid)
    .get();
  for (let offset = 0; offset < snap.docs.length; offset += 400) {
    const batch = db.batch();
    snap.docs.slice(offset, offset + 400).forEach((doc) => {
      batch.update(doc.ref, {
        submittedByUid: admin.firestore.FieldValue.delete(),
        submittedBy: admin.firestore.FieldValue.delete(),
        contributorDeletedAt: admin.firestore.FieldValue.serverTimestamp(),
      });
    });
    await batch.commit();
  }
  return snap.size;
}

async function deleteQuery(query, beforeDelete) {
  const snap = await query.get();
  for (const doc of snap.docs) {
    if (beforeDelete) await beforeDelete(doc.data() || {});
  }
  for (let offset = 0; offset < snap.docs.length; offset += 400) {
    const batch = db.batch();
    snap.docs.slice(offset, offset + 400).forEach((doc) => batch.delete(doc.ref));
    await batch.commit();
  }
  return snap.size;
}

exports.deleteMyData = functions.runWith({ timeoutSeconds: 120, memory: "512MB" })
  .https.onCall(async (_data, context) => {
    assertAppCheck(context);
    const uid = assertSignedIn(context);
    await consumeRateLimit({
      uid,
      action: "delete-my-data",
      limit: 2,
      windowMs: 24 * 60 * 60 * 1000,
      minIntervalMs: 60 * 1000,
    });
    const submissions = await deleteQuery(
      db.collection("lesson_submissions").where("uid", "==", uid),
      async (value) => {
        if (value.storagePath) await deleteFileIfExists(value.storagePath);
        const transcriptImages = Array.isArray(value.transcriptImagePaths)
          ? value.transcriptImagePaths
          : [];
        for (const path of transcriptImages) {
          await deleteFileIfExists(path).catch(() => {});
        }
      },
    );
    const transcriptSubmissions = await deleteQuery(
      db.collection("transcript_submissions").where("uid", "==", uid),
      async (value) => {
        const paths = Array.isArray(value.imagePaths) ? value.imagePaths : [];
        for (const path of paths) await deleteFileIfExists(path).catch(() => {});
      },
    );
    // 📮 محادثات التواصل مع المالك: تُحذف كاملةً (رسائلها ومرفقاتها وطلب
    // الإشراف المرتبط بها) لأنها — بخلاف البلاغات — مربوطة بالهويّة صراحةً.
    const supportThreadsSnap = await db.collection(SUPPORT_THREADS_COLLECTION)
      .where("uid", "==", uid)
      .get();
    for (const doc of supportThreadsSnap.docs) {
      await deleteSupportThreadDeep(uid, doc.ref);
    }
    await db.collection(SUPPORT_BLOCKS_COLLECTION).doc(uid).delete().catch(() => {});
    // إخفاء هوية المساهم في النصوص المعتمدة المنشورة (كما في الدروس).
    const transcriptsSnap = await db.collection("lesson_transcripts")
      .where("contributorUid", "==", uid)
      .get();
    for (let offset = 0; offset < transcriptsSnap.docs.length; offset += 400) {
      const batch = db.batch();
      transcriptsSnap.docs.slice(offset, offset + 400).forEach((doc) => {
        batch.update(doc.ref, {
          contributorUid: admin.firestore.FieldValue.delete(),
          contributorName: admin.firestore.FieldValue.delete(),
          contributorDeletedAt: admin.firestore.FieldValue.serverTimestamp(),
        });
      });
      await batch.commit();
    }
    // البلاغات تُخزَّن ببصمة مجزّأة لا بالمعرّف الخام (سياسة الخصوصية)؛
    // والاستعلام بالمعرّف الخام يبقى لحذف ما كُتب قبل هذا التغيير.
    const feedback = (await deleteQuery(
      db.collection("feedback").where("uidHash", "==", hashId(uid)),
    )) + (await deleteQuery(
      db.collection("feedback").where("uid", "==", uid),
    ));
    const anonymizedLessons = await anonymizePublishedLessons(uid);
    // مع المجموعة الفرعية devices (تعدّد الأجهزة) — الوثيقة الأمّ وحدها
    // كانت تُحذف فتبقى وثائق الأجهزة يتيمة.
    await deleteQuery(
      db.collection("admin_device_tokens").doc(uid).collection("devices"),
    ).catch(() => {});
    await db.collection("admin_device_tokens").doc(uid).delete().catch(() => {});
    const rates = await deleteQuery(
      db.collection("private_rate_limits").where("uid", "==", uid),
    );
    // صندوق إشعارات المستخدم يحمل عناوين مساهماته؛ والقواعد تسمح بحذفه
    // لصاحب الـuid وحده، فبعد زوال حسابه لا يبقى من يحذفه — يُمسح هنا.
    const notificationsDoc = db.collection("user_notifications").doc(uid);
    const notifications = await deleteQuery(notificationsDoc.collection("items"));
    await notificationsDoc.delete().catch(() => {});
    await admin.auth().deleteUser(uid).catch((error) => {
      if (error.code !== "auth/user-not-found") throw error;
    });
    return {
      ok: true,
      deleted: {
        submissions,
        transcriptSubmissions,
        feedback,
        rates,
        notifications,
        supportThreads: supportThreadsSnap.size,
      },
      anonymizedLessons,
      anonymizedTranscripts: transcriptsSnap.size,
    };
  });

// ─── أدوات الملفات والمساهمات الإدارية الذرية ───────────────────────
function storagePathFromUrl(pathOrUrl) {
  const value = String(pathOrUrl || "").trim();
  if (!value) return "";
  if (!/^https?:/i.test(value)) return value.replace(/^\/+/, "");
  try {
    const url = new URL(value);
    const marker = "/o/";
    const index = url.pathname.indexOf(marker);
    if (index >= 0) return decodeURIComponent(url.pathname.slice(index + marker.length));
  } catch (_) {
    return "";
  }
  return "";
}

async function deleteFileIfExists(pathOrUrl) {
  const path = storagePathFromUrl(pathOrUrl);
  if (!path) return false;
  try {
    await bucket.file(path).delete({ ignoreNotFound: true });
    return true;
  } catch (error) {
    if (error.code === 404) return true;
    console.error("storage delete failed", path, error);
    throw error;
  }
}

function safeFileName(value) {
  const original = cleanString(value, 255) || "lesson.mp3";
  return original.replace(/[^\p{L}\p{N}._-]+/gu, "_");
}

async function copySubmissionAudio(sourcePath, lessonId, fileName) {
  const source = bucket.file(sourcePath);
  const [metadata] = await source.getMetadata();
  const destinationPath = `lessons/${lessonId}/${safeFileName(fileName)}`;
  const destination = bucket.file(destinationPath);
  await source.copy(destination);
  const token = crypto.randomUUID();
  await destination.setMetadata({
    contentType: metadata.contentType || "audio/mpeg",
    // مسار الوجهة مختوم بمعرّف الدرس واسم الملف ولا يُستبدل محتواه أبداً —
    // فرأس سنة الخالد (كرفع اللوحة سواء) صحيح، وساعةٌ واحدة كانت سهواً
    // يجعل كل استماع يعيد التنزيل من الأصل.
    cacheControl: "public, max-age=31536000, immutable",
    metadata: {
      firebaseStorageDownloadTokens: token,
      sourceSubmissionPath: sourcePath,
    },
  });
  const audioUrl = `https://firebasestorage.googleapis.com/v0/b/${encodeURIComponent(bucket.name)}`
    + `/o/${encodeURIComponent(destinationPath)}?alt=media&token=${token}`;
  return { destinationPath, audioUrl };
}

exports.approveSubmission = functions.runWith({ timeoutSeconds: 120, memory: "512MB" })
  .https.onCall(async (data, context) => {
    const actor = await assertAuthorized(context);
    const submissionId = requireString(
      data && data.submissionId,
      "submissionId",
      1,
      180,
    );
    const submissionRef = db.collection("lesson_submissions").doc(submissionId);
    const firstSnap = await submissionRef.get();
    if (!firstSnap.exists) {
      throw new functions.https.HttpsError("not-found", "المساهمة غير موجودة.");
    }
    const original = firstSnap.data() || {};
    if (original.status !== "pending") {
      if (["approved", "approved_edited"].includes(original.status)
          && original.publishedLessonId) {
        return {
          ok: true,
          id: original.publishedLessonId,
          lessonId: original.publishedLessonId,
          storagePath: cleanString(original.publishedStoragePath, 700),
          alreadyApproved: true,
        };
      }
      throw new functions.https.HttpsError(
        "failed-precondition",
        "سبق حسم هذه المساهمة.",
      );
    }
    const title = requireString(
      data && data.title || original.title,
      "title",
      3,
      120,
    );
    const categoryId = cleanString(data && data.categoryId || original.categoryId, 180);
    const categoryName = cleanString(data && data.categoryName || original.categoryName, 180);
    const subcategoryId = cleanString(
      data && data.subcategoryId || original.subcategoryId,
      180,
    );
    const subcategoryName = cleanString(
      data && data.subcategoryName || original.subcategoryName,
      180,
    );
    const sourcePath = requireString(original.storagePath, "storagePath", 1, 700);
    const lessonRef = db.collection("lessons").doc();
    let published;
    try {
      published = await copySubmissionAudio(
        sourcePath,
        lessonRef.id,
        original.fileName,
      );
      const edited = title !== cleanString(original.title, 120)
        || categoryId !== cleanString(original.categoryId, 180)
        || subcategoryId !== cleanString(original.subcategoryId, 180);
      const status = edited ? "approved_edited" : "approved";
      await db.runTransaction(async (tx) => {
        const currentSnap = await tx.get(submissionRef);
        if (!currentSnap.exists || currentSnap.data().status !== "pending") {
          throw new functions.https.HttpsError(
            "aborted",
            "حُسمت المساهمة من مشرف آخر.",
          );
        }
        tx.create(lessonRef, {
          title,
          normalizedTitle: title.toLocaleLowerCase("ar").replace(/\s+/g, " ").trim(),
          categoryId,
          categoryName,
          subcategoryId,
          subcategoryName,
          audioUrl: published.audioUrl,
          audioStoragePath: published.destinationPath,
          storagePath: published.destinationPath,
          views: 0,
          createdAt: new Date().toISOString(),
          createdAtTs: admin.firestore.FieldValue.serverTimestamp(),
          addedBy: actor.email,
          createdByUid: context.auth.uid,
          createdByEmail: actor.email,
          updatedByUid: context.auth.uid,
          updatedByEmail: actor.email,
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
          sourceSubmissionId: submissionId,
          submittedByUid: cleanString(original.uid, 180),
          submittedBy: cleanString(original.submitterName, 60),
        });
        tx.update(submissionRef, {
          status,
          publishedLessonId: lessonRef.id,
          publishedTitle: title,
          publishedCategoryName: categoryName,
          publishedSubcategoryName: subcategoryName,
          publishedStoragePath: published.destinationPath,
          decidedBy: actor.email,
          decidedAt: new Date().toISOString(),
          decidedAtTs: admin.firestore.FieldValue.serverTimestamp(),
          cleanupPending: false,
        });
      });
    } catch (error) {
      if (published && published.destinationPath) {
        await deleteFileIfExists(published.destinationPath).catch(() => {});
      }
      throw error;
    }
    try {
      await deleteFileIfExists(sourcePath);
    } catch (_) {
      await submissionRef.update({ cleanupPending: true }).catch(() => {});
    }
    // «النص المشروح» المرافق (إن أُرفق): يُنشر مع الدرس فور اعتماده.
    // فشله لا يُسقط نشر الدرس نفسه — يُسجَّل ويستطيع المشرف إضافته يدوياً.
    const transcriptText = cleanString(original.transcriptText, 20000);
    const transcriptImages = Array.isArray(original.transcriptImagePaths)
      ? original.transcriptImagePaths
      : [];
    if (transcriptText.length >= 10 || transcriptImages.length) {
      try {
        const publishedImages = [];
        for (let index = 0; index < transcriptImages.length; index += 1) {
          publishedImages.push(
            await publishTranscriptImage(transcriptImages[index], lessonRef.id, index),
          );
        }
        await db.collection("lesson_transcripts").doc(lessonRef.id).set({
          lessonId: lessonRef.id,
          lessonTitle: title,
          text: transcriptText,
          bookTitle: cleanString(original.transcriptBookTitle, 200),
          sourceRef: cleanString(original.transcriptSourceRef, 300),
          images: publishedImages,
          contributorUid: cleanString(original.uid, 180),
          contributorName: cleanString(original.submitterName, 60),
          sourceSubmissionId: submissionId,
          updatedBy: actor.email,
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
          updatedAtMs: Date.now(),
          createdAt: new Date().toISOString(),
        });
        await syncTranscriptIndex(lessonRef.id, transcriptText);
        for (const path of transcriptImages) {
          await deleteFileIfExists(path).catch(() => {});
        }
      } catch (error) {
        console.error("transcript publish with lesson failed", submissionId, error);
      }
    }
    await auditOwnerAction(
      actor.email,
      "approve_submission",
      submissionId,
      { lessonId: lessonRef.id },
    );
    return {
      ok: true,
      id: lessonRef.id,
      lessonId: lessonRef.id,
      audioUrl: published.audioUrl,
      storagePath: published.destinationPath,
    };
  });

exports.rejectSubmission = functions.https.onCall(async (data, context) => {
  const actor = await assertAuthorized(context);
  const submissionId = requireString(
    data && data.submissionId,
    "submissionId",
    1,
    180,
  );
  const reason = requireString(data && data.reason, "reason", 2, 300);
  const ref = db.collection("lesson_submissions").doc(submissionId);
  let storagePath = "";
  let transcriptImages = [];
  await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!snap.exists) {
      throw new functions.https.HttpsError("not-found", "المساهمة غير موجودة.");
    }
    const value = snap.data() || {};
    if (value.status !== "pending") {
      if (value.status === "rejected") return;
      throw new functions.https.HttpsError(
        "failed-precondition",
        "سبق حسم هذه المساهمة.",
      );
    }
    storagePath = cleanString(value.storagePath, 700);
    transcriptImages = Array.isArray(value.transcriptImagePaths)
      ? value.transcriptImagePaths
      : [];
    tx.update(ref, {
      status: "rejected",
      rejectReason: reason,
      decidedBy: actor.email,
      decidedAt: new Date().toISOString(),
      decidedAtTs: admin.firestore.FieldValue.serverTimestamp(),
      cleanupPending: Boolean(storagePath) || transcriptImages.length > 0,
    });
  });
  if (storagePath || transcriptImages.length) {
    try {
      if (storagePath) await deleteFileIfExists(storagePath);
      for (const path of transcriptImages) await deleteFileIfExists(path);
      await ref.update({ cleanupPending: false });
    } catch (_) {
      // تبقى cleanupPending=true لإعادة المحاولة الآمنة لاحقاً.
    }
  }
  await auditOwnerAction(actor.email, "reject_submission", submissionId, { reason });
  return { ok: true, id: submissionId };
});

async function deleteSubmissionHandler(data, context) {
  const actor = await assertAuthorized(context);
  const submissionId = requireString(
    data && data.submissionId,
    "submissionId",
    1,
    180,
  );
  const ref = db.collection("lesson_submissions").doc(submissionId);
  const snap = await ref.get();
  if (!snap.exists) return { ok: true, alreadyDeleted: true };
  const value = snap.data() || {};
  if (value.storagePath) await deleteFileIfExists(value.storagePath);
  const transcriptImages = Array.isArray(value.transcriptImagePaths)
    ? value.transcriptImagePaths
    : [];
  for (const path of transcriptImages) await deleteFileIfExists(path).catch(() => {});
  await ref.delete();
  await auditOwnerAction(actor.email, "delete_submission", submissionId, {});
  return { ok: true };
}

exports.deleteSubmission = functions.https.onCall(deleteSubmissionHandler);
// اسم بديل تستدعيه نسخ لوحة الإدارة المثبتة قبل توحيد الاسم — أبقه منشوراً.
exports.deleteSubmissionRecord = functions.https.onCall(deleteSubmissionHandler);

exports.deleteMySubmission = functions.https.onCall(async (data, context) => {
  assertAppCheck(context);
  const uid = assertSignedIn(context);
  const submissionId = requireString(
    data && data.submissionId,
    "submissionId",
    1,
    180,
  );
  const ref = db.collection("lesson_submissions").doc(submissionId);
  const snap = await ref.get();
  if (!snap.exists) return { ok: true, alreadyDeleted: true };
  const value = snap.data() || {};
  if (value.uid !== uid || value.status !== "pending") {
    throw new functions.https.HttpsError("permission-denied", "لا يمكن حذف هذا الطلب.");
  }
  if (value.storagePath) await deleteFileIfExists(value.storagePath);
  const transcriptImages = Array.isArray(value.transcriptImagePaths)
    ? value.transcriptImagePaths
    : [];
  for (const path of transcriptImages) await deleteFileIfExists(path).catch(() => {});
  await ref.delete();
  return { ok: true };
});

// ─── النص المشروح: المتن/المقطع الذي تشرحه الصوتية ─────────────────
// وثيقة واحدة لكل درس في lesson_transcripts (معرّفها = معرّف الدرس) تُجلب
// عند فتح المشغّل فقط، فلا تُثقل مزامنة مجموعة lessons الكاملة إطلاقاً.
// اقتراحات المستمعين تمرّ عبر transcript_submissions بنفس دورة «شارك درساً».
const TRANSCRIPTS_COLLECTION = "lesson_transcripts";
const TRANSCRIPT_SUBMISSIONS_COLLECTION = "transcript_submissions";
const MAX_TRANSCRIPT_CHARS = 20000;
const MAX_TRANSCRIPT_IMAGES = 4;
const MAX_TRANSCRIPT_IMAGE_BYTES = 10 * 1024 * 1024;
const MIN_TRANSCRIPT_TEXT_CHARS = 10;

// ─── فهرس البحث داخل المتون ────────────────────────────────────────
// وثيقة خفيفة لكل درس (transcript_index/{lessonId}) لا تحمل إلا كلماته.
// فُصلت عن lesson_transcripts عمداً: استعلام array-contains يُنزّل الوثيقة
// المطابقة **كاملةً**، ووثيقة المتن تبلغ 20 ألف حرف — فبحثٌ واحد كان
// سيكلّف المستمع مئات الكيلوبايتات. وثيقة الفهرس بضعة كيلوبايتات لا غير.
//
// ⚠️ قواعد التطبيع أدناه نسخة حرفية من `normalizeArabic` في
// app/util/TextUtils.kt، واختلاف حرفٍ واحد يُبطل التطابق بين الطرفين.
const TRANSCRIPT_INDEX_COLLECTION = "transcript_index";
const MAX_INDEX_KEYWORDS = 400;
const MIN_KEYWORD_CHARS = 3;
// كل ما ليس حرفاً عربياً أو لاتينياً أو رقماً فاصلٌ بين الكلمات:
// المدى الأول همزة→غين والثاني فاء→ياء (وبينهما التطويل، وقد حُذف).
const KEYWORD_SEPARATORS = /[^ء-غف-يa-z0-9]+/;
// أدوات التعريف الملتصقة: يُفهرس الجذع وحده كي يجد من كتب «تيمم» درساً
// وردت فيه «بالتيمم»، ومن كتب «التيمم» درساً ورد فيه «تيمم» — والعربية
// تلصق الأداة بالكلمة فالمطابقة الحرفية وحدها كانت ستُخفي أكثر النتائج.
const KEYWORD_PREFIXES = ["وال", "فال", "بال", "كال", "لل", "ال"];

function normalizeArabicText(value) {
  return String(value || "").trim().toLowerCase().normalize("NFKC")
    .replace(/[ً-ٰٟۖ-ۭ]/g, "")
    .replace(/ـ/g, "")
    .replace(/[أإآٱ]/g, "ا")
    .replace(/ى/g, "ي")
    .replace(/ة/g, "ه")
    .replace(/ؤ/g, "و")
    .replace(/ئ/g, "ي");
}

/** جذع الكلمة: تُقشَّر الأداة ما دام الباقي كلمةً معتبرة (فـ«الله» تبقى). */
function keywordStem(word) {
  for (const prefix of KEYWORD_PREFIXES) {
    if (word.startsWith(prefix)
        && word.length - prefix.length >= MIN_KEYWORD_CHARS) {
      return word.slice(prefix.length);
    }
  }
  return word;
}

/**
 * كلمات فهرس المتن: فريدة، الأشيع أوّلاً، وبحدّ 400 كلمة.
 * الحدّ مقصود لأن الوثيقة تُنزَّل كاملة مع كل نتيجة بحث. والقصّ بالتواتر
 * لا بالترتيب: أشيع كلمة في متنٍ هي موضوعه (من يبحث عن «التيمم» يريد
 * درساً تكرّرت فيه)، فيُحفظ ما يُبحث عنه ويسقط ما ورد عرَضاً مرّة.
 */
function transcriptIndexKeywords(text) {
  const counts = new Map();
  for (const raw of normalizeArabicText(text).split(KEYWORD_SEPARATORS)) {
    if (raw.length < MIN_KEYWORD_CHARS) continue;
    const stem = keywordStem(raw);
    counts.set(stem, (counts.get(stem) || 0) + 1);
  }
  return [...counts.entries()]
    .sort((a, b) => b[1] - a[1])
    .slice(0, MAX_INDEX_KEYWORDS)
    .map((entry) => entry[0]);
}

/**
 * مزامنة فهرس درس بعد كتابة متنه.
 * لا ترمي أبداً: الفهرس تحسينٌ للبحث لا شرطٌ لاعتماد النص، وفشل كتابته
 * يجب ألّا يُسقط اعتماداً أنجزه المشرف ولا نشر درسٍ مرفقٍ به.
 */
async function syncTranscriptIndex(lessonId, text) {
  try {
    const ref = db.collection(TRANSCRIPT_INDEX_COLLECTION).doc(lessonId);
    const keywords = transcriptIndexKeywords(text);
    // متنٌ بلا كلمات (صور صفحات فقط مثلاً) لا يُفهرس، ووثيقته السابقة
    // تُمسح كي لا يبقى فهرسٌ يشير إلى نصٍّ لم يعد موجوداً.
    if (!keywords.length) {
      await ref.delete();
      return;
    }
    await ref.set({
      lessonId,
      keywords,
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    });
  } catch (error) {
    console.error("transcript index sync failed", lessonId, error);
  }
}

async function validateTranscriptImages(paths, requiredPrefix) {
  const list = Array.isArray(paths) ? paths.slice(0, MAX_TRANSCRIPT_IMAGES) : [];
  const cleaned = [];
  for (const raw of list) {
    const path = cleanString(raw, 700);
    if (!path || !path.startsWith(requiredPrefix) || path.includes("..")) {
      throw new functions.https.HttpsError("permission-denied", "مسار صورة غير صالح.");
    }
    let metadata;
    try {
      [metadata] = await bucket.file(path).getMetadata();
    } catch (_) {
      throw new functions.https.HttpsError("not-found", "صورة مرفقة غير موجودة.");
    }
    const size = Number(metadata.size || 0);
    const contentType = String(metadata.contentType || "");
    if (size <= 0 || size > MAX_TRANSCRIPT_IMAGE_BYTES
        || !contentType.startsWith("image/")) {
      throw new functions.https.HttpsError("invalid-argument", "صورة مرفقة غير صالحة.");
    }
    if (!cleaned.includes(path)) cleaned.push(path);
  }
  return cleaned;
}

function buildTokenUrl(path, token) {
  return `https://firebasestorage.googleapis.com/v0/b/${encodeURIComponent(bucket.name)}`
    + `/o/${encodeURIComponent(path)}?alt=media&token=${token}`;
}

async function ensureImageDownloadUrl(path) {
  const file = bucket.file(path);
  const [metadata] = await file.getMetadata();
  let token = String(
    (metadata.metadata || {}).firebaseStorageDownloadTokens || "",
  ).split(",")[0].trim();
  if (!token) {
    token = crypto.randomUUID();
    await file.setMetadata({
      metadata: { firebaseStorageDownloadTokens: token },
    });
  }
  return buildTokenUrl(path, token);
}

async function publishTranscriptImage(sourcePath, lessonId, index) {
  const source = bucket.file(sourcePath);
  const [metadata] = await source.getMetadata();
  const baseName = safeFileName(sourcePath.split("/").pop() || `page_${index + 1}.jpg`);
  const destinationPath = `lesson_transcripts/${lessonId}/${Date.now()}_${index}_${baseName}`;
  const destination = bucket.file(destinationPath);
  await source.copy(destination);
  const token = crypto.randomUUID();
  await destination.setMetadata({
    contentType: metadata.contentType || "image/jpeg",
    cacheControl: "public,max-age=86400",
    metadata: {
      firebaseStorageDownloadTokens: token,
      sourceSubmissionPath: sourcePath,
    },
  });
  return { path: destinationPath, url: buildTokenUrl(destinationPath, token) };
}

exports.createTranscriptSubmission = functions.https.onCall(async (data, context) => {
  assertAppCheck(context);
  const uid = assertSignedIn(context);
  const lessonId = requireString(data && data.lessonId, "lessonId", 1, 180);
  const lessonSnap = await db.collection("lessons").doc(lessonId).get();
  if (!lessonSnap.exists) {
    throw new functions.https.HttpsError("not-found", "الدرس غير موجود.");
  }
  const lesson = unwrapLegacy(lessonSnap.data());
  const text = cleanString(data && data.text, MAX_TRANSCRIPT_CHARS);
  const bookTitle = cleanString(data && data.bookTitle, 200);
  const sourceRef = cleanString(data && data.sourceRef, 300);
  const note = cleanString(data && data.note, 500);
  const submitterName = cleanString(data && data.submitterName, 60);
  const fcmToken = cleanString(data && data.fcmToken, 4096);
  const requiredPrefix = `transcript_submissions/${uid}/`;
  const rawImagePaths = Array.isArray(data && data.imagePaths) ? data.imagePaths : [];
  const firstImagePath = cleanString(rawImagePaths[0], 700);
  const pathParts = firstImagePath.split("/");
  const pathSubmissionId = pathParts.length >= 4 ? pathParts[2] : "";
  const requestedId = cleanString(
    (data && data.submissionId) || pathSubmissionId,
    180,
  );
  const ref = requestedId && /^[A-Za-z0-9_-]+$/.test(requestedId)
    ? db.collection(TRANSCRIPT_SUBMISSIONS_COLLECTION).doc(requestedId)
    : db.collection(TRANSCRIPT_SUBMISSIONS_COLLECTION).doc();
  // كل الصور يجب أن تكون داخل مجلد هذه المساهمة تحديداً (لا مجلد آخر للمستخدم).
  const imagePaths = await validateTranscriptImages(
    rawImagePaths,
    `${requiredPrefix}${ref.id}/`,
  );
  if (text.length < MIN_TRANSCRIPT_TEXT_CHARS && !imagePaths.length) {
    throw new functions.https.HttpsError(
      "invalid-argument",
      "أرفق نص المقطع أو صورة صفحة واحدة على الأقل.",
    );
  }
  const existing = await ref.get();
  if (existing.exists) {
    const value = existing.data() || {};
    if (value.uid === uid && value.lessonId === lessonId) {
      return { ok: true, id: ref.id, submissionId: ref.id, existing: true };
    }
    throw new functions.https.HttpsError("already-exists", "المساهمة موجودة مسبقاً.");
  }
  await consumeRateLimit({
    uid,
    action: "transcript_submission",
    limit: 5,
    windowMs: 24 * 60 * 60 * 1000,
    minIntervalMs: 60 * 1000,
  });
  await ref.set({
    uid,
    submitterName,
    lessonId,
    lessonTitle: cleanString(lesson.title || lesson.name, 160),
    text,
    bookTitle,
    sourceRef,
    note,
    imagePaths,
    fcmToken,
    status: "pending",
    rejectReason: "",
    createdAt: new Date().toISOString(),
    createdAtTs: admin.firestore.FieldValue.serverTimestamp(),
    createdAtMs: Date.now(),
  });
  return { ok: true, id: ref.id, submissionId: ref.id };
});

exports.approveTranscriptSubmission = functions
  .runWith({ timeoutSeconds: 120, memory: "512MB" })
  .https.onCall(async (data, context) => {
    const actor = await assertAuthorized(context);
    const submissionId = requireString(
      data && data.submissionId,
      "submissionId",
      1,
      180,
    );
    const submissionRef = db.collection(TRANSCRIPT_SUBMISSIONS_COLLECTION)
      .doc(submissionId);
    const firstSnap = await submissionRef.get();
    if (!firstSnap.exists) {
      throw new functions.https.HttpsError("not-found", "المساهمة غير موجودة.");
    }
    const original = firstSnap.data() || {};
    if (original.status !== "pending") {
      if (["approved", "approved_edited"].includes(original.status)) {
        return { ok: true, lessonId: original.lessonId, alreadyApproved: true };
      }
      throw new functions.https.HttpsError(
        "failed-precondition",
        "سبق حسم هذه المساهمة.",
      );
    }
    const lessonId = requireString(original.lessonId, "lessonId", 1, 180);
    const text = cleanString(
      data && data.text !== undefined ? data.text : original.text,
      MAX_TRANSCRIPT_CHARS,
    );
    const bookTitle = cleanString(
      data && data.bookTitle !== undefined ? data.bookTitle : original.bookTitle,
      200,
    );
    const sourceRef = cleanString(
      data && data.sourceRef !== undefined ? data.sourceRef : original.sourceRef,
      300,
    );
    const keepImages = !(data && data.keepImages === false);
    const sourceImages = keepImages
      ? (Array.isArray(original.imagePaths) ? original.imagePaths : [])
      : [];
    if (text.length < MIN_TRANSCRIPT_TEXT_CHARS && !sourceImages.length) {
      throw new functions.https.HttpsError(
        "invalid-argument",
        "لا يمكن اعتماد نص فارغ بلا صور.",
      );
    }
    const transcriptRef = db.collection(TRANSCRIPTS_COLLECTION).doc(lessonId);
    const published = [];
    let previousImagePaths = [];
    try {
      for (let index = 0; index < sourceImages.length; index += 1) {
        published.push(
          await publishTranscriptImage(sourceImages[index], lessonId, index),
        );
      }
      const edited = text !== cleanString(original.text, MAX_TRANSCRIPT_CHARS)
        || bookTitle !== cleanString(original.bookTitle, 200)
        || sourceRef !== cleanString(original.sourceRef, 300)
        || (!keepImages
          && Array.isArray(original.imagePaths)
          && original.imagePaths.length > 0);
      const status = edited ? "approved_edited" : "approved";
      const nowIso = new Date().toISOString();
      await db.runTransaction(async (tx) => {
        const [currentSnap, transcriptSnap] = await Promise.all([
          tx.get(submissionRef),
          tx.get(transcriptRef),
        ]);
        if (!currentSnap.exists || currentSnap.data().status !== "pending") {
          throw new functions.https.HttpsError(
            "aborted",
            "حُسمت المساهمة من مشرف آخر.",
          );
        }
        const previous = transcriptSnap.data() || {};
        previousImagePaths = (Array.isArray(previous.images) ? previous.images : [])
          .map((item) => item && item.path)
          .filter(Boolean);
        tx.set(transcriptRef, {
          lessonId,
          lessonTitle: cleanString(original.lessonTitle, 160),
          text,
          bookTitle,
          sourceRef,
          images: published,
          contributorUid: cleanString(original.uid, 180),
          contributorName: cleanString(original.submitterName, 60),
          sourceSubmissionId: submissionId,
          updatedBy: actor.email,
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
          updatedAtMs: Date.now(),
          createdAt: transcriptSnap.exists
            ? (previous.createdAt || nowIso)
            : nowIso,
        });
        tx.update(submissionRef, {
          status,
          publishedLessonId: lessonId,
          publishedTextPreview: text.slice(0, 200),
          decidedBy: actor.email,
          decidedAt: nowIso,
          decidedAtTs: admin.firestore.FieldValue.serverTimestamp(),
          cleanupPending: false,
        });
      });
      // الصور المعتمدة سابقاً واستُبدلت الآن تُحذف بعد نجاح المعاملة فقط.
      for (const path of previousImagePaths) {
        if (!published.some((item) => item.path === path)) {
          await deleteFileIfExists(path).catch(() => {});
        }
      }
    } catch (error) {
      for (const item of published) {
        await deleteFileIfExists(item.path).catch(() => {});
      }
      throw error;
    }
    // بعد نجاح المعاملة لا داخلها: كتابة الفهرس ليست جزءاً من ذرّية اعتماد
    // النص، وفشلها لا يصحّ أن يُعيد المساهمة إلى «معلّقة» بعد حسمها.
    await syncTranscriptIndex(lessonId, text);
    const originalImages = Array.isArray(original.imagePaths)
      ? original.imagePaths
      : [];
    for (const path of originalImages) {
      try {
        await deleteFileIfExists(path);
      } catch (_) {
        await submissionRef.update({ cleanupPending: true }).catch(() => {});
      }
    }
    await auditOwnerAction(actor.email, "approve_transcript", submissionId, {
      lessonId,
    });
    return { ok: true, lessonId };
  });

exports.rejectTranscriptSubmission = functions.https.onCall(async (data, context) => {
  const actor = await assertAuthorized(context);
  const submissionId = requireString(
    data && data.submissionId,
    "submissionId",
    1,
    180,
  );
  const reason = requireString(data && data.reason, "reason", 2, 300);
  const ref = db.collection(TRANSCRIPT_SUBMISSIONS_COLLECTION).doc(submissionId);
  let imagePaths = [];
  await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!snap.exists) {
      throw new functions.https.HttpsError("not-found", "المساهمة غير موجودة.");
    }
    const value = snap.data() || {};
    if (value.status !== "pending") {
      if (value.status === "rejected") return;
      throw new functions.https.HttpsError(
        "failed-precondition",
        "سبق حسم هذه المساهمة.",
      );
    }
    imagePaths = Array.isArray(value.imagePaths) ? value.imagePaths : [];
    tx.update(ref, {
      status: "rejected",
      rejectReason: reason,
      decidedBy: actor.email,
      decidedAt: new Date().toISOString(),
      decidedAtTs: admin.firestore.FieldValue.serverTimestamp(),
      cleanupPending: imagePaths.length > 0,
    });
  });
  if (imagePaths.length) {
    try {
      for (const path of imagePaths) await deleteFileIfExists(path);
      await ref.update({ cleanupPending: false });
    } catch (_) {
      // تبقى cleanupPending=true لإعادة المحاولة الآمنة لاحقاً.
    }
  }
  await auditOwnerAction(actor.email, "reject_transcript", submissionId, { reason });
  return { ok: true, id: submissionId };
});

exports.deleteTranscriptSubmission = functions.https.onCall(async (data, context) => {
  const actor = await assertAuthorized(context);
  const submissionId = requireString(
    data && data.submissionId,
    "submissionId",
    1,
    180,
  );
  const ref = db.collection(TRANSCRIPT_SUBMISSIONS_COLLECTION).doc(submissionId);
  const snap = await ref.get();
  if (!snap.exists) return { ok: true, alreadyDeleted: true };
  const value = snap.data() || {};
  const imagePaths = Array.isArray(value.imagePaths) ? value.imagePaths : [];
  for (const path of imagePaths) await deleteFileIfExists(path).catch(() => {});
  await ref.delete();
  await auditOwnerAction(actor.email, "delete_transcript_submission", submissionId, {});
  return { ok: true };
});

exports.deleteMyTranscriptSubmission = functions.https.onCall(async (data, context) => {
  assertAppCheck(context);
  const uid = assertSignedIn(context);
  const submissionId = requireString(
    data && data.submissionId,
    "submissionId",
    1,
    180,
  );
  const ref = db.collection(TRANSCRIPT_SUBMISSIONS_COLLECTION).doc(submissionId);
  const snap = await ref.get();
  if (!snap.exists) return { ok: true, alreadyDeleted: true };
  const value = snap.data() || {};
  if (value.uid !== uid || value.status !== "pending") {
    throw new functions.https.HttpsError("permission-denied", "لا يمكن حذف هذا الطلب.");
  }
  const imagePaths = Array.isArray(value.imagePaths) ? value.imagePaths : [];
  for (const path of imagePaths) await deleteFileIfExists(path).catch(() => {});
  await ref.delete();
  return { ok: true };
});

// إضافة/تعديل مباشر من لوحة الإدارة: الصور تكون قد رُفعت مسبقاً عبر SDK إلى
// lesson_transcripts/{lessonId}/ (قواعد التخزين تسمح بذلك للمشرفين فقط)،
// والخادم يتحقق منها ويولّد روابطها ويحذف اليتيم منها.
exports.upsertLessonTranscript = functions
  .runWith({ timeoutSeconds: 120, memory: "512MB" })
  .https.onCall(async (data, context) => {
    const actor = await assertAuthorized(context);
    const lessonId = requireString(data && data.lessonId, "lessonId", 1, 180);
    const transcriptRef = db.collection(TRANSCRIPTS_COLLECTION).doc(lessonId);
    const requiredPrefix = `lesson_transcripts/${lessonId}/`;
    if (data && data.remove === true) {
      await transcriptRef.delete();
      // الفهرس يتبع المتن: بقاؤه بعد حذفه يعني نتيجة بحثٍ تفتح درساً بلا نص.
      await db.collection(TRANSCRIPT_INDEX_COLLECTION).doc(lessonId)
        .delete().catch(() => {});
      await bucket.deleteFiles({ prefix: requiredPrefix }).catch(() => {});
      await auditOwnerAction(actor.email, "delete_transcript", lessonId, {});
      return { ok: true, removed: true };
    }
    const lessonSnap = await db.collection("lessons").doc(lessonId).get();
    if (!lessonSnap.exists) {
      throw new functions.https.HttpsError("not-found", "الدرس غير موجود.");
    }
    const lesson = unwrapLegacy(lessonSnap.data());
    const text = cleanString(data && data.text, MAX_TRANSCRIPT_CHARS);
    const bookTitle = cleanString(data && data.bookTitle, 200);
    const sourceRef = cleanString(data && data.sourceRef, 300);
    const imagePaths = await validateTranscriptImages(
      data && data.imagePaths,
      requiredPrefix,
    );
    if (text.length < MIN_TRANSCRIPT_TEXT_CHARS && !imagePaths.length) {
      throw new functions.https.HttpsError(
        "invalid-argument",
        "أدخل نص المقطع أو أرفق صورة واحدة على الأقل.",
      );
    }
    const images = [];
    for (const path of imagePaths) {
      images.push({ path, url: await ensureImageDownloadUrl(path) });
    }
    // حذف صور المجلد التي لم تعد ضمن القائمة المرسلة (اليتيمة).
    try {
      const [existingFiles] = await bucket.getFiles({ prefix: requiredPrefix });
      for (const file of existingFiles) {
        if (!imagePaths.includes(file.name)) await file.delete().catch(() => {});
      }
    } catch (_) { /* تنظيف اختياري */ }
    const prevSnap = await transcriptRef.get();
    const previous = prevSnap.data() || {};
    const nowIso = new Date().toISOString();
    await transcriptRef.set({
      lessonId,
      lessonTitle: cleanString(lesson.title || lesson.name, 160),
      text,
      bookTitle,
      sourceRef,
      images,
      contributorUid: cleanString(previous.contributorUid, 180),
      contributorName: cleanString(previous.contributorName, 60),
      sourceSubmissionId: cleanString(previous.sourceSubmissionId, 180),
      updatedBy: actor.email,
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      updatedAtMs: Date.now(),
      createdAt: prevSnap.exists ? (previous.createdAt || nowIso) : nowIso,
    });
    await syncTranscriptIndex(lessonId, text);
    await auditOwnerAction(actor.email, "upsert_transcript", lessonId, {
      images: images.length,
    });
    return { ok: true, lessonId, images };
  });

/**
 * 🏗️ بناء فهرس البحث لما هو موجود من متون (backfill) — للمالك وحده.
 *
 * تُنادى مرّة بعد نشر هذه النسخة، فالمتون المعتمدة قبلها لا مُشغِّل يمرّ
 * عليها. ⛔ وعمداً لا كرون دوريّ ولا مُشغِّل على lessons: الكلفة تُحسب،
 * والمزامنة بعد ذلك تتم في مسارات الكتابة نفسها لا غير.
 *
 * تمشي على دفعات بمؤشّر معرّف الوثيقة، وتتوقّف قبل نفاد المهلة معيدةً
 * `nextStartAfter` كي تُستأنف بنداءٍ ثانٍ إن كانت المتون كثيرة جداً.
 * `force: true` تُعيد بناء ما هو مفهرس أصلاً (بعد تغيير قواعد التقطيع).
 */
exports.backfillTranscriptIndex = functions
  .runWith({ timeoutSeconds: 540, memory: "512MB" })
  .https.onCall(async (data, context) => {
    const owner = await assertOwner(context);
    const force = !!(data && data.force === true);
    const pageSize = 120;
    // هامش دون المهلة (540ث) يكفي لإنهاء الدفعة الجارية وإرجاع المؤشّر.
    const deadline = Date.now() + 470 * 1000;
    let cursor = cleanString(data && data.startAfter, 180);
    let scanned = 0;
    let indexed = 0;
    let cleared = 0;
    let done = false;
    for (;;) {
      let query = db.collection(TRANSCRIPTS_COLLECTION)
        .orderBy(admin.firestore.FieldPath.documentId())
        .limit(pageSize);
      if (cursor) query = query.startAfter(cursor);
      const snap = await query.get();
      if (snap.empty) {
        done = true;
        break;
      }
      const indexRefs = snap.docs.map(
        (doc) => db.collection(TRANSCRIPT_INDEX_COLLECTION).doc(doc.id),
      );
      // قراءة واحدة لكل الدفعة: إعادة النداء بعد اكتمالها لا تكلّف كتابات.
      const existing = force ? [] : await db.getAll(...indexRefs);
      const alreadyIndexed = new Set(
        existing.filter((snapshot) => snapshot.exists).map((snapshot) => snapshot.id),
      );
      const batch = db.batch();
      let writes = 0;
      snap.docs.forEach((doc, position) => {
        scanned += 1;
        if (alreadyIndexed.has(doc.id)) return;
        const keywords = transcriptIndexKeywords((doc.data() || {}).text);
        if (!keywords.length) {
          // متنٌ بلا كلمات (صور صفحات فقط). في المسار العادي لا وثيقة فهرس
          // له أصلاً — تخطّيه أرخص من حذفٍ لا يحذف شيئاً.
          if (!force) return;
          cleared += 1;
          batch.delete(indexRefs[position]);
        } else {
          indexed += 1;
          batch.set(indexRefs[position], {
            lessonId: doc.id,
            keywords,
            updatedAt: admin.firestore.FieldValue.serverTimestamp(),
          });
        }
        writes += 1;
      });
      if (writes) await batch.commit();
      cursor = snap.docs[snap.docs.length - 1].id;
      if (snap.size < pageSize) {
        done = true;
        break;
      }
      if (Date.now() > deadline) break;
    }
    await auditOwnerAction(owner, "backfill_transcript_index", "", {
      scanned,
      indexed,
      done,
    });
    return {
      ok: true,
      scanned,
      indexed,
      cleared,
      done,
      nextStartAfter: done ? "" : cursor,
    };
  });

/// محارف التحكّم ثنائيّة الاتّجاه — تكسر عرض النصّ العربي وتلتصق بمخرجات
/// الماسح، ولا يراها المستخدم فلا يفهم لماذا اضطرب السطر.
const BIDI_CONTROLS = /[\u200b-\u200f\u202a-\u202e\u2066-\u2069\ufeff]/g;
const ARABIC_LETTER = /[\u0600-\u06ff]/;
const HAS_CONTENT = /[\w\u0600-\u06ff]/;

/**
 * 🧹 تنقية سطرٍ ممسوح: يُعاد فارغاً إن كان ضوضاء.
 *
 * ضوضاء الماسح في هوامش الكتب: أرقام الصفحات، والشُّرَط، وحروفٌ لاتينيّة
 * مفردة يخلّفها الحبر أو الظلّ. وشرط «قصيرٌ وبلا حرف عربيّ» يحفظ النصّ
 * الأجنبيّ الحقيقيّ (اسم كتاب مثلاً) ويُسقط الشاردة.
 */
function cleanScannedLine(line) {
  const value = String(line || "").replace(BIDI_CONTROLS, "")
    .replace(/[ \t\u00a0]+/g, " ").trim();
  if (!value) return "";
  if (!ARABIC_LETTER.test(value) && value.length <= 6) return "";
  if (!HAS_CONTENT.test(value)) return "";
  return value;
}

/**
 * 📄 نصّ الصفحة من بنية Vision — **سطرٌ لكلّ سطرٍ مطبوع**.
 *
 * ⛔ ولا تُدمج الأسطر في فقرات: أكثر متون هذه المكتبة منظومات (لامية العجم،
 * نظم مقدّمة الرسالة…) والبيت سطرٌ مستقلّ، فالدمج — وإن بدا «أنظف» في
 * النثر — يُذهب الوزن ويُتلف الشعر. البنية تُحفظ كما في الورقة، والعمل
 * كلّه في إسقاط الضوضاء: أسقط ٧٣ سطر ضوضاء من متون المكتبة إلى صفر.
 */
function pageTextOf(fullTextAnnotation) {
  const annotation = fullTextAnnotation || {};
  const blocks = [];
  for (const page of annotation.pages || []) {
    for (const block of page.blocks || []) {
      const lines = [];
      let buffer = "";
      for (const paragraph of block.paragraphs || []) {
        for (const word of paragraph.words || []) {
          const symbols = word.symbols || [];
          buffer += symbols.map((s) => s.text || "").join("");
          const last = symbols[symbols.length - 1] || {};
          const brk = ((last.property || {}).detectedBreak || {}).type || "";
          if (brk === "SPACE" || brk === "SURE_SPACE") {
            buffer += " ";
          } else if (brk === "EOL_SURE_SPACE" || brk === "LINE_BREAK") {
            lines.push(buffer);
            buffer = "";
          }
        }
      }
      if (buffer) lines.push(buffer);
      const kept = lines.map(cleanScannedLine).filter(Boolean);
      if (kept.length) blocks.push(kept.join("\n"));
    }
  }
  return blocks.join("\n\n");
}

/**
 * 🔎 استخراج نصّ صورةٍ واحدة عبر Cloud Vision.
 *
 * يستعملها المسار التلقائيّ (المُشغِّل أدناه). وتُعيد نصّاً فارغاً عند أيّ
 * عطل ولا ترمي: الاستخراج تحسينٌ لا شرطٌ لاعتماد النصّ، فصورةٌ واحدة تعصى
 * لا يجوز أن تُسقط بقيّة الصفحات. أمّا زرّ المشرف فيبقى على مساره الخاصّ
 * لأنّه يحتاج تمييز «الواجهة غير مفعّلة» من «صورة بلا نصّ» ليقوله له.
 */
async function visionTextOf(storagePath) {
  const file = bucket.file(storagePath);
  let metadata;
  try {
    [metadata] = await file.getMetadata();
  } catch (_) {
    return "";
  }
  const size = Number(metadata.size || 0);
  if (size <= 0 || size > MAX_TRANSCRIPT_IMAGE_BYTES
      || !String(metadata.contentType || "").startsWith("image/")) {
    return "";
  }
  const [buffer] = await file.download();
  const { GoogleAuth } = require("google-auth-library");
  const auth = new GoogleAuth({
    scopes: ["https://www.googleapis.com/auth/cloud-platform"],
  });
  const client = await auth.getClient();
  const response = await client.request({
    url: "https://vision.googleapis.com/v1/images:annotate",
    method: "POST",
    data: {
      requests: [{
        image: { content: buffer.toString("base64") },
        features: [{ type: "DOCUMENT_TEXT_DETECTION" }],
        imageContext: { languageHints: ["ar"] },
      }],
    },
  });
  const result = (((response.data || {}).responses || [])[0]) || {};
  return cleanString(pageTextOf(result.fullTextAnnotation), MAX_TRANSCRIPT_CHARS);
}

// استخراج النص من صورة صفحة الكتاب (OCR عربي) — للمشرفين فقط، عبر Cloud
// Vision REST بهوية حساب خدمة الدوال. إن لم تكن الواجهة مفعّلة تُعاد رسالة
// إرشادية واضحة بدل فشل غامض.
exports.extractImageText = functions
  .runWith({ timeoutSeconds: 60, memory: "512MB" })
  .https.onCall(async (data, context) => {
    await assertAuthorized(context);
    const storagePath = requireString(data && data.storagePath, "storagePath", 1, 700);
    const allowed = storagePath.startsWith("transcript_submissions/")
      || storagePath.startsWith("lesson_transcripts/");
    if (!allowed || storagePath.includes("..")) {
      throw new functions.https.HttpsError("permission-denied", "مسار الصورة غير صالح.");
    }
    const file = bucket.file(storagePath);
    let metadata;
    try {
      [metadata] = await file.getMetadata();
    } catch (_) {
      throw new functions.https.HttpsError("not-found", "الصورة غير موجودة.");
    }
    const size = Number(metadata.size || 0);
    if (size <= 0 || size > MAX_TRANSCRIPT_IMAGE_BYTES
        || !String(metadata.contentType || "").startsWith("image/")) {
      throw new functions.https.HttpsError("invalid-argument", "الصورة غير صالحة للاستخراج.");
    }
    const [buffer] = await file.download();
    const { GoogleAuth } = require("google-auth-library");
    const auth = new GoogleAuth({
      scopes: ["https://www.googleapis.com/auth/cloud-platform"],
    });
    const client = await auth.getClient();
    let response;
    try {
      response = await client.request({
        url: "https://vision.googleapis.com/v1/images:annotate",
        method: "POST",
        data: {
          requests: [{
            image: { content: buffer.toString("base64") },
            features: [{ type: "DOCUMENT_TEXT_DETECTION" }],
            imageContext: { languageHints: ["ar"] },
          }],
        },
      });
    } catch (error) {
      console.error("vision request failed", error && error.message);
      throw new functions.https.HttpsError(
        "failed-precondition",
        "تعذّر استخراج النص. تأكد من تفعيل Cloud Vision API لمشروع mxqp-8d1e8 ثم أعد المحاولة.",
      );
    }
    const result = (((response.data || {}).responses || [])[0]) || {};
    if (result.error && result.error.message) {
      throw new functions.https.HttpsError(
        "internal",
        cleanString(result.error.message, 300) || "فشل استخراج النص.",
      );
    }
    const text = cleanString(
      (result.fullTextAnnotation || {}).text,
      MAX_TRANSCRIPT_CHARS,
    );
    return { ok: true, text };
  });

/**
 * 🤖 **استخراج النصّ تلقائياً من صور «النص المشروح»** — بلا تدخّل مشرف.
 *
 * **لماذا؟** أغلب المتون تُرفع صوراً لصفحات الكتاب وحقلُ النصّ فارغ، فيبقى
 * المتن غير قابلٍ للبحث ولا للنسخ ولا لقارئ الشاشة — ولا يملؤه إلا مشرفٌ
 * يضغط زرّ الاستخراج في كل درس. فصار يجري من نفسه لحظةَ كتابة المتن.
 *
 * ⛔ **حارس التكرار**: المُشغِّل يكتب في الوثيقة نفسها التي أطلقته، فلولا
 * `ocrAt` لدار بلا نهاية. ولا يعمل إلا حين يكون النصّ فارغاً فعلاً وفيها
 * صور — فنصّ المشرف المكتوب بيده لا يُدهَس أبداً.
 */
exports.autoExtractTranscriptText = functions
  .runWith({ timeoutSeconds: 300, memory: "1GB" })
  .firestore.document("lesson_transcripts/{lessonId}")
  .onWrite(async (change, context) => {
    const after = change.after.exists ? unwrapLegacy(change.after.data()) : null;
    if (!after) return null;
    // نصٌّ موجود ⇒ لا شأن لنا به (وهذا يشمل ما كتبناه نحن، فلا دوران).
    if (cleanString(after.text, 10).length > 0) return null;
    if (after.ocrAt) return null;
    const images = Array.isArray(after.images) ? after.images : [];
    const paths = images
      .map((img) => cleanString(img && img.path, 700))
      .filter((p) => p && p.startsWith("lesson_transcripts/") && !p.includes(".."));
    if (!paths.length) return null;

    const lessonId = context.params.lessonId;
    const parts = [];
    for (const path of paths) {
      try {
        const text = await visionTextOf(path);
        if (text) parts.push(text);
      } catch (error) {
        console.error("auto ocr failed", lessonId, path, error && error.message);
      }
    }
    // نضع الوسم في الحالين: نجح الاستخراج أم عاد فارغاً (صورةٌ بلا نصّ
    // مقروء) — وإلّا أُعيدت المحاولة على الصور نفسها مع كل كتابة لاحقة.
    const merged = cleanString(parts.join("\n\n"), MAX_TRANSCRIPT_CHARS);
    await change.after.ref.set({
      text: merged,
      ocrAt: admin.firestore.FieldValue.serverTimestamp(),
      ocrPages: paths.length,
    }, { merge: true });
    if (merged) {
      console.log("auto ocr ok", lessonId, paths.length, "pages", merged.length, "chars");
      await syncTranscriptIndex(lessonId, merged);
    }
    return null;
  });

exports.onTranscriptSubmissionCreated = functions.firestore
  .document("transcript_submissions/{id}")
  .onCreate(async (snap) => {
    const d = snap.data() || {};
    const who = cleanString(d.submitterName, 60) || "مستمع";
    const lessonTitle = cleanString(d.lessonTitle, 160) || "درس";
    const hasImages = Array.isArray(d.imagePaths) && d.imagePaths.length > 0;
    const alertTitle = "اقتراح نص مشروح بانتظار المراجعة";
    const alertBody = hasImages
      ? `أرسل ${who} نص/صور المقطع المشروح لدرس «${lessonTitle}».`
      : `أرسل ${who} نص المقطع المشروح لدرس «${lessonTitle}».`;
    await Promise.all([
      writeAdminAlert("", alertTitle, alertBody, {
        type: "transcript",
        submissionId: snap.id,
        refId: snap.id,
        lessonId: cleanString(d.lessonId, 180),
      }),
      // وجهة اللوحة صريحة: تبويب «النصوص المشروحة» في شاشة المراجعة.
      pushToAdmins(alertTitle, alertBody, {
        type: "transcript",
        submissionId: snap.id,
        refId: snap.id,
        lessonId: cleanString(d.lessonId, 180),
        route: "submissions",
      }),
    ]);
    return null;
  });

exports.onTranscriptSubmissionDecided = functions.firestore
  .document("transcript_submissions/{id}")
  .onUpdate(async (change) => {
    const before = change.before.data() || {};
    const after = change.after.data() || {};
    if (before.status !== "pending" || after.status === "pending") return null;
    const token = cleanString(after.fcmToken, 4096);
    const lessonTitle = cleanString(after.lessonTitle, 160) || "الدرس";
    // معرّف الدرس المرتبط بالاقتراح — قد يخلو منه سجلّ قديم، وسلسلة فارغة
    // تُفسِد التوجيه (يسقط العميل على معرّف المساهمة فيبني شاشة ميتة)،
    // لذا لا يُرسل المفتاح إلا صالحاً، ويرافقه `route` صريح دائماً.
    const linkedLessonId = cleanString(after.lessonId, 180);
    if (after.status === "approved" || after.status === "approved_edited") {
      const edited = after.status === "approved_edited";
      const notificationTitle = edited
        ? "اعتُمد النص الذي أرسلته بعد المراجعة"
        : "اعتُمد النص الذي أرسلته";
      const notificationBody = `صار النص المشروح ظاهراً في درس «${lessonTitle}». شكراً لمساهمتك.`;
      const notificationData = {
        type: "transcript",
        id: change.after.id,
        refId: change.after.id,
        submissionId: change.after.id,
        result: after.status,
        route: linkedLessonId ? "lesson" : "my-submissions",
      };
      if (linkedLessonId) notificationData.lessonId = linkedLessonId;
      await Promise.all([
        clearAdminAlerts("transcript", change.after.id),
        writeUserNotification(after.uid, notificationTitle, notificationBody, notificationData),
        pushToToken(token, notificationTitle, notificationBody, notificationData),
      ]);
      return null;
    }
    if (after.status === "rejected") {
      const reason = cleanString(after.rejectReason, 300);
      const notificationTitle = "نتيجة مراجعة النص المقترح";
      const notificationBody = reason
        ? `لم يُعتمد نص «${lessonTitle}»: ${reason}`
        : `لم يُعتمد نص «${lessonTitle}».`;
      // ⛳ الوجهة «مساهماتي» صراحةً: هنا يقرأ المستمع سبب عدم الاعتماد.
      const notificationData = {
        type: "transcript",
        id: change.after.id,
        refId: change.after.id,
        submissionId: change.after.id,
        result: "rejected",
        route: "my-submissions",
      };
      if (linkedLessonId) notificationData.lessonId = linkedLessonId;
      await Promise.all([
        clearAdminAlerts("transcript", change.after.id),
        writeUserNotification(after.uid, notificationTitle, notificationBody, notificationData),
        pushToToken(token, notificationTitle, notificationBody, notificationData),
      ]);
      return null;
    }
    return null;
  });

async function queueStorageCleanup(paths, reason, targetId) {
  const unique = [...new Set((paths || []).map(storagePathFromUrl).filter(Boolean))];
  if (!unique.length) return "";
  const ref = await db.collection("storage_cleanup_jobs").add({
    paths: unique,
    reason,
    targetId: targetId || "",
    status: "pending",
    attempts: 0,
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
    createdAtMs: Date.now(),
  });
  return ref.id;
}

function lessonStoragePaths(value) {
  return [...new Set([
    value && value.storagePath,
    value && value.audioStoragePath,
    value && value.audioUrl,
  ].map(storagePathFromUrl).filter(Boolean))];
}

async function deletePathsBestEffort(paths, reason, targetId) {
  const failed = [];
  for (const path of paths) {
    try {
      await deleteFileIfExists(path);
    } catch (_) {
      failed.push(path);
    }
  }
  const cleanupJobId = failed.length
    ? await queueStorageCleanup(failed, reason, targetId)
    : "";
  return { failed, cleanupJobId };
}

// 🗑️ سلة المحذوفات: الحذف نقلٌ لا إعدام — الوثيقة (ومعها نصّها المشروح)
// تنتقل إلى deleted_lessons وملفات التخزين تبقى كما هي، فتصحّ الاستعادة
// بنقرة. الحذف النهائي (يدوي أو بعد TRASH_RETENTION_MS) هو وحده ما يمسح
// الملفات. (درسٌ من فاجعة 2026-08-01: حذفٌ بالخطأ استلزم إنقاذاً من
// soft-delete التخزين ونافذة الساعة في Firestore.)
const TRASH_COLLECTION = "deleted_lessons";
const TRASH_RETENTION_MS = 30 * 24 * 60 * 60 * 1000;

async function deleteLessonHandler(data, context) {
  const actor = await assertAuthorized(context);
  const lessonId = requireString(data && data.lessonId, "lessonId", 1, 180);
  const lessonRef = db.collection("lessons").doc(lessonId);
  const reviewRef = db.collection("owner_lesson_reviews").doc(lessonId);
  const transcriptRef = db.collection("lesson_transcripts").doc(lessonId);
  const [lessonSnap, reviewSnap, transcriptSnap] = await Promise.all([
    lessonRef.get(),
    reviewRef.get(),
    transcriptRef.get(),
  ]);
  if (!lessonSnap.exists) return { ok: true, alreadyDeleted: true };
  const batch = db.batch();
  batch.set(db.collection(TRASH_COLLECTION).doc(lessonId), {
    lesson: lessonSnap.data(),
    transcript: transcriptSnap.exists ? transcriptSnap.data() : null,
    deletedBy: actor.email,
    deletedAt: admin.firestore.FieldValue.serverTimestamp(),
    deletedAtMs: Date.now(),
    purgeAfterMs: Date.now() + TRASH_RETENTION_MS,
  });
  batch.delete(lessonRef);
  batch.delete(transcriptRef);
  // فهرس البحث لا يُحفظ في السلة: يُعاد بناؤه من نصّ الدرس عند الاستعادة.
  batch.delete(db.collection(TRANSCRIPT_INDEX_COLLECTION).doc(lessonId));
  if (reviewSnap.exists) {
    batch.update(reviewRef, {
      status: "deleted",
      resolution: "delete_by_admin",
      resolvedBy: actor.email,
      resolvedAt: admin.firestore.FieldValue.serverTimestamp(),
    });
  }
  await batch.commit();
  await auditOwnerAction(actor.email, "trash_lesson", lessonId, {});
  return { ok: true, id: lessonId, trashed: true };
}

/** استعادة درس من السلة: تعيد الوثيقة ونصّها المشروح كما كانا. */
exports.restoreDeletedLesson = functions.https.onCall(async (data, context) => {
  const actor = await assertAuthorized(context);
  const lessonId = requireString(data && data.lessonId, "lessonId", 1, 180);
  const trashRef = db.collection(TRASH_COLLECTION).doc(lessonId);
  const snap = await trashRef.get();
  if (!snap.exists) {
    throw new functions.https.HttpsError("not-found", "العنصر غير موجود في السلة.");
  }
  const value = snap.data() || {};
  const lesson = value.lesson;
  if (!lesson || typeof lesson !== "object") {
    throw new functions.https.HttpsError("internal", "بيانات السلة غير مكتملة.");
  }
  const batch = db.batch();
  // ♻️ وسم الاستعادة: مُشغِّلات الإنشاء تُطلق ثانيةً عند إعادة الكتابة،
  // فيميّز هذا الوسمُ الدرسَ المستعاد من درس جديد فعلاً (يستعمله كاشف
  // الشبهة كي لا يُعيد إزعاج المالك ببلاغ سبق أن رآه). لا يمسّ حقول
  // المحتوى فبصمة المراجعة تبقى كما هي.
  batch.set(db.collection("lessons").doc(lessonId), Object.assign({}, lesson, {
    restoredAt: admin.firestore.FieldValue.serverTimestamp(),
    restoredAtMs: Date.now(),
    restoredBy: actor.email,
    // 🔁 طابع جديد كي تلتقط المزامنةُ التفاضلية الوثيقةَ المستعادة —
    // إعادتها بطابعها القديم تجعلها غير مرئية للدلتا فيُجبَر كل جهاز شهد
    // الحذف على جلب المكتبة كاملة.
    updatedAt: admin.firestore.FieldValue.serverTimestamp(),
  }));
  // 🔁 ومحوُ شاهد الحذف: بقاؤه يُسقط الوثيقة المستعادة عند جهاز تشمل
  // نافذةُ مزامنته الحذفَ والاستعادةَ معاً («الحذف مقدَّم على التعديل»).
  batch.delete(db.collection(DELETED_IDS_COLLECTION).doc(`lessons__${lessonId}`));
  if (value.transcript && typeof value.transcript === "object") {
    batch.set(db.collection("lesson_transcripts").doc(lessonId), value.transcript);
    // ويعود معه فهرس بحثه، وإلّا رجع الدرس ونصّه بلا أن يجده أحد بكلمة منه.
    const keywords = transcriptIndexKeywords(value.transcript.text);
    if (keywords.length) {
      batch.set(db.collection(TRANSCRIPT_INDEX_COLLECTION).doc(lessonId), {
        lessonId,
        keywords,
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      });
    }
  }
  batch.delete(trashRef);
  await batch.commit();
  await auditOwnerAction(actor.email, "restore_lesson", lessonId, {});
  return { ok: true, id: lessonId };
});

/** الحذف النهائي من السلة: يمسح الوثيقة وملفات التخزين معاً. */
async function purgeTrashedLesson(trashDoc, actorEmail) {
  const value = trashDoc.data() || {};
  const lesson = unwrapLegacy(value.lesson || {});
  const paths = lessonStoragePaths(lesson);
  const transcript = value.transcript || {};
  (Array.isArray(transcript.images) ? transcript.images : []).forEach((item) => {
    const path = item && item.path && storagePathFromUrl(item.path);
    if (path) paths.push(path);
  });
  await trashDoc.ref.delete();
  await bucket.deleteFiles({ prefix: `lesson_transcripts/${trashDoc.id}/` })
    .catch(() => {});
  const cleanup = await deletePathsBestEffort(paths, "purge_lesson", trashDoc.id);
  if (actorEmail) {
    await auditOwnerAction(actorEmail, "purge_lesson", trashDoc.id, {
      cleanupPending: cleanup.failed.length > 0,
    });
  }
  return cleanup;
}

exports.purgeDeletedLesson = functions
  .runWith({ timeoutSeconds: 120, memory: "512MB" })
  .https.onCall(async (data, context) => {
    const actor = await assertAuthorized(context);
    const lessonId = requireString(data && data.lessonId, "lessonId", 1, 180);
    const snap = await db.collection(TRASH_COLLECTION).doc(lessonId).get();
    if (!snap.exists) return { ok: true, alreadyDeleted: true };
    const cleanup = await purgeTrashedLesson(snap, actor.email);
    return { ok: true, id: lessonId, cleanupPending: cleanup.failed.length > 0 };
  });

/** تفريغ السلة كاملةً — **للمالك حصراً**: حذف نهائي لكل محتوياتها. */
exports.emptyTrash = functions
  .runWith({ timeoutSeconds: 540, memory: "512MB" })
  .https.onCall(async (data, context) => {
    const actorEmail = await assertOwner(context);
    const snap = await db.collection(TRASH_COLLECTION).get();
    let purged = 0;
    for (const doc of snap.docs) {
      try {
        await purgeTrashedLesson(doc, "");
        purged += 1; // العدّ عند النجاح فقط — الفشل لا يُحسب.
      } catch (error) {
        console.error("empty trash item failed", doc.id, error);
      }
    }
    await auditOwnerAction(actorEmail, "empty_trash", "", { purged });
    return { ok: true, purged };
  });

/**
 * 🔀 إعادة ترتيب دروس قسم فرعي: ترتيب التطبيق قائم على تاريخ الإنشاء
 * (الأقدم أولاً افتراضياً، والأحدث إن اختاره المستمع) — لذا لا نخترع
 * حقلاً جديداً بل **نعيد توزيع طوابع الإنشاء الموجودة نفسها** على الدروس
 * بالترتيب المطلوب: أقدم طابع لأول درس في الترتيب الجديد وهكذا. فيصحّ
 * الترتيبان تلقائياً في كل النسخ المثبتة بلا أي تعديل على التطبيق العام.
 */
exports.reorderSubcategoryLessons = functions
  .runWith({ timeoutSeconds: 300, memory: "512MB" })
  .https.onCall(async (data, context) => {
    const actor = await assertAuthorized(context);
    const subcategoryId = requireString(
      data && data.subcategoryId,
      "subcategoryId",
      1,
      180,
    );
    const orderedIds = Array.isArray(data && data.lessonIds)
      ? data.lessonIds.map((id) => cleanString(id, 180)).filter(Boolean)
      : [];
    if (orderedIds.length < 2) {
      throw new functions.https.HttpsError(
        "invalid-argument",
        "أرسل ترتيباً يضم درسين على الأقل.",
      );
    }
    // ⚠️ أربعة أشكال لا اثنان: اللوحة تعرف أيضاً الشكل الأقدم الذي يخزّن
    // القسم في خريطة `subcategory._id` المتداخلة (بلا حقل جذري) — حصرُ
    // الاستعلام بالشكلين الحديثين كان يجعل مطابقة القائمة تفشل دائماً
    // برسالة «حدّث الشاشة» المضلّلة لهذه الدروس.
    const [plainSnap, wrappedSnap, legacySnap, wrappedLegacySnap] = await Promise.all([
      db.collection("lessons").where("subcategoryId", "==", subcategoryId).get(),
      db.collection("lessons")
        .where("data.subcategoryId", "==", subcategoryId).get(),
      db.collection("lessons").where("subcategory._id", "==", subcategoryId).get(),
      db.collection("lessons")
        .where("data.subcategory._id", "==", subcategoryId).get(),
    ]);
    const byId = new Map();
    [...plainSnap.docs, ...wrappedSnap.docs, ...legacySnap.docs, ...wrappedLegacySnap.docs]
      .forEach((doc) => byId.set(doc.id, doc));
    // الترتيب المرسل يجب أن يطابق دروس القسم تماماً (لا أكثر ولا أقل):
    // قائمة ناقصة تعني أن اللوحة ترى نسخة قديمة — نرفض بدل خلط الترتيب.
    if (orderedIds.length !== byId.size ||
        orderedIds.some((id) => !byId.has(id))) {
      throw new functions.https.HttpsError(
        "failed-precondition",
        "قائمة الترتيب لا تطابق دروس القسم الحالية — حدّث الشاشة وأعد المحاولة.",
      );
    }
    // جمع طوابع الإنشاء الحالية ثم فرزها تصاعدياً وإزالة أي تطابق بدفعة
    // +1ms — طابعان متساويان يجعلان موضعَي درسين غير محسومَين.
    const parseMs = (value) => {
      const raw = unwrapLegacy(value);
      const fromIso = Date.parse(String(raw.createdAt || ""));
      if (!Number.isNaN(fromIso)) return fromIso;
      if (Number.isFinite(Number(raw.createdAtMs))) return Number(raw.createdAtMs);
      if (raw.createdAtTs && typeof raw.createdAtTs.toMillis === "function") {
        return raw.createdAtTs.toMillis();
      }
      return Date.now();
    };
    const stamps = orderedIds
      .map((id) => parseMs(byId.get(id).data()))
      .sort((a, b) => a - b);
    for (let i = 1; i < stamps.length; i += 1) {
      if (stamps[i] <= stamps[i - 1]) stamps[i] = stamps[i - 1] + 1;
    }
    const batch = db.batch();
    orderedIds.forEach((id, index) => {
      const doc = byId.get(id);
      const ms = stamps[index];
      const iso = new Date(ms).toISOString();
      const update = {
        createdAt: iso,
        createdAtTs: admin.firestore.Timestamp.fromMillis(ms),
        createdAtMs: ms,
        reorderedBy: actor.email,
        reorderedAt: admin.firestore.FieldValue.serverTimestamp(),
        // ⚠️ بلا `updatedAt` لا يصل الترتيب الجديد لأحد: مسبار المزامنة
        // التفاضلية في التطبيق لا يراقب إلا الأعداد وأحدث `updatedAt` —
        // وإعادة توزيع طوابع الإنشاء وحدها لا تغيّر أياً منهما.
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      };
      // الوثائق القديمة الملفوفة `{data:{...}}`: التطبيق يقرأ المفتاح
      // الملفوف — يُحدَّث الموضعان معاً.
      const raw = doc.data() || {};
      if (raw.data && typeof raw.data === "object") {
        update["data.createdAt"] = iso;
        update["data.updatedAt"] = admin.firestore.FieldValue.serverTimestamp();
      }
      batch.update(doc.ref, update);
    });
    await batch.commit();
    await auditOwnerAction(actor.email, "reorder_subcategory", subcategoryId, {
      lessons: orderedIds.length,
    });
    return { ok: true, id: subcategoryId, lessons: orderedIds.length };
  });

/** تنظيف يومي: ما تجاوز مدة بقائه في السلة (30 يوماً) يُحذف نهائياً. */
exports.purgeExpiredTrash = functions
  .runWith({ timeoutSeconds: 300, memory: "512MB" })
  .pubsub.schedule("40 3 * * *")
  .timeZone("Asia/Riyadh")
  .onRun(async () => {
    const snap = await db.collection(TRASH_COLLECTION)
      .where("purgeAfterMs", "<", Date.now())
      .limit(100)
      .get();
    for (const doc of snap.docs) {
      await purgeTrashedLesson(doc, "").catch((error) => {
        console.error("trash purge failed", doc.id, error);
      });
    }
    // وسلّة الأقسام بالمنطق نفسه: وثيقة واحدة لكل قسم بلا ملفات تخزين.
    const sectionsSnap = await db.collection(SECTION_TRASH_COLLECTION)
      .where("purgeAfterMs", "<", Date.now())
      .limit(100)
      .get();
    for (const doc of sectionsSnap.docs) {
      await doc.ref.delete().catch((error) => {
        console.error("section trash purge failed", doc.id, error);
      });
    }
    if (snap.size > 0 || sectionsSnap.size > 0) {
      await auditOwnerAction("system", "purge_expired_trash", "", {
        purged: snap.size,
        sectionsPurged: sectionsSnap.size,
      });
    }
    return null;
  });

exports.deleteLesson = functions.runWith({ timeoutSeconds: 120, memory: "512MB" })
  .https.onCall(deleteLessonHandler);
exports.deleteLessonPermanently = functions.runWith({ timeoutSeconds: 120, memory: "512MB" })
  .https.onCall(deleteLessonHandler);

async function deleteRefsInBatches(refs) {
  for (let offset = 0; offset < refs.length; offset += 400) {
    const batch = db.batch();
    refs.slice(offset, offset + 400).forEach((ref) => batch.delete(ref));
    await batch.commit();
  }
}

/**
 * نقل دفعة دروس إلى سلة المحذوفات (تستعملها عمليات الحذف التعاقبي):
 * الوثيقة + نصّها المشروح يُحفظان في السلة وملفات التخزين لا تُمسّ —
 * فحذف قسم بالخطأ لم يعد كارثة.
 */
async function trashLessonDocs(lessonDocs, actorEmail) {
  if (!lessonDocs.length) return 0;
  const transcriptSnaps = await db.getAll(
    ...lessonDocs.map((doc) => db.collection("lesson_transcripts").doc(doc.id)),
  );
  const transcriptById = new Map(transcriptSnaps.map((s) => [s.id, s]));
  const now = Date.now();
  // ⚠️ 120 لا 150: صار لكل درس أربع عمليات (سلة + درس + متن + فهرس بحثه)،
  // و150×4 = 600 تتجاوز سقف الدفعة الواحدة في Firestore (500).
  for (let offset = 0; offset < lessonDocs.length; offset += 120) {
    const batch = db.batch();
    lessonDocs.slice(offset, offset + 120).forEach((doc) => {
      const transcriptSnap = transcriptById.get(doc.id);
      batch.set(db.collection(TRASH_COLLECTION).doc(doc.id), {
        lesson: doc.data(),
        transcript: transcriptSnap && transcriptSnap.exists
          ? transcriptSnap.data()
          : null,
        deletedBy: actorEmail,
        deletedAt: admin.firestore.FieldValue.serverTimestamp(),
        deletedAtMs: now,
        purgeAfterMs: now + TRASH_RETENTION_MS,
      });
      batch.delete(doc.ref);
      batch.delete(db.collection("lesson_transcripts").doc(doc.id));
      batch.delete(db.collection(TRANSCRIPT_INDEX_COLLECTION).doc(doc.id));
    });
    await batch.commit();
  }
  return lessonDocs.length;
}

// 🗂️ سلّة الأقسام: كانت وثيقة القسم تُمحى نهائياً بينما دروسه تنجو في
// السلّة — فنقرةٌ خاطئة تُلزم المشرف بإعادة بناء القسم يدوياً ثم استعادة
// الدروس واحداً واحداً ثم نقلها إليه. الآن تُنسخ وثيقة القسم هنا بمدّة
// الاحتفاظ نفسها (TRASH_RETENTION_MS، لا رقماً مكرّراً) فتعود بمعرّفها
// الأصلي، ويعود إليها الدرس المستعاد إلى مكانه تلقائياً.
const SECTION_TRASH_COLLECTION = "deleted_sections";

/** معرّف مركّب كي لا يصطدم قسمٌ رئيسيّ بفرعيٍّ يحمل المعرّف نفسه. */
function sectionTrashId(kind, docId) {
  return `${kind}__${docId}`;
}

/**
 * نقل وثائق أقسام إلى سلّة الأقسام (لا تمسّ الدروس إطلاقاً — تلك تمرّ
 * بـtrashLessonDocs كما كانت).
 * @param {Array} sectionDocs لقطات وثائق موجودة.
 * @param {string} kind "category" أو "subcategory".
 */
async function trashSectionDocs(sectionDocs, kind, actorEmail) {
  const docs = sectionDocs.filter((doc) => doc && doc.exists);
  if (!docs.length) return 0;
  const now = Date.now();
  for (let offset = 0; offset < docs.length; offset += 400) {
    const batch = db.batch();
    docs.slice(offset, offset + 400).forEach((doc) => {
      const raw = doc.data() || {};
      const value = unwrapLegacy(raw);
      batch.set(
        db.collection(SECTION_TRASH_COLLECTION).doc(sectionTrashId(kind, doc.id)),
        {
          kind,
          docId: doc.id,
          name: cleanString(value.name || value.title, 180),
          parentCategoryId: kind === "subcategory"
            ? cleanString(value.categoryId, 180)
            : "",
          data: raw,
          deletedBy: actorEmail || "",
          deletedAt: admin.firestore.FieldValue.serverTimestamp(),
          deletedAtMs: now,
          purgeAfterMs: now + TRASH_RETENTION_MS,
        },
      );
    });
    await batch.commit();
  }
  return docs.length;
}

/** استعادة قسم محذوف: تعيد وثيقته بمعرّفها الأصلي كما كانت. */
exports.restoreDeletedSection = functions.https.onCall(async (data, context) => {
  const actor = await assertAuthorized(context);
  const entryId = requireString(data && data.entryId, "entryId", 1, 400);
  const trashRef = db.collection(SECTION_TRASH_COLLECTION).doc(entryId);
  const snap = await trashRef.get();
  if (!snap.exists) {
    throw new functions.https.HttpsError("not-found", "القسم غير موجود في السلة.");
  }
  const value = snap.data() || {};
  const kind = value.kind === "category" ? "category" : "subcategory";
  const docId = cleanString(value.docId, 180);
  const payload = value.data;
  if (!docId || !payload || typeof payload !== "object") {
    throw new functions.https.HttpsError("internal", "بيانات السلة غير مكتملة.");
  }
  const collection = kind === "category" ? "categories" : "subcategories";
  const targetRef = db.collection(collection).doc(docId);
  // ⛔ لا نكتب فوق قسم قائم: قد يكون المشرف أعاد بناءه يدوياً بعد الحذف،
  // فالكتابة الصامتة تمحو عمله. الرسالة تشرح السبب بلا لبس.
  const existing = await targetRef.get();
  if (existing.exists) {
    throw new functions.https.HttpsError(
      "already-exists",
      "يوجد قسم آخر بالمعرّف نفسه الآن — احذفه أو غيّره ثم أعد المحاولة.",
    );
  }
  // 🧷 قسم فرعي بلا رئيسه قسمٌ يتيم لا يظهر في اللوحة ولا في التطبيق
  // (كلاهما يعرض الفروع تحت رئيسيها): يُتحقّق من وجود الرئيسي أولاً،
  // وإن كان هو أيضاً في السلة أُرشد المشرف إلى استعادته قبل الفرع.
  if (kind === "subcategory") {
    const parentId = cleanString(value.parentCategoryId, 180)
      || cleanString(unwrapLegacy(payload).categoryId, 180);
    if (parentId) {
      const parentSnap = await db.collection("categories").doc(parentId).get();
      if (!parentSnap.exists) {
        const parentTrash = await db.collection(SECTION_TRASH_COLLECTION)
          .doc(sectionTrashId("category", parentId)).get();
        if (parentTrash.exists) {
          const parentName = cleanString((parentTrash.data() || {}).name, 180) || parentId;
          throw new functions.https.HttpsError(
            "failed-precondition",
            `القسم الرئيسي «${parentName}» في السلة أيضاً — استعده أولاً ثم أعد استعادة هذا الفرع.`,
          );
        }
        throw new functions.https.HttpsError(
          "failed-precondition",
          "القسم الرئيسي لهذا الفرع لم يعد موجوداً — أنشئه من جديد أولاً ثم أعد المحاولة.",
        );
      }
    }
  }
  const batch = db.batch();
  batch.set(targetRef, Object.assign({}, payload, {
    restoredAt: admin.firestore.FieldValue.serverTimestamp(),
    restoredAtMs: Date.now(),
    restoredBy: actor.email,
    // 🔁 طابع جديد كي تلتقط المزامنةُ التفاضلية القسمَ المستعاد (انظر
    // نظيره في restoreDeletedLesson).
    updatedAt: admin.firestore.FieldValue.serverTimestamp(),
  }));
  // 🔁 ومحوُ شاهد الحذف للسبب نفسه.
  batch.delete(db.collection(DELETED_IDS_COLLECTION).doc(`${collection}__${docId}`));
  batch.delete(trashRef);
  await batch.commit();
  await auditOwnerAction(actor.email, "restore_section", docId, { kind });
  return { ok: true, id: docId, kind };
});

/** الحذف النهائي لقسم من السلة (وثيقة واحدة، بلا ملفات تخزين). */
exports.purgeDeletedSection = functions.https.onCall(async (data, context) => {
  const actor = await assertAuthorized(context);
  const entryId = requireString(data && data.entryId, "entryId", 1, 400);
  const ref = db.collection(SECTION_TRASH_COLLECTION).doc(entryId);
  const snap = await ref.get();
  if (!snap.exists) return { ok: true, alreadyDeleted: true };
  await ref.delete();
  await auditOwnerAction(actor.email, "purge_section", entryId, {});
  return { ok: true, id: entryId };
});

exports.deleteSubcategoryCascade = functions.runWith({ timeoutSeconds: 540, memory: "512MB" })
  .https.onCall(async (data, context) => {
    const actor = await assertAuthorized(context);
    const subcategoryId = requireString(
      data && data.subcategoryId,
      "subcategoryId",
      1,
      180,
    );
    // الوثائق القديمة ملفوفة `{data:{...}}`: استعلام الجذر لا يراها إطلاقاً
    // بينما تفكّها unwrapLegacy عند المعالجة — فكانت دروسها وملفاتها تنجو من
    // الحذف «الكامل» وتبقى ظاهرة في التطبيق العام. لذا استعلام ثانٍ على
    // المفتاح الملفوف، والدمج في خريطة واحدة بمعرّف الوثيقة (لا تكرار).
    const [subcategorySnap, lessonsSnap, wrappedLessonsSnap] = await Promise.all([
      db.collection("subcategories").doc(subcategoryId).get(),
      db.collection("lessons").where("subcategoryId", "==", subcategoryId).get(),
      db.collection("lessons")
        .where("data.subcategoryId", "==", subcategoryId).get(),
    ]);
    const lessonMap = new Map();
    [...lessonsSnap.docs, ...wrappedLessonsSnap.docs]
      .forEach((doc) => lessonMap.set(doc.id, doc));
    const lessons = [...lessonMap.values()];
    // دروس القسم تنتقل إلى السلة (لا حذف نهائي ولا مساس بالتخزين) —
    // فحذف قسم بالخطأ قابل للتراجع درساً درساً من سلة المحذوفات.
    await trashLessonDocs(lessons, actor.email);
    // ووثيقة القسم نفسها تُنسخ إلى سلّة الأقسام قبل محوها — لتعود بمعرّفها
    // الأصلي فيرجع إليها كل درس مستعاد بلا نقلٍ يدويّ.
    if (subcategorySnap.exists) {
      await trashSectionDocs([subcategorySnap], "subcategory", actor.email);
      await subcategorySnap.ref.delete();
    }
    await auditOwnerAction(actor.email, "delete_subcategory_cascade", subcategoryId, {
      lessonsTrashed: lessons.length,
    });
    return {
      ok: true,
      id: subcategoryId,
      lessonsDeleted: lessons.length,
      cleanupPending: false,
    };
  });

exports.deleteCategoryCascade = functions.runWith({ timeoutSeconds: 540, memory: "1GB" })
  .https.onCall(async (data, context) => {
    const actor = await assertAuthorized(context);
    const categoryId = requireString(data && data.categoryId, "categoryId", 1, 180);
    // كما في حذف القسم الفرعي: الوثائق الملفوفة `{data:{...}}` لا يراها
    // استعلام الجذر، فتنجو من الحذف التعاقبي كلّه (دروساً وأقساماً وكتباً)
    // بينما تعلن اللوحة «حُذف بالكامل». لكل مجموعة استعلامان يُدمجان بالمعرّف.
    const [
      categorySnap,
      subcategoriesSnap,
      wrappedSubcategoriesSnap,
      categoryLessonsSnap,
      wrappedCategoryLessonsSnap,
      booksSnap,
      wrappedBooksSnap,
    ] = await Promise.all([
      db.collection("categories").doc(categoryId).get(),
      db.collection("subcategories").where("categoryId", "==", categoryId).get(),
      db.collection("subcategories")
        .where("data.categoryId", "==", categoryId).get(),
      db.collection("lessons").where("categoryId", "==", categoryId).get(),
      db.collection("lessons").where("data.categoryId", "==", categoryId).get(),
      db.collection("books").where("categoryId", "==", categoryId).get(),
      db.collection("books").where("data.categoryId", "==", categoryId).get(),
    ]);
    const lessonMap = new Map();
    [...categoryLessonsSnap.docs, ...wrappedCategoryLessonsSnap.docs]
      .forEach((doc) => lessonMap.set(doc.id, doc));
    const subcategoryMap = new Map();
    [...subcategoriesSnap.docs, ...wrappedSubcategoriesSnap.docs]
      .forEach((doc) => subcategoryMap.set(doc.id, doc));
    const bookMap = new Map();
    [...booksSnap.docs, ...wrappedBooksSnap.docs]
      .forEach((doc) => bookMap.set(doc.id, doc));
    const subcategories = [...subcategoryMap.values()];
    for (const subcategory of subcategories) {
      const [plainSnap, wrappedSnap] = await Promise.all([
        db.collection("lessons")
          .where("subcategoryId", "==", subcategory.id).get(),
        db.collection("lessons")
          .where("data.subcategoryId", "==", subcategory.id).get(),
      ]);
      [...plainSnap.docs, ...wrappedSnap.docs]
        .forEach((doc) => lessonMap.set(doc.id, doc));
    }
    const lessons = [...lessonMap.values()];
    const books = [...bookMap.values()];
    // دروس القسم كلّها تنتقل إلى السلة (قابلة للاستعادة)؛ الكتب تُحذف كما
    // كانت (لا واجهة لها في التطبيقين وملفاتها PDF قليلة).
    await trashLessonDocs(lessons, actor.email);
    const bookPaths = [];
    books.forEach((doc) => {
      const value = unwrapLegacy(doc.data());
      [value.storagePath, value.pdfStoragePath, value.fileUrl, value.url]
        .map(storagePathFromUrl)
        .filter(Boolean)
        .forEach((path) => bookPaths.push(path));
    });
    // القسم الرئيسيّ وكلّ فروعه يُنسخون إلى سلّة الأقسام قبل المحو، فحذفٌ
    // بالخطأ يُتراجَع عنه بالبناء نفسه لا بإعادة إنشاء يدويّة.
    await trashSectionDocs(subcategories, "subcategory", actor.email);
    if (categorySnap.exists) {
      await trashSectionDocs([categorySnap], "category", actor.email);
    }
    const refs = [
      ...books.map((doc) => doc.ref),
      ...subcategories.map((doc) => doc.ref),
    ];
    if (categorySnap.exists) refs.push(categorySnap.ref);
    await deleteRefsInBatches(refs);
    const cleanup = await deletePathsBestEffort(
      bookPaths,
      "delete_category_cascade",
      categoryId,
    );
    await auditOwnerAction(actor.email, "delete_category_cascade", categoryId, {
      subcategoriesDeleted: subcategories.length,
      lessonsTrashed: lessons.length,
      booksDeleted: books.length,
      cleanupPending: cleanup.failed.length > 0,
      cleanupJobId: cleanup.cleanupJobId,
    });
    return {
      ok: true,
      id: categoryId,
      subcategoriesDeleted: subcategories.length,
      lessonsDeleted: lessons.length,
      booksDeleted: books.length,
      cleanupPending: cleanup.failed.length > 0,
    };
  });

exports.onSubmissionCreated = functions.firestore
  .document("lesson_submissions/{id}")
  .onCreate(async (snap) => {
    const d = snap.data() || {};
    const title = cleanString(d.title, 120);
    const who = cleanString(d.submitterName, 60) || "مستمع";
    const alertTitle = "مساهمة جديدة بانتظار المراجعة";
    const alertBody = `أرسل ${who} درساً مقترحاً: «${title}».`;
    await Promise.all([
      writeAdminAlert("", alertTitle, alertBody, {
        type: "submission",
        submissionId: snap.id,
        refId: snap.id,
      }),
      // وجهة اللوحة صريحة: تبويب «المساهمات» في شاشة المراجعة.
      pushToAdmins(alertTitle, alertBody, {
        type: "submission",
        submissionId: snap.id,
        refId: snap.id,
        route: "submissions",
      }),
    ]);
    return null;
  });

exports.onSubmissionDecided = functions.firestore
  .document("lesson_submissions/{id}")
  .onUpdate(async (change) => {
    const before = change.before.data() || {};
    const after = change.after.data() || {};
    if (before.status !== "pending" || after.status === "pending") return null;
    const token = cleanString(after.fcmToken, 4096);
    const title = cleanString(after.publishedTitle || after.title, 120);
    if (after.status === "approved" || after.status === "approved_edited") {
      const edited = after.status === "approved_edited";
      const notificationTitle = edited
        ? "نُشرت مساهمتك بعد المراجعة"
        : "نُشرت مساهمتك";
      const notificationBody = `نُشر الدرس «${title}». شكراً لمساهمتك.`;
      // معرّف الدرس المنشور فقط إن وُجد فعلاً — سلسلة فارغة تُفسد التوجيه.
      const publishedLessonId = cleanString(after.publishedLessonId, 180);
      const notificationData = {
        type: "submission",
        id: change.after.id,
        refId: change.after.id,
        submissionId: change.after.id,
        result: after.status,
        route: publishedLessonId ? "lesson" : "my-submissions",
      };
      if (publishedLessonId) notificationData.lessonId = publishedLessonId;
      await Promise.all([
        clearAdminAlerts("submission", change.after.id),
        writeUserNotification(after.uid, notificationTitle, notificationBody, notificationData),
        pushToToken(token, notificationTitle, notificationBody, notificationData),
      ]);
      return null;
    }
    if (after.status === "rejected") {
      const reason = cleanString(after.rejectReason, 300);
      const notificationTitle = "نتيجة مراجعة مساهمتك";
      const notificationBody = reason
        ? `لم يُنشر «${title}»: ${reason}`
        : `لم يُنشر «${title}».`;
      // ⛳ الوجهة «مساهماتي» صراحةً: لا درس منشوراً يُفتح بعد الرفض.
      const notificationData = {
        type: "submission",
        id: change.after.id,
        refId: change.after.id,
        submissionId: change.after.id,
        result: "rejected",
        route: "my-submissions",
      };
      await Promise.all([
        clearAdminAlerts("submission", change.after.id),
        writeUserNotification(after.uid, notificationTitle, notificationBody, notificationData),
        pushToToken(token, notificationTitle, notificationBody, notificationData),
      ]);
      return null;
    }
    return null;
  });

// تنظيف التنبيهات المستهلَكة: مساهمات حُسمت، تنبيهات نسخ قديمة بلا type
// (يستحيل ربطها بمصدرها — النسخ الحالية تكتب type دائماً)، ورموز اعتماد انتهت
// صلاحيتها دون حسم.
exports.cleanupResolvedAdminAlerts = functions.https.onCall(async (_data, context) => {
  await assertAuthorized(context);
  const alertsSnap = await db.collection("admin_alerts").get();
  const parsed = alertsSnap.docs.map((doc) => {
    const value = doc.data() || {};
    const metadata = value.data || {};
    return {
      ref: doc.ref,
      type: cleanString(value.type || metadata.type, 40),
      submissionId: cleanString(
        value.refId || metadata.refId || metadata.submissionId,
        180,
      ),
      expiresAt: Number(metadata.expiresAt || 0),
    };
  });
  const stale = parsed
    .filter((item) => !item.type
      || (item.type === "owner_code" && item.expiresAt && item.expiresAt < Date.now()))
    .map((item) => item.ref);

  // التنبيهات المرتبطة بوثيقة حالة: تُحذف حين لا تعود الوثيقة معلّقة.
  const trackedKinds = [
    { type: "submission", collection: "lesson_submissions" },
    { type: "suspicious_lesson", collection: "owner_lesson_reviews" },
  ];
  for (const kind of trackedKinds) {
    const tracked = parsed.filter(
      (item) => item.type === kind.type && item.submissionId,
    );
    if (!tracked.length) continue;
    const uniqueIds = [...new Set(tracked.map((item) => item.submissionId))];
    const statusById = new Map();
    for (let offset = 0; offset < uniqueIds.length; offset += 300) {
      const refs = uniqueIds.slice(offset, offset + 300)
        .map((id) => db.collection(kind.collection).doc(id));
      const docs = await db.getAll(...refs);
      docs.forEach((doc) => {
        statusById.set(doc.id, doc.exists ? cleanString((doc.data() || {}).status, 40) : "missing");
      });
    }
    tracked
      .filter((item) => statusById.get(item.submissionId) !== "pending")
      .forEach((item) => stale.push(item.ref));
  }
  for (let offset = 0; offset < stale.length; offset += 400) {
    const batch = db.batch();
    stale.slice(offset, offset + 400).forEach((ref) => batch.delete(ref));
    await batch.commit();
  }
  return { ok: true, deleted: stale.length };
});

// إشعار خاص بأعضاء مجموعة الإدارة الموثقين، مع احترام الكتم واستبعاد المرسل.
exports.onAdminChatMessageCreated = functions.firestore
  .document("admin_chat_messages/{id}")
  .onCreate(async (snap) => {
    const value = snap.data() || {};
    if (value.deleted === true) return null;
    const senderId = cleanString(value.senderId, 180);
    const senderName = cleanString(value.senderName || "عضو", 100);
    const typeLabels = {
      image: "صورة",
      video: "فيديو",
      audio: "مقطع صوتي",
      voice: "رسالة صوتية",
      file: "ملف",
    };
    const messageType = cleanString(value.type, 20);
    const preview = cleanString(value.text, 160)
      || typeLabels[messageType]
      || "رسالة جديدة";
    await pushToAdminsFiltered(
      `رسالة من ${senderName}`,
      preview,
      { type: "admin_chat", messageId: snap.id, senderId },
      { excludeUid: senderId, respectChatMute: true },
    );
    return null;
  });

// إشعار المحادثة الفرديّة: يصل الطرف الآخر وحده (لا المجموعة ولا غيرهما).
exports.onAdminDmMessageCreated = functions.firestore
  .document("admin_dm_threads/{threadId}/messages/{msgId}")
  .onCreate(async (snap, context) => {
    const value = snap.data() || {};
    if (value.deleted === true) return null;
    // سجلّ المكالمة رسالةٌ في الثريد لكنّه ليس «رسالة خاصّة» — إشعاره يصل
    // بعد كلّ مكالمة (حتى الفائتة) فيبدو تكراراً مربكاً بلا فائدة.
    if (cleanString(value.type, 20) === "call") return null;
    const senderId = cleanString(value.senderId, 180);
    const senderName = cleanString(value.senderName || "مشرف", 100);
    const threadId = cleanString(context.params.threadId, 400);
    // طرفا المحادثة من معرّفها الحتمي (uidA__uidB) ومن وثيقتها احتياطاً.
    let members = threadId.split("__").filter(Boolean);
    if (members.length !== 2) {
      const threadSnap = await db.collection("admin_dm_threads")
        .doc(threadId).get();
      const data = threadSnap.data() || {};
      members = Array.isArray(data.members) ? data.members.map(String) : [];
    }
    const target = members.find((uid) => uid && uid !== senderId);
    if (!target) return null;

    const typeLabels = {
      image: "صورة",
      video: "فيديو",
      audio: "مقطع صوتي",
      voice: "رسالة صوتيّة",
      file: "ملف",
    };
    const messageType = cleanString(value.type, 20);
    const preview = cleanString(value.text, 160)
      || typeLabels[messageType]
      || "رسالة خاصّة";

    // الرمز المستهدف مباشرة: الوثائق مفهرسة بالـuid، فقراءة المجموعة كاملة
    // كانت تكلّف N قراءة لإشعار شخص واحد. وكتم «إشعارات المجموعة» لا يشمل
    // الخاص (نمط واتساب): الرسائل الشخصية تصل دائماً.
    const targetRef = db.collection("admin_device_tokens").doc(target);
    // كل أجهزة المستهدف: الرمز القديم في الوثيقة الأمّ ∪ المجموعة الفرعية
    // devices، بلا تكرار — التخويل يُفحص مرّة واحدة على هويّة الوثيقة الأمّ.
    const [targetDoc, devicesSnap] = await Promise.all([
      targetRef.get(),
      targetRef.collection("devices").get().catch(() => ({ docs: [] })),
    ]);
    const tokens = [];
    if (targetDoc.exists) {
      const value = targetDoc.data() || {};
      const email = normalizeEmail(value.email);
      const token = cleanString(value.token, 4096);
      // التخويل بالبريد وحده: خلوّ الأمّ من token (خرج من جهاز مثلاً)
      // لا يمنع وصول الرسائل الخاصّة لبقيّة أجهزته في devices.
      if (email) {
        let authorized = email === OWNER_EMAIL;
        if (!authorized) {
          const adminSnap = await db.collection(ADMINS_COLLECTION).doc(email).get();
          const data = adminSnap.data() || {};
          authorized = adminSnap.exists
            && data.role === "supervisor"
            && data.blocked !== true;
        }
        if (authorized) {
          const identity = {
            email,
            uid: cleanString(value.uid || targetDoc.id, 180),
            chatMuted: value.chatMuted === true,
          };
          const seen = new Set();
          if (token) {
            seen.add(token);
            tokens.push(Object.assign(
              { ref: targetDoc.ref, token, isParent: true }, identity,
            ));
          }
          for (const deviceDoc of devicesSnap.docs) {
            const deviceToken = cleanString((deviceDoc.data() || {}).token, 4096);
            if (!deviceToken || seen.has(deviceToken)) continue;
            seen.add(deviceToken);
            tokens.push(Object.assign({ ref: deviceDoc.ref, token: deviceToken }, identity));
          }
        }
      }
    }
    return sendToAdminTargets(
      tokens,
      `رسالة خاصّة من ${senderName}`,
      preview,
      {
        type: "admin_dm",
        threadId,
        messageId: snap.id,
        senderId,
        senderName,
      },
    );
  });

// ─── نظام المراجعة السرية للدروس المشبوهة (للمالك فقط) ─────────────
//
// أُعيدت معايرته بعد بلاغ المالك (2026-07-30): «الفحص الشامل يُظهر 67 درساً
// مشبوهاً ولا شيء فيها فعلاً». سبب ذلك أن المعايير القديمة كانت تُطابق
// **مفردات** لا **سياقات**، وتخلط بين إشارة محتوى وإشارة سلامة بيانات:
//   • «القاعدة» كانت في قائمة التنظيمات بدرجة 5، وwholeWord يسمح بسابقة
//     «ال» — فكل درس اسمه «القاعدة الفقهية» أو «القاعدة الأولى» من سلسلة
//     «القواعد الأربع» يُفتح له بلاغ خطورة عالية. أكبر مصدر للضجيج.
//   • «سفك الدماء» بدرجة 4 = عتبة التنبيه تماماً، وهي عبارة خطبٍ تُحرّمه.
//   • ألفاظ «القتل/سلاح/مخدرات/تكفير» هي موضوع الدروس الفقهية نفسه، وكانت
//     تكفي درجتها (3) لتجاوز العتبة مع أيّ ملاحظة إدارية عابرة (تكرار عنوان).
//
// المبدأ الجديد — فئتان لا فئة واحدة:
//   1) إشارات محتوى/أمان: هي وحدها ما يجوز أن يفتح مراجعة بذاته، وتُطابَق
//      في سياق لا كمفردة (تنظيم القاعدة ≠ القاعدة، صنع قنبلة ≠ قنبلة).
//   2) إشارات سلامة بيانات (رابط ناقص، قسم محذوف، تكرار ملف…): لا تفتح
//      مراجعة بمفردها أبداً؛ تحتاج قرينتين مستقلّتين ومجموعاً عالياً.
// وحُذف ما لا معنى له في مكتبة دروس صوتية: كشف «القرصنة»، وكشف «معلومات
// الاتصال» (نمطه `(?:\+?\d[\s-]?){9,}` كان يلتقط أيّ تاريخين أو ترقيم دروس).

/// حدّ كلمة عربي/لاتيني (\b اللاتينية لا تعمل مع العربية).
/// تُسمح السوابق الملتصقة الشائعة وحدها: العطف (و/ف) ثم الجرّ (ب/ك/ل)،
/// لأن منعها كلياً فتح تهرّباً بحرف واحد — «وداعش» و«فاقتلوا» و«لصنع قنبلة»
/// كانت تُفلت من كل أنماط المحتوى. ويبقى **منع «ال» التعريف الملتصقة**
/// (فالألف ليست ضمن السوابق المسموحة)، وهي وحدها سبب إيجابيات «القاعدة
/// الفقهية»؛ والعبارات أدناه مركّبة فلا يعود بها الضجيج.
const phrase = (alternatives) =>
  new RegExp(
    `(?<![\\p{L}\\p{N}])(?:و|ف)?(?:ب|ك|ل)?(?:${alternatives})(?![\\p{L}\\p{N}])`,
    "iu",
  );

// إشارات المحتوى: قاطعة بدرجة 5 (تفتح مراجعة وحدها)، ومرجّحة بدرجة 4
// (تحتاج قرينة أخرى) — لأن صيغة الأمر قد ترد داخل نقل نصّ أو ردٍّ عليه.
const CONTENT_PATTERNS = [
  {
    pattern: /<script|javascript:|data:text\/html|<iframe/iu,
    reason: "شفرة أو رابط غير آمن داخل البيانات",
    score: 5,
  },
  {
    // أسماء التنظيمات في سياقها المركّب وحده.
    pattern: phrase(
      "داعش|تنظيم\\s+القاعدة|تنظيم\\s+الدولة|جبهة\\s+النصرة|"
      + "الدولة\\s+الإسلامية\\s+في\\s+العراق",
    ),
    reason: "إشارة صريحة إلى تنظيم متطرف",
    score: 5,
  },
  {
    // تعليمات تصنيع لا مجرّد ذكر: «قنبلة» و«تفجير» تردان في السيرة والتاريخ.
    pattern: phrase(
      "(?:صنع|تصنيع|تركيب|إعداد|تحضير|طريقة|كيفية)\\s+(?:ال)?"
      + "(?:قنبلة|قنابل|متفجرات|عبوة\\s+ناسفة|حزام\\s+ناسف|سلاح\\s+ناري)",
    ),
    reason: "ما يشبه تعليمات تصنيع متفجرات أو سلاح",
    score: 5,
  },
  {
    // تحريض بصيغة الأمر على فئة — لا مجرّد ذكر «القتل» في باب فقهي.
    // درجته 5 لا 4: بدرجة 4 كان التحريض الصريح لا يبلغ أيّ عتبة وحده، بل
    // يسلك الدرس مسار «دون العتبة» فيُغلق بلاغه السابق تلقائياً — وهو أخطر
    // ما يمكن أن يفعله كاشف. «قتلوا» ضمن البدائل كي تُطابق «فاقتلوا/واقتلوا».
    pattern: phrase(
      "(?:اقتلوا|اقتل|قتلوا|اذبحوا|اذبح|فجّروا|فجروا)\\s+(?:كلَّ|كل|جميع|من)"
      + "|يجب\\s+قتلُ?\\s+(?:كلَّ|كل|جميع|من)",
    ),
    reason: "صيغة أمر صريحة بالقتل موجَّهة إلى فئة",
    score: 5,
  },
];

/// درجة المحتوى التي تفتح مراجعة بذاتها.
const CONTENT_ALERT_THRESHOLD = 5;
/// مجموع الدرجات الذي يفتح مراجعة عند وجود إشارة محتوى مرجّحة مع قرينة.
const MIXED_ALERT_THRESHOLD = 6;
/// مجموع درجات سلامة البيانات — ولا يكفي إلا مع قرينتين مستقلّتين فأكثر.
const HYGIENE_ALERT_THRESHOLD = 6;
const HYGIENE_MIN_SIGNALS = 2;

/// إشارة سلامة بيانات: لا تفتح مراجعة بمفردها مهما بلغت درجتها.
function addHygieneSignal(result, reason, score) {
  if (!reason || result.reasons.includes(reason)) return;
  result.reasons.push(reason);
  result.hygieneScore += score;
  result.hygieneSignals += 1;
}

/// إشارة محتوى/أمان.
function addContentSignal(result, reason, score) {
  if (!reason || result.reasons.includes(reason)) return;
  result.reasons.push(reason);
  result.contentScore += score;
}

/// القرار النهائي: هل يستحق هذا الدرس إزعاج المالك؟
function shouldOpenReview(result) {
  if (!result.reasons.length) return false;
  if (result.contentScore >= CONTENT_ALERT_THRESHOLD) return true;
  const total = result.contentScore + result.hygieneScore;
  if (result.contentScore > 0 && total >= MIXED_ALERT_THRESHOLD) return true;
  return result.hygieneSignals >= HYGIENE_MIN_SIGNALS
    && result.hygieneScore >= HYGIENE_ALERT_THRESHOLD;
}

function lessonModerationFields(raw) {
  const d = unwrapLegacy(raw);
  const title = cleanString(d.title || d.name, 300);
  return {
    title,
    normalizedTitle: cleanString(d.normalizedTitle, 300)
      || title.toLocaleLowerCase("ar").replace(/\s+/g, " ").trim(),
    description: cleanString(d.description || d.note || d.text, 2000),
    audioUrl: cleanString(d.audioUrl || d.url, 2500),
    storagePath: cleanString(d.storagePath || d.audioStoragePath, 700),
    categoryId: cleanString(d.categoryId, 180),
    subcategoryId: cleanString(d.subcategoryId, 180),
    publishNotified: d.publishNotified === true,
    addedBy: cleanString(d.addedBy, 180),
    createdByEmail: normalizeEmail(d.createdByEmail || d.addedBy),
    createdByUid: cleanString(d.createdByUid, 180),
    updatedByEmail: normalizeEmail(d.updatedByEmail),
    updatedByUid: cleanString(d.updatedByUid, 180),
  };
}

/// مقتطف سياق حول موضع المطابقة — يُعرض للمالك دليلاً لا تخميناً.
function evidenceExcerpt(text, index, length) {
  const start = Math.max(0, index - 25);
  const end = Math.min(text.length, index + length + 25);
  const prefix = start > 0 ? "…" : "";
  const suffix = end < text.length ? "…" : "";
  return `${prefix}${text.slice(start, end).trim()}${suffix}`;
}

function inspectLesson(raw) {
  const fields = lessonModerationFields(raw);
  const result = {
    fields,
    reasons: [],
    contentScore: 0,
    hygieneScore: 0,
    hygieneSignals: 0,
    // البصمة تستثني publishNotified: قلبُه علامة نشر إجرائية (يكتبها
    // onLessonCreated والمجدول) لا تغييراً في المحتوى، وإدراجه كان يعيد
    // فتح المراجعة وتنبيه المالك مرتين لنفس الدرس.
    fingerprint: hashId(
      JSON.stringify({ ...fields, publishNotified: undefined }),
    ),
  };
  const sources = [
    ["العنوان", fields.title],
    ["الوصف", fields.description],
  ];
  CONTENT_PATTERNS.forEach((item) => {
    for (const [label, text] of sources) {
      if (!text) continue;
      const match = item.pattern.exec(text);
      if (match) {
        addContentSignal(
          result,
          `${item.reason} — وردت عبارة «${match[0]}» في ${label}: `
          + `"${evidenceExcerpt(text, match.index, match[0].length)}"`,
          item.score,
        );
        break; // يكفي دليل واحد لكل نمط.
      }
    }
  });
  if (!fields.title) {
    addHygieneSignal(result, "الدرس بلا عنوان إطلاقاً", 3);
  } else if (fields.title.length < 3) {
    addHygieneSignal(
      result,
      `العنوان أقصر من أن يكون دالاً: «${fields.title}»`,
      2,
    );
  }
  // التطويل العربي (ـ) حرفٌ زخرفيّ مشروع في العناوين، فلا يُحسب تكراراً
  // شاذاً؛ والحدّ رُفع إلى سبعة أحرف متطابقة كي لا يُلتقط المدّ المكتوب.
  if (/(?!ـ)([\p{L}\p{N}])\1{6,}/u.test(fields.title)) {
    addHygieneSignal(result, "العنوان يحوي تكراراً غير طبيعي لنفس الحرف", 2);
  }
  if (!fields.audioUrl) {
    addHygieneSignal(result, "الدرس بلا رابط صوت", 3);
  } else {
    try {
      const host = new URL(fields.audioUrl).hostname.toLowerCase();
      const approved = host === "firebasestorage.googleapis.com"
        || host.endsWith(".googleapis.com")
        || host.endsWith(".firebasestorage.app")
        || host === "storage.cloud.google.com"
        || host.endsWith("res.cloudinary.com")
        || host.endsWith("archive.org");
      if (!approved) {
        addHygieneSignal(
          result,
          `مصدر الصوت خارج تخزين التطبيق المعتمد: ${host}`,
          2,
        );
      }
    } catch (_) {
      addHygieneSignal(result, "رابط الصوت ليس رابطاً صالحاً أصلاً", 3);
    }
  }
  return result;
}

async function recordSuspiciousLesson(
  lessonId,
  raw,
  source,
  notifyOwner,
  extraReasons = [],
  preloaded = null,
) {
  const result = inspectLesson(raw);
  // الأسباب الخارجية (من مسارات أخرى) إدارية بطبيعتها، فتُعامَل معاملة
  // إشارات سلامة البيانات: لا تفتح مراجعة بمفردها.
  extraReasons.forEach((reason) => addHygieneSignal(result, reason, 2));
  const checks = [];
  // الدروس المكرّرة: عنوانٌ متطابق وحده أمر طبيعي تماماً في السلاسل
  // («الدرس الأول»، «القاعدة الأولى»)، فدرجته 1 فقط؛ أمّا تطابق الملف
  // المخزَّن فأقوى دلالة على تكرار حقيقي.
  if (preloaded) {
    if (result.fields.categoryId
        && !preloaded.categoryIds.has(result.fields.categoryId)) {
      addHygieneSignal(result, "القسم الرئيسي المُشار إليه غير موجود في القاعدة", 3);
    }
    if (result.fields.subcategoryId) {
      if (!preloaded.subcategoryParents.has(result.fields.subcategoryId)) {
        addHygieneSignal(result, "القسم الفرعي المُشار إليه غير موجود في القاعدة", 3);
      } else {
        const parent = preloaded.subcategoryParents.get(result.fields.subcategoryId);
        if (result.fields.categoryId && parent && parent !== result.fields.categoryId) {
          addHygieneSignal(result, "القسم الفرعي المحدد لا يتبع القسم الرئيسي المحدد", 3);
        }
      }
    }
    if (result.fields.normalizedTitle
        && (preloaded.titleCounts.get(result.fields.normalizedTitle) || 0) > 1) {
      addHygieneSignal(result, "العنوان مطابق حرفياً لدرس آخر موجود", 1);
    }
    if (result.fields.audioUrl
        && (preloaded.audioUrlCounts.get(result.fields.audioUrl) || 0) > 1) {
      addHygieneSignal(result, "رابط الصوت نفسه مستخدم في درس آخر", 2);
    }
    if (result.fields.storagePath
        && (preloaded.storagePathCounts.get(result.fields.storagePath) || 0) > 1) {
      addHygieneSignal(result, "ملف الصوت المخزَّن نفسه مستخدم في درس آخر", 3);
    }
  } else if (result.fields.categoryId) {
    checks.push(
      db.collection("categories").doc(result.fields.categoryId).get()
        .then((snap) => {
          if (!snap.exists) {
            addHygieneSignal(result, "القسم الرئيسي المُشار إليه غير موجود في القاعدة", 3);
          }
        }),
    );
  }
  if (!preloaded && result.fields.subcategoryId) {
    checks.push(
      db.collection("subcategories").doc(result.fields.subcategoryId).get()
        .then((snap) => {
          if (!snap.exists) {
            addHygieneSignal(result, "القسم الفرعي المُشار إليه غير موجود في القاعدة", 3);
          } else {
            const parent = cleanString((snap.data() || {}).categoryId, 180);
            if (result.fields.categoryId && parent && parent !== result.fields.categoryId) {
              addHygieneSignal(result, "القسم الفرعي المحدد لا يتبع القسم الرئيسي المحدد", 3);
            }
          }
        }),
    );
  }
  if (!preloaded && result.fields.normalizedTitle) {
    checks.push(
      db.collection("lessons")
        .where("normalizedTitle", "==", result.fields.normalizedTitle)
        .limit(3)
        .get()
        .then((snap) => {
          if (snap.docs.some((doc) => doc.id !== lessonId)) {
            addHygieneSignal(result, "العنوان مطابق حرفياً لدرس آخر موجود", 1);
          }
        }),
    );
  }
  if (!preloaded && result.fields.audioUrl) {
    checks.push(
      db.collection("lessons").where("audioUrl", "==", result.fields.audioUrl)
        .limit(3).get().then((snap) => {
          if (snap.docs.some((doc) => doc.id !== lessonId)) {
            addHygieneSignal(result, "رابط الصوت نفسه مستخدم في درس آخر", 2);
          }
        }),
    );
  }
  if (!preloaded && result.fields.storagePath) {
    checks.push(
      db.collection("lessons").where("storagePath", "==", result.fields.storagePath)
        .limit(3).get().then((snap) => {
          if (snap.docs.some((doc) => doc.id !== lessonId)) {
            addHygieneSignal(result, "ملف الصوت المخزَّن نفسه مستخدم في درس آخر", 3);
          }
        }),
    );
  }
  await Promise.all(checks);
  result.riskScore = result.contentScore + result.hygieneScore;
  const ref = db.collection("owner_lesson_reviews").doc(lessonId);
  const existing = await ref.get();
  const old = existing.data() || {};
  // دون العتبة = ليس شبهة تستحق مراجعة المالك. يشمل هذا المراجعات المعلّقة
  // من منطق قديم أشد حساسية — تُغلق تلقائياً ويُمسح تنبيهها مهما كانت بصمتها.
  // («flagged» حالة قديمة يعدّها التطبيق معلّقة أيضاً، فتُغلق معها.)
  if (!shouldOpenReview(result)) {
    if (existing.exists && (old.status === "pending" || old.status === "flagged")) {
      await ref.update({
        status: "auto_cleared",
        resolution: result.reasons.length
          ? "below_alert_threshold"
          : "no_longer_flagged",
        resolvedAt: admin.firestore.FieldValue.serverTimestamp(),
      });
      await clearAdminAlerts("suspicious_lesson", lessonId);
    }
    return false;
  }
  if (old.status === "verified" && old.fingerprint === result.fingerprint) {
    return false;
  }
  const unchangedPending = old.status === "pending"
    && old.fingerprint === result.fingerprint;
  await ref.set({
    lessonId,
    lessonTitle: result.fields.title,
    reasons: result.reasons,
    riskScore: result.riskScore,
    contentScore: result.contentScore,
    hygieneScore: result.hygieneScore,
    riskLevel: result.contentScore >= CONTENT_ALERT_THRESHOLD
      ? "high"
      : result.contentScore > 0 ? "medium" : "low",
    status: "pending",
    fingerprint: result.fingerprint,
    source,
    lessonSnapshot: result.fields,
    detectedAt: admin.firestore.FieldValue.serverTimestamp(),
    detectedAtMs: Date.now(),
    updatedAt: admin.firestore.FieldValue.serverTimestamp(),
  }, { merge: true });
  if (notifyOwner && !unchangedPending) {
    const title = "تنبيه خاص: درس يحتاج مراجعتك";
    const body = `«${result.fields.title || lessonId}» — ${result.reasons[0]}`;
    await Promise.all([
      writeAdminAlert(OWNER_EMAIL, title, body, {
        type: "suspicious_lesson",
        reviewId: lessonId,
        lessonId,
      }),
      pushToAdmins(title, body, {
        type: "suspicious_lesson",
        reviewId: lessonId,
        lessonId,
        refId: lessonId,
      }, true),
    ]);
  }
  return true;
}

exports.onLessonSuspicionCreated = functions.firestore
  .document("lessons/{lessonId}")
  .onCreate((snap, context) => {
    const value = snap.data() || {};
    // ♻️ الدرس المستعاد من السلة ليس محتوى جديداً: الفحص يجري كاملاً
    // (فيبقى سجلّ المراجعة صحيحاً ومحدَّثاً) لكن بلا تنبيه فوريّ للمالك،
    // فقد رأى هذا البلاغ نفسه يوم أُضيف الدرس أوّل مرّة. الوسم قصير العمر
    // كي لا يُسكت الكشف عن أي إضافة لاحقة بالمعرّف نفسه.
    const restoredAtMs = Number(value.restoredAtMs || 0);
    const justRestored = restoredAtMs > 0
      && Date.now() - restoredAtMs < 10 * 60 * 1000;
    return recordSuspiciousLesson(
      context.params.lessonId,
      value,
      justRestored ? "restored" : "created",
      !justRestored,
    );
  });

exports.onLessonSuspicionUpdated = functions.firestore
  .document("lessons/{lessonId}")
  .onUpdate(async (change, context) => {
    const before = inspectLesson(change.before.data());
    const after = inspectLesson(change.after.data());
    if (before.fingerprint === after.fingerprint) return null;
    return recordSuspiciousLesson(
      context.params.lessonId,
      change.after.data(),
      "updated",
      true,
    );
  });

exports.scanSuspiciousLessons = functions.runWith({ timeoutSeconds: 540, memory: "512MB" })
  .https.onCall(async (_data, context) => {
    await assertOwner(context);
    const [snap, categoriesSnap, subcategoriesSnap] = await Promise.all([
      db.collection("lessons").get(),
      db.collection("categories").get(),
      db.collection("subcategories").get(),
    ]);
    const preloaded = {
      categoryIds: new Set(categoriesSnap.docs.map((doc) => doc.id)),
      subcategoryParents: new Map(subcategoriesSnap.docs.map((doc) => [
        doc.id,
        cleanString(unwrapLegacy(doc.data()).categoryId, 180),
      ])),
      titleCounts: new Map(),
      audioUrlCounts: new Map(),
      storagePathCounts: new Map(),
    };
    const increment = (map, key) => {
      if (key) map.set(key, (map.get(key) || 0) + 1);
    };
    snap.docs.forEach((doc) => {
      const fields = lessonModerationFields(doc.data());
      increment(preloaded.titleCounts, fields.normalizedTitle);
      increment(preloaded.audioUrlCounts, fields.audioUrl);
      increment(preloaded.storagePathCounts, fields.storagePath);
    });
    let suspicious = 0;
    const concurrency = 20;
    for (let offset = 0; offset < snap.docs.length; offset += concurrency) {
      const results = await Promise.all(
        snap.docs.slice(offset, offset + concurrency).map((doc) =>
          recordSuspiciousLesson(
            doc.id,
            doc.data(),
            "manual_scan",
            false,
            [],
            preloaded,
          )),
      );
      suspicious += results.filter(Boolean).length;
    }
    // مراجعات معلّقة لدروس لم تعد موجودة (حُذفت من مسار آخر): لا يمكن
    // للمالك حسمها من الشاشة لأن الدرس مفقود، فتبقى معلّقة إلى الأبد.
    const lessonIds = new Set(snap.docs.map((doc) => doc.id));
    const pendingSnap = await db.collection("owner_lesson_reviews")
      .where("status", "==", "pending").get();
    const orphans = pendingSnap.docs.filter((doc) => {
      const value = doc.data() || {};
      return !lessonIds.has(cleanString(value.lessonId, 180) || doc.id);
    });
    for (let offset = 0; offset < orphans.length; offset += 400) {
      const batch = db.batch();
      orphans.slice(offset, offset + 400).forEach((doc) => {
        batch.update(doc.ref, {
          status: "auto_cleared",
          resolution: "lesson_missing",
          resolvedAt: admin.firestore.FieldValue.serverTimestamp(),
        });
      });
      await batch.commit();
    }
    await auditOwnerAction(OWNER_EMAIL, "scan_suspicious_lessons", "", {
      scanned: snap.size,
      suspicious,
      orphansCleared: orphans.length,
    });
    if (suspicious) {
      await pushToAdmins(
        "اكتمل فحص الدروس",
        `تم فحص ${snap.size} درساً والعثور على ${suspicious} درساً يحتاج المراجعة.`,
        { type: "suspicious_scan", suspicious },
        true,
      );
    }
    // `flagged` مرادف متوافق مع نسخ اللوحة القديمة التي تقرأ هذا المفتاح.
    return {
      ok: true,
      scanned: snap.size,
      suspicious,
      flagged: suspicious,
      orphansCleared: orphans.length,
    };
  });

exports.resolveSuspiciousLesson = functions.runWith({ timeoutSeconds: 120, memory: "512MB" })
  .https.onCall(async (data, context) => {
    const actorEmail = await assertOwner(context);
    const reviewId = requireString(data && data.reviewId, "reviewId", 1, 180);
    const lessonId = requireString(data && data.lessonId, "lessonId", 1, 180);
    const action = cleanString(data && data.action, 20);
    if (!["verified", "delete"].includes(action)) {
      throw new functions.https.HttpsError("invalid-argument", "الإجراء غير صالح.");
    }
    const reviewRef = db.collection("owner_lesson_reviews").doc(reviewId);
    const lessonRef = db.collection("lessons").doc(lessonId);
    const [reviewSnap, lessonSnap] = await Promise.all([
      reviewRef.get(),
      lessonRef.get(),
    ]);
    if (!reviewSnap.exists || reviewSnap.data().lessonId !== lessonId) {
      throw new functions.https.HttpsError("not-found", "سجل المراجعة غير موجود.");
    }
    let cleanup = { failed: [], cleanupJobId: "" };
    if (action === "verified") {
      if (!lessonSnap.exists) {
        throw new functions.https.HttpsError("not-found", "الدرس غير موجود.");
      }
      const batch = db.batch();
      batch.update(reviewRef, {
        status: "verified",
        resolvedBy: actorEmail,
        resolvedAt: admin.firestore.FieldValue.serverTimestamp(),
        resolution: "verified",
      });
      batch.update(lessonRef, {
        moderationStatus: "verified",
        moderationVerifiedBy: actorEmail,
        moderationVerifiedAt: admin.firestore.FieldValue.serverTimestamp(),
      });
      await batch.commit();
    } else {
      const paths = lessonSnap.exists
        ? lessonStoragePaths(unwrapLegacy(lessonSnap.data()))
        : [];
      const batch = db.batch();
      if (lessonSnap.exists) batch.delete(lessonRef);
      batch.update(reviewRef, {
        status: "deleted",
        resolvedBy: actorEmail,
        resolvedAt: admin.firestore.FieldValue.serverTimestamp(),
        resolution: "delete",
      });
      await batch.commit();
      cleanup = await deletePathsBestEffort(
        paths,
        "delete_suspicious_lesson",
        lessonId,
      );
    }
    // اتُّخذ القرار — تنبيه «درس يحتاج مراجعتك» يختفي من تنبيهات المالك.
    await clearAdminAlerts("suspicious_lesson", lessonId);
    await auditOwnerAction(actorEmail, `suspicious_${action}`, lessonId, {
      reviewId,
      cleanupPending: cleanup.failed.length > 0,
      cleanupJobId: cleanup.cleanupJobId,
    });
    return {
      ok: true,
      action,
      lessonId,
      cleanupPending: cleanup.failed.length > 0,
      cleanupJobId: cleanup.cleanupJobId,
    };
  });

/// مسح تنبيهات «درس يحتاج مراجعتك» لمجموعة دروس بمرور واحد على
/// admin_alerts — بدل مرور كامل لكلّ درس كما في clearAdminAlerts.
async function clearSuspicionAlertsFor(lessonIds) {
  const wanted = new Set(lessonIds.filter(Boolean));
  if (!wanted.size) return 0;
  const snap = await db.collection("admin_alerts").get();
  const refs = snap.docs.filter((doc) => {
    const value = doc.data() || {};
    const metadata = value.data || {};
    if (cleanString(value.type || metadata.type, 40) !== "suspicious_lesson") {
      return false;
    }
    const refId = cleanString(
      value.refId || metadata.refId || metadata.lessonId || metadata.id,
      180,
    );
    return wanted.has(refId);
  }).map((doc) => doc.ref);
  for (let offset = 0; offset < refs.length; offset += 400) {
    const batch = db.batch();
    refs.slice(offset, offset + 400).forEach((ref) => batch.delete(ref));
    await batch.commit();
  }
  return refs.length;
}

/// حسم جماعي: «اعتماد الكل» في شاشة المراجعة. يعتمد فقط — الحذف يبقى
/// فردياً بقرار واعٍ لكل درس (لا حذف جماعي أبداً).
/// `reviewIds` فارغة تعني كل المراجعات المعلّقة.
exports.bulkResolveSuspiciousLessons = functions
  .runWith({ timeoutSeconds: 540, memory: "512MB" })
  .https.onCall(async (data, context) => {
    const actorEmail = await assertOwner(context);
    const action = cleanString(data && data.action, 20) || "verified";
    if (action !== "verified") {
      throw new functions.https.HttpsError(
        "invalid-argument",
        "الإجراء الجماعي المتاح هو الاعتماد فقط.",
      );
    }
    const requested = Array.isArray(data && data.reviewIds)
      ? [...new Set(
        data.reviewIds.map((item) => cleanString(item, 180)).filter(Boolean),
      )].slice(0, 1000)
      : [];
    const reviewsRef = db.collection("owner_lesson_reviews");
    let docs = [];
    if (requested.length) {
      for (let offset = 0; offset < requested.length; offset += 200) {
        const refs = requested.slice(offset, offset + 200)
          .map((id) => reviewsRef.doc(id));
        const snaps = await db.getAll(...refs);
        docs = docs.concat(snaps.filter((item) => item.exists));
      }
    } else {
      const snap = await reviewsRef.where("status", "==", "pending").get();
      docs = snap.docs;
    }
    // لا يُحسم إلا ما هو معلّق فعلاً («flagged» حالة قديمة معلّقة أيضاً).
    const targets = docs
      .filter((doc) => {
        const status = cleanString((doc.data() || {}).status, 40) || "pending";
        return status === "pending" || status === "flagged";
      })
      .map((doc) => ({
        ref: doc.ref,
        lessonId: cleanString((doc.data() || {}).lessonId, 180) || doc.id,
      }));
    if (!targets.length) {
      return { ok: true, verified: 0, missingLessons: 0, alertsCleared: 0 };
    }
    let verified = 0;
    let missingLessons = 0;
    for (let offset = 0; offset < targets.length; offset += 200) {
      const chunk = targets.slice(offset, offset + 200);
      const lessonRefs = chunk.map(
        (item) => db.collection("lessons").doc(item.lessonId),
      );
      const lessonSnaps = await db.getAll(...lessonRefs);
      const batch = db.batch();
      chunk.forEach((item, index) => {
        const lessonExists = lessonSnaps[index] && lessonSnaps[index].exists;
        batch.update(item.ref, {
          status: "verified",
          resolvedBy: actorEmail,
          resolvedAt: admin.firestore.FieldValue.serverTimestamp(),
          resolution: lessonExists ? "verified_bulk" : "lesson_missing",
        });
        if (lessonExists) {
          batch.update(lessonRefs[index], {
            moderationStatus: "verified",
            moderationVerifiedBy: actorEmail,
            moderationVerifiedAt: admin.firestore.FieldValue.serverTimestamp(),
          });
        } else {
          missingLessons += 1;
        }
      });
      await batch.commit();
      verified += chunk.length;
    }
    const alertsCleared = await clearSuspicionAlertsFor(
      targets.map((item) => item.lessonId),
    );
    await auditOwnerAction(actorEmail, "suspicious_bulk_verified", "", {
      verified,
      missingLessons,
      alertsCleared,
      scope: requested.length ? "selection" : "all_pending",
    });
    return { ok: true, verified, missingLessons, alertsCleared };
  });

// ─── الإشعار اليدوي من تطبيق الإدارة ────────────────────────────────
exports.sendNotification = functions.https.onCall(async (data, context) => {
  const actor = await assertAuthorized(context);
  const title = cleanString(data && data.title, 80);
  const body = cleanString(data && data.body, 500);
  if (!title && !body) {
    throw new functions.https.HttpsError("invalid-argument", "العنوان أو النص مطلوب.");
  }
  // بلا عنوان من اللوحة: يسقط إلى اسم التطبيق «منبر ادكصهك» داخل
  // pushToTopic — لا إلى كلمة «إشعار» العامّة.
  const messageId = await pushToTopic(title, body, { type: "manual" });
  await auditOwnerAction(actor.email, "send_notification", "", {
    title,
    bodyLength: body.length,
  });
  return { ok: true, messageId };
});

// ⭐ إنهاء تمييز الدروس التي انقضت مدّتها. التطبيق العام يُخفيها فوراً
// بترشيح محلّي، وهذه تُنظّف الراية في القاعدة كي يستقيم المصدر ولا تظهر
// عند النسخ القديمة التي لا تعرف featuredUntil.
//
// ⏳ وقبل السقوط بساعات: إنذار موجَّه إلى مَن أضاف الدرس («مدّده أو دعه
// يسقط») — التمييز كان يسقط صامتاً فيفاجَأ صاحبه باختفاء درسه من
// «مختارات المنبر». الإنذار مرّة واحدة لكل مدّة (وسم featuredExpiryWarnedFor
// يحمل قيمة featuredUntil نفسها)، فتمديد المدّة يستحق إنذاراً جديداً.
const FEATURED_WARN_BEFORE_MS = 6 * 60 * 60 * 1000;

exports.expireFeaturedLessons = functions.pubsub
  .schedule("every 30 minutes")
  .timeZone("Asia/Riyadh")
  .onRun(async () => {
    const nowIso = new Date().toISOString();
    const nowMs = Date.now();
    // الوثائق القديمة ملفوفة `{data:{...}}` بلا مرآة جذرية: استعلام الجذر
    // لا يراها فيبقى تمييزها المنقضي قائماً للأبد. لذا استعلام ثانٍ على
    // المفتاح الملفوف والدمج بمعرّف الوثيقة (نفس نهج deleteSubcategoryCascade).
    const [rootSnap, wrappedSnap] = await Promise.all([
      db.collection("lessons").where("featured", "==", true).get(),
      db.collection("lessons").where("data.featured", "==", true).get(),
    ]);
    const docMap = new Map();
    [...rootSnap.docs, ...wrappedSnap.docs]
      .forEach((doc) => docMap.set(doc.id, doc));
    let cleared = 0;
    let batch = db.batch();
    let pending = 0;
    const warnings = [];
    for (const doc of docMap.values()) {
      const value = doc.data() || {};
      const wrapped = value.data && typeof value.data === "object";
      const inner = wrapped ? value.data : value;
      const until = value.featuredUntil || (wrapped && value.data.featuredUntil);
      if (!until) continue; // تمييز دائم.
      const ms = Date.parse(until);
      if (Number.isNaN(ms)) continue;
      if (ms > nowMs) {
        if (ms - nowMs > FEATURED_WARN_BEFORE_MS) continue;
        const warnedFor = cleanString(inner.featuredExpiryWarnedFor, 60);
        const target = normalizeEmail(inner.addedBy || inner.createdByEmail);
        if (!target || warnedFor === String(until)) continue;
        warnings.push({
          ref: doc.ref,
          id: doc.id,
          wrapped,
          until: String(until),
          target,
          title: cleanString(inner.title || inner.name, 180),
          remainingMs: ms - nowMs,
        });
        continue;
      }
      // الوثيقة الملفوفة تحمل المرآتين (الجذر + data): مسح data.* وحده
      // يُبقي المرآة الجذرية featured=true للأبد — يُمسح الموضعان معاً.
      batch.update(doc.ref, wrapped
        ? {
          "featured": false,
          "featuredUntil": admin.firestore.FieldValue.delete(),
          "featuredExpiredAt": nowIso,
          "data.featured": false,
          "data.featuredUntil": admin.firestore.FieldValue.delete(),
          "data.featuredExpiredAt": nowIso,
        }
        : {
          featured: false,
          featuredUntil: admin.firestore.FieldValue.delete(),
          featuredExpiredAt: nowIso,
        });
      cleared += 1;
      pending += 1;
      if (pending >= 400) {
        await batch.commit();
        batch = db.batch();
        pending = 0;
      }
    }
    if (pending > 0) await batch.commit();
    if (cleared > 0) console.log(`expireFeaturedLessons: cleared ${cleared}`);

    let warned = 0;
    for (const item of warnings) {
      const hours = Math.max(
        1,
        Math.round(item.remainingMs / (60 * 60 * 1000)),
      );
      // صياغة عربية سليمة للعدد (ساعة/ساعتين/ساعات) لا «1 ساعات».
      const hoursText = hours === 1
        ? "ساعة واحدة"
        : hours === 2 ? "ساعتين" : `${hours} ساعات`;
      const alertTitle = "⭐ تمييز درسك يوشك أن ينتهي";
      const alertBody = `تمييز «${item.title || "درسك"}» ينتهي بعد ${hoursText}`
        + " تقريباً — مدّده أو دعه يسقط من مختارات المنبر.";
      try {
        await Promise.all([
          writeAdminAlert(item.target, alertTitle, alertBody, {
            type: "featured_expiring",
            lessonId: item.id,
            refId: item.id,
            featuredUntil: item.until,
          }),
          pushToAdminsFiltered(alertTitle, alertBody, {
            type: "featured_expiring",
            lessonId: item.id,
            refId: item.id,
          }, { targetEmail: item.target }),
        ]);
        // الوسم بعد نجاح الإرسال وحده، كي تُعاد المحاولة في الدورة التالية
        // إن فشل — ولا يتكرّر الإنذار إن نجح.
        await item.ref.update(item.wrapped
          ? {
            "data.featuredExpiryWarnedFor": item.until,
            "data.featuredExpiryWarnedAt": nowIso,
          }
          : {
            featuredExpiryWarnedFor: item.until,
            featuredExpiryWarnedAt: nowIso,
          });
        warned += 1;
      } catch (error) {
        console.error("featured expiry warning failed", item.id, error);
      }
    }
    if (warned > 0) console.log(`expireFeaturedLessons: warned ${warned}`);
    return null;
  });

// تنظيف يومي للملفات اليتيمة وإعادة محاولة مهام تنظيف التخزين الفاشلة.
exports.cleanupOrphanSubmissionUploads = functions.runWith({
  timeoutSeconds: 540,
  memory: "512MB",
}).pubsub.schedule("30 3 * * *").timeZone("Asia/Riyadh").onRun(async () => {
  const now = Date.now();
  const [files] = await bucket.getFiles({ prefix: "submissions/" });
  const submissionsSnap = await db.collection("lesson_submissions").get();
  const submissions = new Map(
    submissionsSnap.docs.map((doc) => [doc.id, doc.data() || {}]),
  );
  let deletedOrphans = 0;
  let failedOrphans = 0;
  for (const file of files) {
    const parts = file.name.split("/");
    if (parts.length < 4) continue;
    const submissionId = parts[2];
    const submission = submissions.get(submissionId);
    const createdAt = Date.parse(file.metadata && file.metadata.timeCreated || "") || 0;
    const oldEnough = createdAt && now - createdAt > 24 * 60 * 60 * 1000;
    const orphan = !submission && oldEnough;
    const decidedLeftover = submission && submission.status !== "pending";
    if (!orphan && !decidedLeftover) continue;
    try {
      await file.delete({ ignoreNotFound: true });
      deletedOrphans += 1;
    } catch (error) {
      console.error("orphan cleanup failed", file.name, error);
      failedOrphans += 1;
    }
  }

  const jobsSnap = await db.collection("storage_cleanup_jobs")
    .where("status", "==", "pending")
    .limit(100)
    .get();
  let completedJobs = 0;
  let exhaustedJobs = 0;
  for (const job of jobsSnap.docs) {
    const value = job.data() || {};
    const failed = [];
    for (const path of Array.isArray(value.paths) ? value.paths : []) {
      try {
        await deleteFileIfExists(path);
      } catch (_) {
        failed.push(path);
      }
    }
    if (!failed.length) {
      await job.ref.update({
        status: "done",
        completedAt: admin.firestore.FieldValue.serverTimestamp(),
      });
      completedJobs += 1;
    } else {
      // العدّاد يُقرأ لا يُكتب وحده: بلوغ الحدّ يُوقف المهمّة (فلا تلتقطها
      // الدورة القادمة) ويرفع تنبيهاً للمالك ليحذف الملفات يدوياً.
      const attempts = Number(value.attempts || 0) + 1;
      const exhausted = attempts >= MAX_CLEANUP_ATTEMPTS;
      await job.ref.update({
        paths: failed,
        attempts,
        status: exhausted ? "failed" : "pending",
        lastAttemptAt: admin.firestore.FieldValue.serverTimestamp(),
      });
      if (exhausted) {
        exhaustedJobs += 1;
        await writeAdminAlert(
          OWNER_EMAIL,
          "تعذّر حذف ملفات من التخزين",
          `${failed.length} ملفاً لم يُحذف بعد ${attempts} محاولات`
            + ` (${cleanString(value.reason, 60) || "تنظيف"}) — يلزم حذف يدوي.`,
          { type: "storage_cleanup_failed", refId: job.id, jobId: job.id },
        );
      }
    }
  }
  await auditOwnerAction("system", "cleanup_orphan_submission_uploads", "", {
    scannedFiles: files.length,
    deletedOrphans,
    failedOrphans,
    cleanupJobsScanned: jobsSnap.size,
    completedJobs,
    exhaustedJobs,
  });
  return null;
});

// ─── اعتماد المشرفين: رمز مستقل لكل بريد ────────────────────────────
function generateSixDigitCode() {
  return String(crypto.randomInt(0, 1_000_000)).padStart(6, "0");
}

exports.onCodeRequested = functions.firestore
  .document("dashboard_code_requests/{email}")
  .onCreate(async (snap, context) => {
    const email = normalizeEmail(decodeURIComponent(context.params.email));
    const requestData = snap.data() || {};
    try {
      if (email === OWNER_EMAIL) {
        await snap.ref.update({ result: "already_authorized" });
        return null;
      }
      const adminSnap = await db.collection(ADMINS_COLLECTION).doc(email).get();
      const adminData = adminSnap.data() || {};
      if (adminSnap.exists) {
        await snap.ref.update({
          result: adminData.blocked === true ? "blocked" : "already_authorized",
        });
        return null;
      }
      const codeRef = db.collection("dashboard_owner_codes").doc(email);
      const now = Date.now();
      const codeSnap = await codeRef.get();
      const previous = codeSnap.data() || {};
      const lastRequestedAt = Number(previous.lastRequestedAt || 0);
      if (lastRequestedAt && now - lastRequestedAt < CODE_REQUEST_INTERVAL_MS) {
        await snap.ref.update({
          result: "rate_limited",
          retryAfterSec: Math.ceil(
            (CODE_REQUEST_INTERVAL_MS - (now - lastRequestedAt)) / 1000,
          ),
        });
        return null;
      }
      const codeData = {
        code: generateSixDigitCode(),
        candidateUid: cleanString(requestData.uid, 180),
        candidateEmail: email,
        candidateName: cleanString(requestData.name, 100),
        candidatePhotoURL: cleanString(requestData.photoURL, 2048),
        createdAt: now,
        expiresAt: now + CODE_TTL_MS,
        attempts: 0,
        lastRequestedAt: now,
      };
      await codeRef.set(codeData);
      // مرآة توافق مؤقتة للنسخة القديمة التي تراقب current فقط.
      await db.collection("dashboard_owner_codes").doc("current").set(codeData);
      await snap.ref.update({ result: "ok" });
      const candidate = codeData.candidateName || email;
      const alertBody = `رمز اعتماد ${candidate} هو ${codeData.code}، وصالح لمدة 10 دقائق.`;
      await clearAdminAlerts("owner_code", email);
      await Promise.all([
        writeAdminAlert(OWNER_EMAIL, "رمز اعتماد مشرف جديد", alertBody, {
          type: "owner_code",
          refId: email,
          candidateEmail: email,
          expiresAt: codeData.expiresAt,
        }),
        pushToAdmins(
          "رمز اعتماد مشرف جديد",
          alertBody,
          { type: "owner_code", candidateEmail: email, refId: email },
          true,
        ),
      ]);
      return null;
    } catch (error) {
      console.error("onCodeRequested failed", error);
      await snap.ref.update({ result: "error" }).catch(() => {});
      return null;
    }
  });

exports.onCodeVerifyRequested = functions.firestore
  .document("dashboard_code_verify/{email}")
  .onCreate(async (snap, context) => {
    const email = normalizeEmail(decodeURIComponent(context.params.email));
    const entered = cleanString((snap.data() || {}).code, 6);
    const codeRef = db.collection("dashboard_owner_codes").doc(email);
    const adminRef = db.collection(ADMINS_COLLECTION).doc(email);
    const now = Date.now();
    try {
      await db.runTransaction(async (tx) => {
        const codeSnap = await tx.get(codeRef);
        if (!codeSnap.exists) {
          tx.update(snap.ref, { result: "no_code" });
          return;
        }
        const value = codeSnap.data() || {};
        if (now > Number(value.expiresAt || 0)) {
          tx.delete(codeRef);
          tx.update(snap.ref, { result: "expired" });
          return;
        }
        const attempts = Number(value.attempts || 0);
        if (attempts >= MAX_CODE_ATTEMPTS) {
          tx.delete(codeRef);
          tx.update(snap.ref, { result: "too_many_attempts" });
          return;
        }
        if (!/^\d{6}$/.test(entered) || entered !== String(value.code || "")) {
          const next = attempts + 1;
          if (next >= MAX_CODE_ATTEMPTS) {
            tx.delete(codeRef);
            tx.update(snap.ref, { result: "too_many_attempts" });
          } else {
            tx.update(codeRef, { attempts: next });
            tx.update(snap.ref, { result: "invalid" });
          }
          return;
        }
        tx.set(adminRef, {
          email,
          role: "supervisor",
          blocked: false,
          displayName: cleanString(value.candidateName, 100),
          photoURL: cleanString(value.candidatePhotoURL, 2048),
          addedBy: "owner_code_approval",
          addedAt: now,
          lastSignedInAt: now,
        });
        tx.delete(codeRef);
        tx.update(snap.ref, { result: "ok" });
      });
      const resultSnap = await snap.ref.get();
      const result = cleanString((resultSnap.data() || {}).result, 40);
      // مرآة «current» تُمحى عند كل تحقق ناجح وعند انقضاء الصلاحية أو
      // استنفاد المحاولات (الرمز نفسه حُذف حينها) — لا فقط حين يطابق
      // بريدها لحظة التحقق، وإلا بقيت مرآة يتيمة تشير إلى رمز لم يعد قائماً.
      if (["ok", "expired", "too_many_attempts"].includes(result)) {
        await db.collection("dashboard_owner_codes").doc("current")
          .delete().catch(() => {});
      }
      if (["ok", "expired", "too_many_attempts", "no_code"].includes(result)) {
        await clearAdminAlerts("owner_code", email);
      }
    } catch (error) {
      console.error("onCodeVerifyRequested failed", error);
      await snap.ref.update({ result: "error" }).catch(() => {});
    }
    return null;
  });

// ─── المكالمات الصوتيّة بين المشرفين (تنبيه data-only) ──────────────
//
// ⚠️ لا تستعمل sendToAdminTargets هنا إطلاقاً: هو يُدرج كتلة notification
// وقناة admin_alerts، فيرسم النظام الإشعار بنفسه في الخلفية ولا يعمل كود
// المكالمة (onMessageReceived) أصلاً. المكالمة تحتاج رسالة data-only
// بأولوية عالية كي يستيقظ الجهاز ويعرض شاشة الرنين هو.
//
// وكتم الدردشة (chatMuted) لا يُطبَّق هنا عمداً: الكتم للرسائل لا للمكالمات.
async function sendCallPush(targetUid, data) {
  const target = cleanString(targetUid, 180);
  if (!target) return { successCount: 0, failureCount: 0 };
  const targets = (await activeAdminTokens(false)).filter(
    (item) => item.uid === target,
  );
  if (!targets.length) return { successCount: 0, failureCount: 0 };
  let successCount = 0;
  let failureCount = 0;
  for (let offset = 0; offset < targets.length; offset += 500) {
    const chunk = targets.slice(offset, offset + 500);
    const response = await admin.messaging().sendEachForMulticast({
      tokens: chunk.map((item) => item.token),
      data: safeData(data),
      android: { priority: "high" },
    });
    successCount += response.successCount;
    failureCount += response.failureCount;
    const removals = [];
    response.responses.forEach((item, index) => {
      const code = item.error && item.error.code || "";
      if (code.includes("registration-token-not-registered")
          || code.includes("invalid-registration-token")) {
        // صفّ الأمّ: لا تُحذف وثيقتها (هي مصدر هويّة أجهزة devices) —
        // يُمحى حقل token وحده. صفّ device: تُحذف وثيقته كاملة.
        if (chunk[index].isParent) {
          removals.push(chunk[index].ref.update({
            token: admin.firestore.FieldValue.delete(),
          }).catch(() => null));
        } else {
          removals.push(chunk[index].ref.delete());
        }
      }
    });
    await Promise.all(removals);
  }
  return { successCount, failureCount };
}

// الحالات التي تُسقط شاشة الرنين عند الطرفين.
const CALL_CANCEL_STATUSES = ["declined", "ended", "missed", "busy"];

exports.onAdminCallCreated = functions.firestore
  .document("admin_calls/{callId}")
  .onCreate(async (snap, context) => {
    const value = snap.data() || {};
    if (cleanString(value.status, 20) !== "ringing") return null;
    const calleeId = cleanString(value.calleeId, 180);
    if (!calleeId) return null;
    try {
      await sendCallPush(calleeId, {
        type: "admin_call",
        action: "incoming",
        callId: cleanString(context.params.callId, 200),
        callerId: cleanString(value.callerId, 180),
        callerName: cleanString(value.callerName || "مشرف", 100),
        callerPhoto: cleanString(value.callerPhoto, 2048),
      });
    } catch (error) {
      console.error("onAdminCallCreated failed", error);
    }
    return null;
  });

exports.onAdminCallUpdated = functions.firestore
  .document("admin_calls/{callId}")
  .onUpdate(async (change, context) => {
    const before = change.before.data() || {};
    const after = change.after.data() || {};
    const beforeStatus = cleanString(before.status, 20);
    const afterStatus = cleanString(after.status, 20);
    if (beforeStatus === afterStatus) return null;
    const accepted = afterStatus === "accepted";
    if (!accepted && !CALL_CANCEL_STATUSES.includes(afterStatus)) return null;

    const callId = cleanString(context.params.callId, 200);
    // عند القبول نوقف رنين أجهزة المستقبِل الأخرى فقط؛ المتّصل يبقى في
    // المكالمة. أمّا النهاية فتسقط الشاشة عند الطرفين.
    const members = accepted
      ? [after.calleeId]
      : (Array.isArray(after.members) ? after.members : [])
        .concat([after.callerId, after.calleeId]);
    const targets = [];
    members.forEach((raw) => {
      const uid = cleanString(raw, 180);
      if (uid && !targets.includes(uid)) targets.push(uid);
    });
    if (!targets.length) return null;

    try {
      await Promise.all(targets.map((uid) => sendCallPush(uid, {
        type: "admin_call",
        action: "cancel",
        callId,
      })));
    } catch (error) {
      console.error("onAdminCallUpdated failed", error);
    }
    return null;
  });

// ─── فحص أسبوعيّ لروابط الصوت الميتة ──────────────────────────────
// لماذا: في ٢٠٢٦-٠٨-١٢ اكتُشف ٧٠ درساً صوتها لا يعمل (حساب الاستضافة
// القديم زال)، والاكتشاف كان يدويّاً بعد أن بقيت معطّلة عند المستخدمين
// مدّةً مجهولة. هذا الفحص يكشفها مبكّراً.
// ⚠️ الاكتشاف فقط: لا حذف ولا نقل إلى السلّة — القرار بشريّ.

const AUDIO_CHECK_CONCURRENCY = 10;
// ⏱️ 5 ثوانٍ تكفي لطلب HEAD — المهلة الطويلة (12ث) كانت تجعل مضيفاً
// يبتلع الاتصالات يستهلك عمر الدالة كله قبل فحص المكتبة.
const AUDIO_CHECK_TIMEOUT_MS = 5000;
const AUDIO_ALERT_SAMPLE = 5;

/** صياغة العدد بالعربية: «درس واحد»/«درسان»/«٣ دروس»/«١٢ درساً»/«١٠٠ درس». */
function arabicLessonsCount(n) {
  if (n === 1) return "درس واحد";
  if (n === 2) return "درسان";
  if (n <= 10) return `${n} دروس`;
  // تمييز مضاعفات المئة مفردٌ مجرور: «100 درس» لا «100 درساً».
  if (n % 100 === 0) return `${n} درس`;
  return `${n} درساً`;
}

/**
 * طلب HEAD واحد. يرجع true إن ردّ الخادم بنجاح، وfalse إن ردّ بخطأ
 * دائم (404/403/410)، وnull إن كان العطل عابراً (5xx/429 أو مهلة)،
 * ورمزَ العطل نصّاً إن فشل الاتصال نفسه (زوال نطاق/رفض/شهادة) —
 * فتكرار الرمز نفسه في محاولتين دليل موتٍ لا عارضِ شبكة.
 */
async function probeAudioOnce(url) {
  try {
    const response = await fetch(url, {
      method: "HEAD",
      redirect: "follow",
      signal: AbortSignal.timeout(AUDIO_CHECK_TIMEOUT_MS),
    });
    if (response.ok) return true;
    if (response.status >= 500 || response.status === 429) return null;
    return false;
  } catch (error) {
    // المهلة وحدها عابرة: خادم بطيء ليس خادماً ميتاً.
    if (error && (error.name === "TimeoutError" || error.name === "AbortError")) {
      return null;
    }
    // fetch يغلّف عطل الشبكة في TypeError وسببه يحمل الرمز الحقيقي
    // (ENOTFOUND/ECONNREFUSED/CERT_...) — يُعاد الرمز للمقارنة.
    const cause = error && error.cause;
    const code = cleanString(
      (cause && cause.code) || (error && error.code) || (error && error.name),
      60,
    );
    return `err:${code || "network"}`;
  }
}

/** محاولتان قبل الحكم بالموت: العطل العابر لا يُحسب رابطاً ميتاً. */
async function isAudioDead(url) {
  const first = await probeAudioOnce(url);
  if (first === true) return false;
  const second = await probeAudioOnce(url);
  if (second === true) return false;
  // رفض HTTP دائم (404/403/410) في أي من المحاولتين: ميت.
  if (first === false || second === false) return true;
  // ⚠️ عطل اتصال بالنوع **نفسه** في المحاولتين (NXDOMAIN/رفض/شهادة):
  // هذا نمط موت الاستضافة القديمة (حادثة 2026-08-12) لا عارضاً شبكياً —
  // كان يُصنَّف «عابراً» فلا يُكشف المضيف الزائل أبداً.
  if (typeof first === "string" && first === second) return true;
  // مهلة أو عطل متقلّب النوع: لا نتّهم الرابط.
  return false;
}

exports.weeklyDeadAudioScan = functions
  .runWith({ timeoutSeconds: 540, memory: "512MB" })
  .pubsub.schedule("20 4 * * 1")
  .timeZone("Asia/Riyadh")
  .onRun(async () => {
    // ⏱️ ميزانية زمنية: مهلة المنصة 540ث، وتجاوزها يقتل الدالة **قبل**
    // كتابة أي تنبيه فيضيع الأسبوع كله بصمت. عند الاقتراب من الحدّ يتوقف
    // الفحص ويُكتب ما تجمّع بوسم «فحص جزئي».
    const startedAtMs = Date.now();
    const SOFT_DEADLINE_MS = 480 * 1000;
    let partial = false;
    const dead = [];
    let checked = 0;
    let cursor = null;
    // مرور على دفعات بحجم ٣٠٠ وثيقة كي لا تُحمَّل المجموعة كلّها دفعة واحدة.
    scan: for (;;) {
      let query = db.collection("lessons").orderBy("__name__").limit(300);
      if (cursor) query = query.startAfter(cursor);
      const snap = await query.get();
      if (snap.empty) break;
      cursor = snap.docs[snap.docs.length - 1];

      const targets = [];
      snap.docs.forEach((doc) => {
        const fields = lessonModerationFields(doc.data());
        if (!fields.audioUrl || !/^https?:\/\//i.test(fields.audioUrl)) return;
        targets.push({ id: doc.id, title: fields.title, url: fields.audioUrl });
      });

      // توازٍ محدود (١٠ طلبات معاً) كي لا تُستنزف حصّة الشبكة.
      for (let i = 0; i < targets.length; i += AUDIO_CHECK_CONCURRENCY) {
        if (Date.now() - startedAtMs > SOFT_DEADLINE_MS) {
          partial = true;
          break scan;
        }
        const slice = targets.slice(i, i + AUDIO_CHECK_CONCURRENCY);
        const results = await Promise.all(slice.map((item) => isAudioDead(item.url)));
        results.forEach((isDead, index) => {
          checked += 1;
          if (isDead) dead.push(slice[index]);
        });
      }
      if (snap.size < 300) break;
    }

    // الصمت خير من ضجيج أسبوعيّ: لا تنبيه إن لم يفشل شيء.
    if (!dead.length) {
      if (partial) {
        console.warn("weeklyDeadAudioScan: فحص جزئي بلا أعطال (نفدت المهلة)", { checked });
      } else {
        console.log("weeklyDeadAudioScan: كل الروابط تعمل", { checked });
      }
      return null;
    }

    // الأسماء تُراجَع والأرقام لا تُراجَع — فتُذكر أسماء أوّل خمسة صراحةً.
    // ⚠️ كل عنوان يُقصّ إلى 60 حرفاً: عناوين بطولها الكامل (حتى 300 حرف)
    // كانت تتجاوز سقف متن التنبيه (700 في writeAdminAlert) فتُبتر خاتمةُ
    // الطمأنة «لم يُحذف شيء» ووجهةُ المراجعة.
    const sample = dead.slice(0, AUDIO_ALERT_SAMPLE)
      .map((item) => `• ${cleanString(item.title, 60) || "درس بلا عنوان"}`)
      .join("\n");
    const rest = dead.length - Math.min(dead.length, AUDIO_ALERT_SAMPLE);
    const body = `${arabicLessonsCount(dead.length)} صوتها لا يعمل`
      + (partial ? " (فحص جزئي — توقّف عند حدّ الوقت)" : "")
      + `:\n${sample}`
      + (rest > 0 ? `\nوغيرها (${arabicLessonsCount(rest)}).` : "")
      + "\nراجعها في «إدارة الكل» — لم يُحذف شيء.";

    await writeAdminAlert(
      OWNER_EMAIL,
      "دروس صوتها لا يعمل",
      body,
      {
        type: "dead_audio_scan",
        refId: `dead_audio_${new Date().toISOString().slice(0, 10)}`,
        deadCount: dead.length,
        checked,
        partial,
        lessonIds: dead.slice(0, 50).map((item) => item.id),
      },
    );
    await auditOwnerAction("system", "weekly_dead_audio_scan", "", {
      checked,
      dead: dead.length,
      partial,
    });
    return null;
  });

// ---------------------------------------------------------------------------
// 🧾 سجلّ الاختفاء `deleted_ids` — عين التطبيق على ما لم يعد موجوداً
// ---------------------------------------------------------------------------
//
// التطبيق صار يزامن **تفاضليّاً**: يجلب ما تغيّر بعد آخر مزامنة بدل تنزيل
// المكتبة كلّها كلّما صُحِّح حرفٌ في اسم قسم. لكن الاستعلام التفاضليّ لا
// يرى المحذوف أبداً — الوثيقة لم تعد هناك لتُقرأ — فيبقى الدرس المحذوف
// معروضاً في الأجهزة إلى الأبد.
//
// فلكل اختفاءٍ من `lessons`/`categories`/`subcategories` سطرٌ هنا يقرؤه
// التطبيق فيحذفه من نسخته المحفوظة.
//
// ⚠️ مُشغِّل `onDelete` لا مساسَ له بمسارات الحذف: الحذف في هذا المشروع
// يمرّ غالباً بالسلّة (`deleted_lessons`) وبدفعات الحذف التعاقبي، وكلّها
// تنتهي إلى `delete()` على وثيقة المجموعة — والمُشغِّل يلتقطها جميعاً:
// deleteLesson/deleteLessonPermanently، وdeleteSubcategoryCascade،
// وdeleteCategoryCascade، وأي حذفٍ مباشر من الوحدة. (أمّا purgeDeletedLesson
// وemptyTrash فيمسحان من السلّة لا من `lessons`، والدرس كان قد سُجِّل
// اختفاؤه لحظة دخوله السلّة.)
//
// و**الاستعادة** تحتاج أمرين (تكتبهما restoreDeletedLesson
// وrestoreDeletedSection): طابع `updatedAt` جديد كي تُلتقط الوثيقة
// المستعادة تفاضلياً، ومحو شاهد الحذف من هذه المجموعة كي لا يُقدَّم
// الحذفُ على الاستعادة عند جهاز تشمل نافذته الحدثين معاً.
const DELETED_IDS_COLLECTION = "deleted_ids";
const DELETED_IDS_RETENTION_MS = 90 * 24 * 60 * 60 * 1000;

async function recordDeletedId(collection, docId) {
  const id = cleanString(docId, 400);
  if (!id) return null;
  try {
    // ♻️ سباق الاستعادة الفورية: قد يصل هذا المُشغِّل **بعد** أن استُعيدت
    // الوثيقة (أو أُعيد إنشاؤها بالمعرّف نفسه) — كتابة شاهد حذف لوثيقة
    // حيّة تُسقطها من أجهزة المستخدمين ظلماً.
    const live = await db.collection(collection).doc(id).get();
    if (live.exists) return null;
    // معرّفٌ مركَّب: المجموعات الثلاث قد تتشارك معرّفاً فلا يدهس أحدها الآخر.
    await db.collection(DELETED_IDS_COLLECTION).doc(`${collection}__${id}`).set({
      collection,
      docId: id,
      // رقم خام لا Timestamp: التطبيق يستعلم بـ`deletedAtMs >` وحدَّه رقم،
      // وFirestore يرتّب القيم بأنواعها فلا يرى حدٌّ رقميّ قيمةً من نوع آخر.
      deletedAtMs: Date.now(),
      deletedAt: admin.firestore.FieldValue.serverTimestamp(),
    });
  } catch (error) {
    console.error("recordDeletedId failed", collection, id, error);
  }
  return null;
}

exports.onLessonDeleted = functions.firestore
  .document("lessons/{docId}")
  .onDelete((snap, context) => recordDeletedId("lessons", context.params.docId));

exports.onCategoryDeleted = functions.firestore
  .document("categories/{docId}")
  .onDelete((snap, context) => recordDeletedId("categories", context.params.docId));

exports.onSubcategoryDeleted = functions.firestore
  .document("subcategories/{docId}")
  .onDelete((snap, context) => recordDeletedId("subcategories", context.params.docId));

/**
 * تنظيف سجلّ الاختفاء: ما مضى عليه أكثر من تسعين يوماً يُمسح.
 *
 * تسعون يوماً سخاءٌ مقصود: جهازٌ لم يُفتح فيه التطبيق طوال هذه المدّة يكون
 * سجلّ حذفه ناقصاً — وهذا مأمون، لأن التطبيق يقارن أعداد المجموعات بعد كل
 * دمج فيسقط إلى الجلب الكامل من تلقاء نفسه حين لا تتطابق.
 */
exports.cleanupDeletedIds = functions
  .runWith({ timeoutSeconds: 300, memory: "256MB" })
  .pubsub.schedule("50 3 * * *")
  .timeZone("Asia/Riyadh")
  .onRun(async () => {
    const cutoff = Date.now() - DELETED_IDS_RETENTION_MS;
    let removed = 0;
    // على دفعات: مجموعةٌ متضخّمة لا تُقرأ دفعةً واحدة في الذاكرة.
    for (let round = 0; round < 20; round += 1) {
      const snap = await db.collection(DELETED_IDS_COLLECTION)
        .where("deletedAtMs", "<", cutoff)
        .limit(400)
        .get();
      if (snap.empty) break;
      const batch = db.batch();
      snap.docs.forEach((doc) => batch.delete(doc.ref));
      await batch.commit();
      removed += snap.size;
      if (snap.size < 400) break;
    }
    if (removed > 0) console.log("cleanupDeletedIds removed", removed);
    // 🧭 أرضية الدلتا: أقدم لحظة ما زال سجلّ الحذف يغطّيها. جهازٌ علامةُ
    // حذفه أقدم من هذه الأرضية لا يستطيع الاستمرار بالدلتا حتمياً (قد فاتته
    // شواهد ممسوحة) فيجب عليه جلبٌ كامل — يقرؤها المسبار من content_meta.
    try {
      await CONTENT_META_REF.set({ deltaFloorMs: cutoff }, { merge: true });
    } catch (error) {
      console.error("cleanupDeletedIds deltaFloor failed", error);
    }
    return null;
  });

// ─── بصمة المحتوى content_meta/state ────────────────────────────────
// وثيقة واحدة تحمل ما يفحصه مسبار التطبيق (ContentRepository.probe):
// عدد كل مجموعة + أحدث updatedAt لكلٍّ منها + أحدث حذف — فيقرأ التطبيق
// وثيقةً واحدة بدل ستّة استعلامات عند كل فتح. التحديث رخيص: increment
// للعدادات عند الإنشاء/الحذف، والطوابع بـmax داخل معاملة على وثيقة واحدة.
const CONTENT_META_REF = db.collection("content_meta").doc("state");
const CONTENT_META_COLLECTIONS = ["lessons", "categories", "subcategories"];

function contentTsToMs(value) {
  if (!value) return 0;
  if (typeof value.toMillis === "function") return value.toMillis();
  const numeric = Number(value);
  if (Number.isFinite(numeric) && numeric > 0) return numeric;
  const parsed = Date.parse(String(value));
  return Number.isFinite(parsed) ? parsed : 0;
}

/** ملء كامل بقراءة شاملة واحدة (تجميعيّة) — للتهيئة الأولى وbackfill. */
async function computeContentMeta() {
  const state = {
    updatedAtMs: Date.now(),
    // أرضية الدلتا المضمونة: ما هو أقدم من عمر الاحتفاظ قد مُسح من سجلّ
    // الحذف، فجهازٌ علامته دونها يجلب الكتالوج كاملاً حتمياً لا احتمالاً.
    deltaFloorMs: Date.now() - DELETED_IDS_RETENTION_MS,
  };
  for (const collection of CONTENT_META_COLLECTIONS) {
    const countSnap = await db.collection(collection).count().get();
    state[`${collection}Count`] = Number(countSnap.data().count || 0);
    let newest = 0;
    // الشكلان: الجذري والمغلَّف data.updatedAt (كما في مسبار التطبيق).
    for (const field of ["updatedAt", "data.updatedAt"]) {
      try {
        const snap = await db.collection(collection)
          .orderBy(field, "desc").limit(1).get();
        if (!snap.empty) {
          newest = Math.max(newest, contentTsToMs(snap.docs[0].get(field)));
        }
      } catch (error) {
        console.error("computeContentMeta newest failed", collection, field, error);
      }
    }
    state[`${collection}UpdatedAtMs`] = newest;
  }
  try {
    const snap = await db.collection(DELETED_IDS_COLLECTION)
      .orderBy("deletedAtMs", "desc").limit(1).get();
    state.lastDeletedAtMs = snap.empty
      ? 0
      : Number(snap.docs[0].get("deletedAtMs") || 0);
  } catch (error) {
    console.error("computeContentMeta lastDeleted failed", error);
    state.lastDeletedAtMs = 0;
  }
  return state;
}

async function bumpContentMeta(collection, change) {
  const existedBefore = change.before.exists;
  const existsAfter = change.after.exists;
  const now = Date.now();
  // ⚡ تعديلٌ لم يتغيّر فيه `updatedAt` (زيادة views مثلاً) لا يعني المزامنة
  // إطلاقاً: إدخاله في البصمة كان يُبطل كاش جميع الأجهزة مع كل استماع
  // ويُشعل جلباً كاملاً دائماً (لغم 2026-08-29) — يُتجاهل بلا أي كتابة.
  if (existedBefore && existsAfter) {
    const before = change.before.data() || {};
    const after = change.after.data() || {};
    const beforeMs = Math.max(
      contentTsToMs(before.updatedAt),
      contentTsToMs(before.data && before.data.updatedAt),
    );
    const afterMs = Math.max(
      contentTsToMs(after.updatedAt),
      contentTsToMs(after.data && after.data.updatedAt),
    );
    if (afterMs <= beforeMs) return null;
  }
  try {
    const needsBackfill = await db.runTransaction(async (tx) => {
      const doc = await tx.get(CONTENT_META_REF);
      // غياب الوثيقة = لم تُهيَّأ بعد — تُملأ كاملة خارج المعاملة.
      if (!doc.exists) return true;
      const update = { updatedAtMs: now };
      if (!existedBefore && existsAfter) {
        update[`${collection}Count`] = admin.firestore.FieldValue.increment(1);
      }
      if (existedBefore && !existsAfter) {
        update[`${collection}Count`] = admin.firestore.FieldValue.increment(-1);
        update.lastDeletedAtMs =
          Math.max(Number(doc.get("lastDeletedAtMs") || 0), now);
      }
      if (existsAfter) {
        const raw = change.after.data() || {};
        // بلا `now` عمداً: البصمة مرآةٌ لطوابع الوثائق نفسها — وهي عين ما
        // تقيسه استعلامات التطبيق (`whereGreaterThan(updatedAt)`)؛ إقحام
        // لحظة التريجر جعل المساواة مع علامات الأجهزة مستحيلة للأبد.
        const ms = Math.max(
          contentTsToMs(raw.updatedAt),
          contentTsToMs(raw.data && raw.data.updatedAt),
        );
        if (ms > 0) {
          update[`${collection}UpdatedAtMs`] =
            Math.max(Number(doc.get(`${collection}UpdatedAtMs`) || 0), ms);
        }
      }
      tx.set(CONTENT_META_REF, update, { merge: true });
      return false;
    });
    if (needsBackfill) {
      await CONTENT_META_REF.set(await computeContentMeta());
    }
  } catch (error) {
    // بصمة تعريفيّة لا مصدر حقيقة: فشلها لا يمسّ الكتابة الأصليّة أبداً.
    console.error("bumpContentMeta failed", collection, error);
  }
  return null;
}

exports.onLessonWriteContentMeta = functions.firestore
  .document("lessons/{id}")
  .onWrite((change) => bumpContentMeta("lessons", change));

exports.onCategoryWriteContentMeta = functions.firestore
  .document("categories/{id}")
  .onWrite((change) => bumpContentMeta("categories", change));

exports.onSubcategoryWriteContentMeta = functions.firestore
  .document("subcategories/{id}")
  .onWrite((change) => bumpContentMeta("subcategories", change));

/** ملء/إعادة ضبط يدويّ لوثيقة البصمة — للمالك وحده. */
exports.backfillContentMeta = functions.https.onCall(async (data, context) => {
  await assertOwner(context);
  const state = await computeContentMeta();
  await CONTENT_META_REF.set(state);
  return { ok: true, state };
});

// ─── تصحيح المساهم لمساهمته ────────────────────────────────────────
// كان على من أخطأ في عنوان درسٍ رفعه أن يسحب المساهمة ويرفع الملفّ
// الصوتيّ كلّه من جديد على إنترنت ضعيف. هذه الدوالّ تسمح بتصحيح النصّ
// وحده ما دامت المساهمة `pending`: لا الملفّ ولا القسم ولا الحالة.
exports.updateMySubmission = functions.https.onCall(async (data, context) => {
  assertAppCheck(context);
  const uid = assertSignedIn(context);
  const submissionId = requireString(
    data && data.submissionId,
    "submissionId",
    1,
    180,
  );
  // الحدّان نفسهما المستعملان في createSubmission (3..120 للعنوان، 500 للملاحظة).
  const title = requireString(data && data.title, "title", 3, 120);
  const ref = db.collection("lesson_submissions").doc(submissionId);
  const snap = await ref.get();
  if (!snap.exists) {
    throw new functions.https.HttpsError("not-found", "الطلب غير موجود.");
  }
  const value = snap.data() || {};
  // التحقّق نفسه المستعمل في deleteMySubmission حرفياً.
  if (value.uid !== uid || value.status !== "pending") {
    throw new functions.https.HttpsError("permission-denied", "لا يمكن تعديل هذا الطلب.");
  }
  // ⚠️ لا مسح صامت: العنوان وحده إلزامي، وكل حقل آخر يُكتب **فقط** إن ورد
  // في الحمولة — واجهة التطبيق تعدّل العنوان بلا `note`، فكانت ملاحظة
  // المساهم الأصلية تُمحى بقيمة فارغة مع كل تعديل عنوان.
  const update = {
    title,
    editedAt: admin.firestore.FieldValue.serverTimestamp(),
    editedAtMs: Date.now(),
  };
  if (data && typeof data === "object" && Object.hasOwn(data, "note")) {
    update.note = cleanString(data.note, 500);
  }
  await ref.update(update);
  return { ok: true, id: submissionId };
});

// نظيرتها لاقتراح النصّ المشروح: **له معنى** — فصور الصفحات مرفوعة فعلاً
// وسحب الاقتراح يعني رفعها كلّها ثانيةً، والنصّ نفسه يُكتب باليد فالخطأ
// فيه أرجح. تُعدَّل الحقول النصّيّة وحدها (لا الصور ولا الدرس ولا الحالة).
exports.updateMyTranscriptSubmission = functions.https.onCall(async (data, context) => {
  assertAppCheck(context);
  const uid = assertSignedIn(context);
  const submissionId = requireString(
    data && data.submissionId,
    "submissionId",
    1,
    180,
  );
  const ref = db.collection(TRANSCRIPT_SUBMISSIONS_COLLECTION).doc(submissionId);
  const snap = await ref.get();
  if (!snap.exists) {
    throw new functions.https.HttpsError("not-found", "الطلب غير موجود.");
  }
  const value = snap.data() || {};
  if (value.uid !== uid || value.status !== "pending") {
    throw new functions.https.HttpsError("permission-denied", "لا يمكن تعديل هذا الطلب.");
  }
  // ⚠️ لا كتابة إلا للحقول المُرسلة: كان كل نداء يكتب الحقول الأربعة كلّها
  // فيمحو الغائبَ منها بقيمة فارغة — نداءُ تعديل الملاحظة وحدها كان يمسح
  // نصَّ الاقتراح واسم الكتاب والمصدر التي كتبها المساهم يدوياً.
  // (العنوان المعروض هو عنوان الدرس نفسه فليس حقلاً يملكه المساهم.)
  const payload = data && typeof data === "object" ? data : {};
  const update = {};
  if (Object.hasOwn(payload, "text")) {
    update.text = cleanString(payload.text, MAX_TRANSCRIPT_CHARS);
  }
  if (Object.hasOwn(payload, "bookTitle")) {
    update.bookTitle = cleanString(payload.bookTitle, 200);
  }
  if (Object.hasOwn(payload, "sourceRef")) {
    update.sourceRef = cleanString(payload.sourceRef, 300);
  }
  if (Object.hasOwn(payload, "note")) {
    update.note = cleanString(payload.note, 500);
  }
  if (!Object.keys(update).length) {
    throw new functions.https.HttpsError(
      "invalid-argument",
      "لا يوجد في الطلب حقل قابل للتعديل — يُعدَّل نص المقطع أو اسم الكتاب أو المصدر أو الملاحظة فقط.",
    );
  }
  const images = Array.isArray(value.imagePaths) ? value.imagePaths : [];
  // الشرط نفسه المستعمل عند الإنشاء، على النصّ **الجديد** إن أُرسل فقط:
  // لا يُسمح بمسح النصّ إن كان هو مصدر الاقتراح الوحيد (بلا صور).
  if (Object.hasOwn(update, "text")
      && update.text.length < MIN_TRANSCRIPT_TEXT_CHARS
      && !images.length) {
    throw new functions.https.HttpsError(
      "invalid-argument",
      "أرفق نص المقطع أو صورة صفحة واحدة على الأقل.",
    );
  }
  update.editedAt = admin.firestore.FieldValue.serverTimestamp();
  update.editedAtMs = Date.now();
  await ref.update(update);
  return { ok: true, id: submissionId };
});

// ─── 📮 قناة التواصل مع المالك (support_threads) ─────────────────────
//
// قناة مستقلّة تماماً عن `feedback` ولا تمسّها: البلاغات تُخزَّن ببصمة
// مجزّأة بلا هويّة (سياسة الخصوصية المنشورة)، أمّا هذه القناة فتحتاج
// المعرّف الخام صراحةً لأنّ غايتها أن يردّ المالك على صاحب الرسالة.
// كل الكتابة تمرّ بهذه الدوالّ؛ قواعد Firestore تمنع كتابة العميل.
const SUPPORT_THREADS_COLLECTION = "support_threads";
const SUPERVISION_REQUESTS_COLLECTION = "supervision_requests";
const SUPPORT_BLOCKS_COLLECTION = "support_blocks";
const SUPPORT_KINDS = ["suggestion", "bug", "lesson_help", "idea", "supervision"];
const MAX_SUPPORT_TEXT = 1000;
const MAX_SUPPORT_NAME = 40;
const MAX_SUPPORT_IMAGES = 4;
const MAX_SUPPORT_IMAGE_BYTES = 10 * 1024 * 1024;
const MAX_SUPPORT_AUDIO_BYTES = 25 * 1024 * 1024;
const MAX_DEVICE_INFO_KEYS = 8;

function supportPreview(text, audioPath, imagePaths) {
  const clean = cleanString(text, 120);
  if (clean) return clean;
  if (audioPath) return "رسالة صوتية";
  if (Array.isArray(imagePaths) && imagePaths.length) return "صورة مرفقة";
  return "رسالة";
}

// معلومات الجهاز اختياريّة بالكامل: العميل هو من يقرّر إرسالها، وتُخزَّن
// كما وصلت بعد تنظيف بسيط (نصوص فقط، مفاتيح محدودة) لتظهر للمالك.
function cleanDeviceInfo(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const entries = Object.entries(value).slice(0, MAX_DEVICE_INFO_KEYS);
  const out = {};
  entries.forEach(([key, item]) => {
    if (item === undefined || item === null) return;
    const name = cleanString(key, 40);
    if (!name) return;
    out[name] = cleanString(String(item), 120);
  });
  return Object.keys(out).length ? out : null;
}

// المرفقات كلّها تحت `support/{uid}/{threadId}/` حصراً، ويُتحقَّق من وجودها
// ونوعها وحجمها فعليّاً في التخزين (نفس نهج المساهمات واقتراحات النصوص).
async function validateSupportAttachments(uid, threadId, audioPath, imagePaths) {
  const prefix = `support/${uid}/${threadId}/`;
  const audio = cleanString(audioPath, 700);
  if (audio) {
    if (!audio.startsWith(prefix) || audio.includes("..")) {
      throw new functions.https.HttpsError("permission-denied", "مسار الملف الصوتي غير صالح.");
    }
    let metadata;
    try {
      [metadata] = await bucket.file(audio).getMetadata();
    } catch (_) {
      throw new functions.https.HttpsError("not-found", "الملف الصوتي غير موجود. أعد رفعه.");
    }
    const size = Number(metadata.size || 0);
    const contentType = String(metadata.contentType || "");
    if (size <= 0 || size > MAX_SUPPORT_AUDIO_BYTES || !contentType.startsWith("audio/")) {
      throw new functions.https.HttpsError("invalid-argument", "الملف الصوتي غير صالح أو حجمه كبير.");
    }
  }
  const list = Array.isArray(imagePaths) ? imagePaths.slice(0, MAX_SUPPORT_IMAGES) : [];
  const images = [];
  for (const raw of list) {
    const path = cleanString(raw, 700);
    if (!path || !path.startsWith(prefix) || path.includes("..")) {
      throw new functions.https.HttpsError("permission-denied", "مسار صورة غير صالح.");
    }
    let metadata;
    try {
      [metadata] = await bucket.file(path).getMetadata();
    } catch (_) {
      throw new functions.https.HttpsError("not-found", "صورة مرفقة غير موجودة. أعد رفعها.");
    }
    const size = Number(metadata.size || 0);
    const contentType = String(metadata.contentType || "");
    if (size <= 0 || size > MAX_SUPPORT_IMAGE_BYTES || !contentType.startsWith("image/")) {
      throw new functions.https.HttpsError("invalid-argument", "صورة مرفقة غير صالحة أو حجمها كبير.");
    }
    if (!images.includes(path)) images.push(path);
  }
  return { audio, images };
}

const SUPPORT_KIND_LABELS = {
  suggestion: "اقتراح",
  bug: "بلاغ خلل",
  lesson_help: "استفسار عن درس",
  idea: "فكرة",
  supervision: "طلب إشراف",
};

async function loadSupportThread(threadId) {
  const id = requireString(threadId, "threadId", 1, 180);
  const ref = db.collection(SUPPORT_THREADS_COLLECTION).doc(id);
  const snap = await ref.get();
  if (!snap.exists) {
    throw new functions.https.HttpsError("not-found", "المحادثة غير موجودة.");
  }
  return { ref, value: snap.data() || {} };
}

exports.createSupportThread = functions.https.onCall(async (data, context) => {
  assertAppCheck(context);
  const uid = assertSignedIn(context);
  const kind = requireString(data && data.kind, "kind", 1, 40);
  if (!SUPPORT_KINDS.includes(kind)) {
    throw new functions.https.HttpsError("invalid-argument", "نوع الرسالة غير معروف. اختر نوعاً من القائمة.");
  }
  const blockSnap = await db.collection(SUPPORT_BLOCKS_COLLECTION).doc(uid).get();
  if (blockSnap.exists && (blockSnap.data() || {}).blocked === true) {
    throw new functions.https.HttpsError("permission-denied", "لا يمكنك إرسال رسائل جديدة في الوقت الحالي.");
  }
  const text = cleanString(data && data.text, MAX_SUPPORT_TEXT);
  const displayName = cleanString(data && data.displayName, MAX_SUPPORT_NAME);
  const fcmToken = cleanString(data && data.fcmToken, 4096);
  const deviceInfo = cleanDeviceInfo(data && data.deviceInfo);
  const rawImages = Array.isArray(data && data.imagePaths) ? data.imagePaths : [];
  const rawAudio = cleanString(data && data.audioPath, 700);
  if (!text && !rawAudio && !rawImages.length) {
    throw new functions.https.HttpsError("invalid-argument", "اكتب رسالتك أو أرفق تسجيلاً أو صورة.");
  }
  // معرّف الخيط يأتي من العميل ليتمكّن من رفع المرفقات قبل الاستدعاء
  // (نفس نهج createSubmission)، وإلا وُلِّد هنا للرسائل النصّية.
  const requestedId = cleanString(data && data.threadId, 180);
  if (requestedId && !/^[A-Za-z0-9_-]+$/.test(requestedId)) {
    throw new functions.https.HttpsError("invalid-argument", "معرّف المحادثة غير صالح.");
  }
  const ref = requestedId
    ? db.collection(SUPPORT_THREADS_COLLECTION).doc(requestedId)
    : db.collection(SUPPORT_THREADS_COLLECTION).doc();
  const existing = await ref.get();
  if (existing.exists) {
    throw new functions.https.HttpsError("already-exists", "هذه المحادثة موجودة مسبقاً.");
  }
  const { audio, images } = await validateSupportAttachments(
    uid,
    ref.id,
    rawAudio,
    rawImages,
  );
  const about = cleanString(data && data.about, MAX_SUPPORT_TEXT);
  const relation = cleanString(data && data.relation, MAX_SUPPORT_TEXT);
  const wants = cleanString(data && data.wants, MAX_SUPPORT_TEXT);
  if (kind === "supervision" && !text && !about) {
    throw new functions.https.HttpsError("invalid-argument", "عرّف بنفسك في طلب الإشراف.");
  }
  // خيط جديد واحد كل ٢٤ ساعة لكل مستخدم: هنا فحص فقط (dryRun) — الاستهلاك
  // الفعلي بعد نجاح commit، وإلا حُرم المستخدم يوماً كاملاً بلا خيط منشأ.
  const supportRateLimit = {
    uid,
    action: "support-thread",
    limit: 1,
    windowMs: 24 * 60 * 60 * 1000,
    minIntervalMs: 5 * 1000,
  };
  await consumeRateLimit({ ...supportRateLimit, dryRun: true });
  const now = Date.now();
  const preview = supportPreview(text, audio, images);
  const thread = {
    uid,
    displayName,
    kind,
    status: "new",
    createdAtMs: now,
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
    lastMessageAtMs: now,
    lastMessagePreview: preview,
    ownerUnread: 1,
    userUnread: 0,
    ownerReplied: false,
    messageCount: 1,
    closed: false,
    blocked: false,
    fcmToken,
  };
  const message = {
    senderUid: uid,
    fromOwner: false,
    text,
    audioPath: audio,
    imagePaths: images,
    createdAtMs: now,
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
  };
  if (deviceInfo) {
    message.deviceInfo = deviceInfo;
    thread.deviceInfo = deviceInfo;
  }
  let supervisionRequestId = "";
  const messageRef = ref.collection("messages").doc();
  const batch = db.batch();
  if (kind === "supervision") {
    const requestRef = db.collection(SUPERVISION_REQUESTS_COLLECTION).doc();
    supervisionRequestId = requestRef.id;
    thread.supervisionRequestId = supervisionRequestId;
    batch.set(requestRef, {
      uid,
      displayName,
      about: about || text,
      relation,
      wants,
      status: "pending",
      createdAtMs: now,
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
      threadId: ref.id,
    });
  }
  batch.set(ref, thread);
  batch.set(messageRef, message);
  await batch.commit();
  // استهلاك الحد بعد نجاح الإنشاء فقط. فشله لا يُفشل الطلب.
  await consumeRateLimit(supportRateLimit).catch((error) => {
    console.error("support thread rate limit consume failed", ref.id, error);
  });
  const label = SUPPORT_KIND_LABELS[kind] || "رسالة";
  const who = displayName || "مستمع";
  const alertTitle = kind === "supervision"
    ? "طلب إشراف جديد"
    : `رسالة جديدة من مستمع (${label})`;
  const alertBody = `${who}: ${preview}`;
  await Promise.all([
    writeAdminAlert(OWNER_EMAIL, alertTitle, alertBody, {
      type: "support",
      threadId: ref.id,
      refId: ref.id,
      kind,
    }),
    pushToAdmins(alertTitle, alertBody, {
      type: "support",
      threadId: ref.id,
      refId: ref.id,
      kind,
      route: "support",
    }, true),
  ]);
  return { ok: true, threadId: ref.id, messageId: messageRef.id, supervisionRequestId };
});

exports.sendSupportMessage = functions.https.onCall(async (data, context) => {
  assertAppCheck(context);
  const uid = assertSignedIn(context);
  const { ref, value } = await loadSupportThread(data && data.threadId);
  if (value.uid !== uid) {
    throw new functions.https.HttpsError("permission-denied", "هذه المحادثة ليست لك.");
  }
  if (value.blocked === true) {
    throw new functions.https.HttpsError("permission-denied", "لا يمكنك إرسال رسائل في هذه المحادثة.");
  }
  if (value.closed === true) {
    throw new functions.https.HttpsError("failed-precondition", "أُغلقت هذه المحادثة. ابدأ محادثة جديدة إن احتجت.");
  }
  // لا متابعة قبل ردّ المالك: يبقى الخيط برسالة واحدة حتى يفتحه بردّه.
  let ownerReplied = value.ownerReplied === true;
  if (!ownerReplied) {
    const answered = await ref.collection("messages")
      .where("fromOwner", "==", true)
      .limit(1)
      .get();
    ownerReplied = !answered.empty;
  }
  if (!ownerReplied) {
    throw new functions.https.HttpsError(
      "failed-precondition",
      "رسالتك وصلت. انتظر الردّ قبل إرسال رسالة أخرى.",
    );
  }
  const text = cleanString(data && data.text, MAX_SUPPORT_TEXT);
  const rawImages = Array.isArray(data && data.imagePaths) ? data.imagePaths : [];
  const rawAudio = cleanString(data && data.audioPath, 700);
  if (!text && !rawAudio && !rawImages.length) {
    throw new functions.https.HttpsError("invalid-argument", "اكتب رسالتك أو أرفق تسجيلاً أو صورة.");
  }
  const { audio, images } = await validateSupportAttachments(uid, ref.id, rawAudio, rawImages);
  await consumeRateLimit({
    uid,
    action: "support-message",
    limit: 10,
    windowMs: 60 * 60 * 1000,
    minIntervalMs: 5 * 1000,
  });
  const now = Date.now();
  const preview = supportPreview(text, audio, images);
  const messageRef = ref.collection("messages").doc();
  const fcmToken = cleanString(data && data.fcmToken, 4096);
  const batch = db.batch();
  batch.set(messageRef, {
    senderUid: uid,
    fromOwner: false,
    text,
    audioPath: audio,
    imagePaths: images,
    createdAtMs: now,
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
  });
  const update = {
    status: "user_replied",
    lastMessageAtMs: now,
    lastMessagePreview: preview,
    ownerUnread: admin.firestore.FieldValue.increment(1),
    messageCount: admin.firestore.FieldValue.increment(1),
  };
  if (fcmToken) update.fcmToken = fcmToken;
  batch.update(ref, update);
  await batch.commit();
  const who = cleanString(value.displayName, MAX_SUPPORT_NAME) || "مستمع";
  const alertTitle = "ردّ جديد في محادثة مستمع";
  const alertBody = `${who}: ${preview}`;
  await Promise.all([
    writeAdminAlert(OWNER_EMAIL, alertTitle, alertBody, {
      type: "support",
      threadId: ref.id,
      refId: ref.id,
      kind: cleanString(value.kind, 40),
    }),
    pushToAdmins(alertTitle, alertBody, {
      type: "support",
      threadId: ref.id,
      refId: ref.id,
      kind: cleanString(value.kind, 40),
      route: "support",
    }, true),
  ]);
  return { ok: true, messageId: messageRef.id };
});

// إشعار صاحب الخيط بردّ المالك: صندوق داخل التطبيق + دفع لرمز جهازه،
// بنفس مسار إشعار نتيجة المساهمة تماماً.
async function notifySupportUser(threadId, thread, title, body, extra) {
  const uid = cleanString(thread.uid, 180);
  const token = cleanString(thread.fcmToken, 4096);
  const payload = Object.assign({
    type: "support",
    threadId,
    id: threadId,
    refId: threadId,
    route: "support-thread",
  }, extra || {});
  await Promise.all([
    writeUserNotification(uid, title, body, payload),
    pushToToken(token, title, body, payload),
  ]);
}

async function appendOwnerSupportMessage(ref, ownerUid, payload) {
  const now = Date.now();
  const preview = supportPreview(payload.text, payload.audioPath, payload.imagePaths);
  const messageRef = ref.collection("messages").doc();
  const batch = db.batch();
  batch.set(messageRef, {
    senderUid: ownerUid,
    fromOwner: true,
    text: cleanString(payload.text, MAX_SUPPORT_TEXT),
    audioPath: cleanString(payload.audioPath, 700),
    imagePaths: Array.isArray(payload.imagePaths) ? payload.imagePaths : [],
    createdAtMs: now,
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
  });
  batch.update(ref, {
    status: "answered",
    ownerReplied: true,
    ownerUnread: 0,
    userUnread: admin.firestore.FieldValue.increment(1),
    lastMessageAtMs: now,
    lastMessagePreview: preview,
    messageCount: admin.firestore.FieldValue.increment(1),
  });
  await batch.commit();
  return { messageRef, preview };
}

exports.replySupportThread = functions.https.onCall(async (data, context) => {
  const ownerEmail = await assertOwner(context);
  const ownerUid = context.auth.uid;
  const { ref, value } = await loadSupportThread(data && data.threadId);
  const text = cleanString(data && data.text, MAX_SUPPORT_TEXT);
  const audioPath = cleanString(data && data.audioPath, 700);
  const imagePaths = (Array.isArray(data && data.imagePaths) ? data.imagePaths : [])
    .slice(0, MAX_SUPPORT_IMAGES)
    .map((item) => cleanString(item, 700))
    .filter(Boolean);
  if (!text && !audioPath && !imagePaths.length) {
    throw new functions.https.HttpsError("invalid-argument", "اكتب ردّك أو أرفق تسجيلاً أو صورة.");
  }
  const { messageRef, preview } = await appendOwnerSupportMessage(ref, ownerUid, {
    text,
    audioPath,
    imagePaths,
  });
  await Promise.all([
    clearAdminAlerts("support", ref.id),
    notifySupportUser(ref.id, value, "وصلك ردّ على رسالتك", preview, { result: "reply" }),
    auditOwnerAction(ownerEmail, "support-reply", ref.id, { kind: cleanString(value.kind, 40) }),
  ]);
  return { ok: true, messageId: messageRef.id };
});

exports.closeSupportThread = functions.https.onCall(async (data, context) => {
  const ownerEmail = await assertOwner(context);
  const { ref, value } = await loadSupportThread(data && data.threadId);
  await ref.update({
    closed: true,
    status: "closed",
    ownerUnread: 0,
    closedAtMs: Date.now(),
    closedAt: admin.firestore.FieldValue.serverTimestamp(),
  });
  await Promise.all([
    clearAdminAlerts("support", ref.id),
    notifySupportUser(ref.id, value, "أُغلقت محادثتك", "شكراً لتواصلك.", { result: "closed" }),
    auditOwnerAction(ownerEmail, "support-close", ref.id, {}),
  ]);
  return { ok: true };
});

/**
 * تصفير شارة «غير مقروء» عند فتح المالك للمحادثة.
 *
 * ⚠️ لماذا دالّة ولا كتابة مباشرة من اللوحة؟ لأنّ قواعد `support_threads`
 * تمنع الكتابة على العميل مطلقاً (`write: if false`) — حتى المالك. فمحاولة
 * اللوحة كتابة `ownerUnread` بنفسها كانت تُرفض بصمت، فتبقى الشارة معلّقة
 * على محادثةٍ قُرئت فعلاً ولا يفهم المالك سبب بقائها.
 */
exports.markSupportThreadRead = functions.https.onCall(async (data, context) => {
  await assertOwner(context);
  const { ref } = await loadSupportThread(data && data.threadId);
  await ref.update({ ownerUnread: 0 });
  await clearAdminAlerts("support", ref.id);
  return { ok: true };
});

exports.blockSupportUser = functions.https.onCall(async (data, context) => {
  const ownerEmail = await assertOwner(context);
  const uid = requireString(data && data.uid, "uid", 1, 180);
  const blocked = data && data.blocked === true;
  await db.collection(SUPPORT_BLOCKS_COLLECTION).doc(uid).set({
    uid,
    blocked,
    updatedAtMs: Date.now(),
    updatedAt: admin.firestore.FieldValue.serverTimestamp(),
  }, { merge: true });
  const snap = await db.collection(SUPPORT_THREADS_COLLECTION)
    .where("uid", "==", uid)
    .get();
  for (let offset = 0; offset < snap.docs.length; offset += 400) {
    const batch = db.batch();
    snap.docs.slice(offset, offset + 400)
      .forEach((doc) => batch.update(doc.ref, { blocked }));
    await batch.commit();
  }
  await auditOwnerAction(ownerEmail, blocked ? "support-block" : "support-unblock", uid, {
    threads: snap.size,
  });
  return { ok: true, blocked, threads: snap.size };
});

exports.decideSupervisionRequest = functions.https.onCall(async (data, context) => {
  const ownerEmail = await assertOwner(context);
  const ownerUid = context.auth.uid;
  const requestId = requireString(data && data.requestId, "requestId", 1, 180);
  const decision = requireString(data && data.decision, "decision", 1, 40);
  if (!["approved", "rejected"].includes(decision)) {
    throw new functions.https.HttpsError("invalid-argument", "القرار غير معروف. اختر القبول أو الرفض.");
  }
  const note = cleanString(data && data.note, MAX_SUPPORT_TEXT);
  const requestRef = db.collection(SUPERVISION_REQUESTS_COLLECTION).doc(requestId);
  const snap = await requestRef.get();
  if (!snap.exists) {
    throw new functions.https.HttpsError("not-found", "الطلب غير موجود.");
  }
  const value = snap.data() || {};
  if (value.status !== "pending") {
    throw new functions.https.HttpsError("failed-precondition", "هذا الطلب محسوم من قبل.");
  }
  await requestRef.update({
    status: decision,
    note,
    decidedAtMs: Date.now(),
    decidedAt: admin.firestore.FieldValue.serverTimestamp(),
    decidedByEmail: ownerEmail,
  });
  // ⛔ لا وثيقة مشرف تُنشأ هنا: الاعتماد الفعليّ يبقى بيد المالك في شاشة
  // المشرفين. هذه الدالّة تغيّر حالة الطلب وتكتب رسالة في الخيط فقط.
  const baseText = decision === "approved"
    ? "قُبل طلب الإشراف. سنتابع معك الخطوة التالية."
    : "لم يُقبل طلب الإشراف حالياً. شكراً لاهتمامك.";
  const text = note ? `${baseText}\n${note}` : baseText;
  const threadId = cleanString(value.threadId, 180);
  if (threadId) {
    const threadRef = db.collection(SUPPORT_THREADS_COLLECTION).doc(threadId);
    const threadSnap = await threadRef.get();
    if (threadSnap.exists) {
      const thread = threadSnap.data() || {};
      await appendOwnerSupportMessage(threadRef, ownerUid, {
        text,
        audioPath: "",
        imagePaths: [],
      });
      await Promise.all([
        clearAdminAlerts("support", threadId),
        notifySupportUser(threadId, thread, "نتيجة طلب الإشراف", baseText, {
          result: decision,
          requestId,
        }),
      ]);
    }
  }
  await auditOwnerAction(ownerEmail, "supervision-decision", requestId, { decision });
  return { ok: true, requestId, decision };
});

// حذف خيط واحد بكل رسائله وملفّاته وطلب الإشراف المرتبط به.
async function deleteSupportThreadDeep(uid, threadRef) {
  const threadId = threadRef.id;
  await deleteQuery(threadRef.collection("messages"));
  await deleteQuery(
    db.collection(SUPERVISION_REQUESTS_COLLECTION)
      .where("uid", "==", uid)
      .where("threadId", "==", threadId),
  );
  try {
    await bucket.deleteFiles({ prefix: `support/${uid}/${threadId}/` });
  } catch (error) {
    console.error("support storage cleanup failed", threadId, error);
  }
  await clearAdminAlerts("support", threadId);
  await threadRef.delete();
}

exports.deleteMySupportThread = functions
  .runWith({ timeoutSeconds: 120, memory: "512MB" })
  .https.onCall(async (data, context) => {
    assertAppCheck(context);
    const uid = assertSignedIn(context);
    const { ref, value } = await loadSupportThread(data && data.threadId);
    if (value.uid !== uid) {
      throw new functions.https.HttpsError("permission-denied", "هذه المحادثة ليست لك.");
    }
    await deleteSupportThreadDeep(uid, ref);
    return { ok: true, threadId: ref.id };
  });

// ─── الصوت القانوني: تطبيع Opus + بصمة SHA-256 + نشر مختوم بالمحتوى ─────
// خطّ أنابيب واحد للسنوات القادمة (معمارية «المكتبة الكاملة» 2026-08-29):
// اللوحة ترفع الأصل كما هو (ثابت مسار الرفع لا يُمَسّ)، وهذه الدالة — في
// بيئة ffmpeg محكومة وموحّدة لا في هواتف المشرفين — تُطبّع الملف إلى صيغة
// المكتبة (Opus أحادي 24kbps voip) أو تُبقيه إن كان Opus/Ogg مضغوطاً أصلاً،
// ثم تحسب بصمة SHA-256 للبايتات **النهائية المُقدَّمة**، وترفعها إلى مسار
// مختوم بالمحتوى serving/{sha256}.ogg (الرابط ≡ البايتات بالتعريف، والدروس
// المتطابقة تتشارك الكائن نفسه)، وتتحقق من الحجم، **وآخر خطوة فقط** تكتب
// وثيقة الدرس (audioUrl + sha256 + sizeBytes + durationSeconds) — ففشلٌ في
// أي منتصف يُبقي الدرس على رابطه القديم الصالح. العملية idempotent بمفتاح
// البصمة، والأصل يبقى في مساره الأول ولا يُحذف.
const AUDIO_SERVING_PREFIX = "serving/";
const AUDIO_OPUS_KBPS = 24; // قابل للتغيير قبل أي ترميز مستقبلي إن حكم السمع بغيره
const AUDIO_KEEP_MAX_BPS = 48000; // ogg/opus دون هذا يُبقى كما هو (لا إعادة ترميز للمضغوط)
const AUDIO_MAX_SOURCE_BYTES = 200 * 1024 * 1024;
const AUDIO_JOB_MAX_ATTEMPTS = 4;
// قاعدة بناء الرابط: تبديل المضيف مستقبلاً (R2 خلف نطاق وسائط) = تغيير هذه
// القاعدة وحدها ثم backfill للروابط — البصمة في المسار ثابتة فلا يفسد كاش.
const MEDIA_BASE_URL = process.env.MEDIA_BASE_URL || "";

function audioBinaries() {
  let ffmpeg = null;
  let ffprobe = null;
  try { ffmpeg = require("ffmpeg-static"); } catch (e) { /* غير مثبّتة */ }
  try { ffprobe = require("ffprobe-static").path; } catch (e) { /* غير مثبّتة */ }
  return { ffmpeg, ffprobe };
}

function probeAudioFile(ffprobe, filePath) {
  const { spawnSync } = require("child_process");
  const out = spawnSync(ffprobe, [
    "-v", "error", "-print_format", "json",
    "-show_format", "-show_streams", filePath,
  ], { encoding: "utf8", maxBuffer: 8 * 1024 * 1024 });
  if (out.status !== 0) {
    throw new Error("ffprobe failed: " + String(out.stderr || "").slice(0, 300));
  }
  const parsed = JSON.parse(out.stdout || "{}");
  const stream = (parsed.streams || []).find((s) => s.codec_type === "audio") || {};
  const format = parsed.format || {};
  const durationSec = Number(stream.duration || format.duration || 0);
  return {
    durationSec: Number.isFinite(durationSec) ? durationSec : 0,
    codec: String(stream.codec_name || ""),
    container: String(format.format_name || ""),
  };
}

async function processLessonAudioCanonical(lessonId) {
  const fs = require("fs");
  const os = require("os");
  const path = require("path");
  const { spawnSync } = require("child_process");
  const { ffmpeg, ffprobe } = audioBinaries();
  if (!ffmpeg || !ffprobe) throw new Error("ffmpeg/ffprobe binaries unavailable");

  const ref = db.collection("lessons").doc(lessonId);
  const snap = await ref.get();
  if (!snap.exists) return { skipped: "missing" };
  const raw = snap.data() || {};
  const data = (raw.data && typeof raw.data === "object") ? raw.data : raw;
  const audioUrl = cleanString(data.audioUrl || raw.audioUrl, 2000);
  if (!audioUrl) return { skipped: "no-audio" };
  const doneSha = cleanString(raw.sha256, 80);
  if (doneSha && cleanString(raw.audioUrlAtSha, 2000) === audioUrl) {
    return { skipped: "already-canonical" };
  }

  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "minbar-audio-"));
  try {
    // 1) جلب المصدر: من دلونا مباشرةً إن كان مساره لدينا، وإلا عبر https.
    const srcPath = path.join(tmpDir, "source.bin");
    const storagePath = cleanString(raw.audioStoragePath || raw.storagePath
      || data.audioStoragePath || data.storagePath, 600);
    if (storagePath && !storagePath.startsWith(AUDIO_SERVING_PREFIX)) {
      await bucket.file(storagePath).download({ destination: srcPath });
    } else {
      const response = await fetch(audioUrl);
      if (!response.ok) throw new Error("source fetch " + response.status);
      const bytes = Buffer.from(await response.arrayBuffer());
      if (bytes.length > AUDIO_MAX_SOURCE_BYTES) throw new Error("source too large");
      fs.writeFileSync(srcPath, bytes);
    }
    const srcSize = fs.statSync(srcPath).size;
    if (srcSize <= 0) throw new Error("empty source");

    // 2) التطبيع: Opus/Ogg مضغوط أصلاً يُبقى بايتاً ببايت، وغيره يُرمَّز.
    const probe = probeAudioFile(ffprobe, srcPath);
    const derivedBps = probe.durationSec > 0 ? (srcSize * 8) / probe.durationSec : 0;
    const keepAsIs = (probe.codec === "opus" || probe.codec === "vorbis")
      && probe.container.includes("ogg")
      && derivedBps > 0 && derivedBps <= AUDIO_KEEP_MAX_BPS;
    let canonicalPath = srcPath;
    if (!keepAsIs) {
      canonicalPath = path.join(tmpDir, "canonical.ogg");
      const enc = spawnSync(ffmpeg, [
        "-hide_banner", "-loglevel", "error", "-y", "-i", srcPath,
        "-vn", "-ac", "1", "-c:a", "libopus",
        "-b:a", AUDIO_OPUS_KBPS + "k", "-application", "voip",
        canonicalPath,
      ], { encoding: "utf8", maxBuffer: 8 * 1024 * 1024 });
      if (enc.status !== 0 || !fs.existsSync(canonicalPath)) {
        throw new Error("ffmpeg failed: " + String(enc.stderr || "").slice(0, 300));
      }
    }
    const finalProbe = keepAsIs ? probe : probeAudioFile(ffprobe, canonicalPath);
    const sizeBytes = fs.statSync(canonicalPath).size;
    const sha256 = crypto.createHash("sha256")
      .update(fs.readFileSync(canonicalPath)).digest("hex");

    // 3) نشر مختوم بالمحتوى — idempotent: الكائن الموجود بنفس الحجم لا يُرفع.
    const servingPath = AUDIO_SERVING_PREFIX + sha256 + ".ogg";
    const servingFile = bucket.file(servingPath);
    const [exists] = await servingFile.exists();
    let token = crypto.randomUUID();
    if (exists) {
      const [meta] = await servingFile.getMetadata();
      if (Number(meta.size) !== sizeBytes) throw new Error("sha collision/size mismatch");
      const existing = (meta.metadata || {}).firebaseStorageDownloadTokens;
      if (existing) token = String(existing).split(",")[0];
      else await servingFile.setMetadata({ metadata: { firebaseStorageDownloadTokens: token } });
    } else {
      await bucket.upload(canonicalPath, {
        destination: servingPath,
        metadata: {
          contentType: "audio/ogg",
          cacheControl: "public, max-age=31536000, immutable",
          metadata: { firebaseStorageDownloadTokens: token, canonicalSha256: sha256 },
        },
      });
      const [meta] = await servingFile.getMetadata();
      if (Number(meta.size) !== sizeBytes) throw new Error("upload size mismatch");
    }
    const servingUrl = MEDIA_BASE_URL
      ? MEDIA_BASE_URL.replace(/\/$/, "") + "/" + servingPath
      : "https://firebasestorage.googleapis.com/v0/b/" + encodeURIComponent(bucket.name)
        + "/o/" + encodeURIComponent(servingPath) + "?alt=media&token=" + token;

    // 4) آخر خطوة: كتابة الوثيقة ذرياً — البيانات الوصفية تصف حرفياً بايتات
    // الرابط المنشور (invariant المراجعة الرابعة)، والأصل يُحفظ في legacyAudio
    // مرةً واحدة ليبقى rollback كاملاً (رابط+بصمة+حجم معاً لا رابطاً وحده).
    await db.runTransaction(async (tx) => {
      const fresh = await tx.get(ref);
      if (!fresh.exists) return;
      const current = fresh.data() || {};
      const currentData = (current.data && typeof current.data === "object") ? current.data : current;
      const currentUrl = cleanString(currentData.audioUrl || current.audioUrl, 2000);
      if (currentUrl !== audioUrl) return; // استُبدل الصوت أثناء عملنا — دورة قادمة تلتقطه.
      const update = {
        audioUrl: servingUrl,
        sha256,
        sizeBytes,
        durationSeconds: Math.round(finalProbe.durationSec),
        audioCodec: "opus",
        audioServingPath: servingPath,
        audioUrlAtSha: servingUrl,
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      };
      if (!current.legacyAudio) {
        update.legacyAudio = {
          audioUrl,
          storagePath: storagePath || "",
          keptAsIs: keepAsIs,
        };
      }
      if (current.data && typeof current.data === "object") {
        update["data.audioUrl"] = servingUrl;
        update["data.updatedAt"] = new Date().toISOString();
      }
      tx.update(ref, update);
    });
    await db.collection("audio_jobs").doc(lessonId).set({
      status: "done", sha256, sizeBytes, attempts: 0,
      finishedAtMs: Date.now(),
    }, { merge: true });
    return { ok: true, sha256, sizeBytes, keepAsIs };
  } finally {
    try { fs.rmSync(tmpDir, { recursive: true, force: true }); } catch (e) { /* تنظيف */ }
  }
}

async function recordAudioJobFailure(lessonId, error) {
  const ref = db.collection("audio_jobs").doc(lessonId);
  try {
    const snap = await ref.get();
    const attempts = Number((snap.data() || {}).attempts || 0) + 1;
    await ref.set({
      status: "failed", attempts,
      lastError: String((error && error.message) || error).slice(0, 500),
      lastAttemptMs: Date.now(),
    }, { merge: true });
    if (attempts === AUDIO_JOB_MAX_ATTEMPTS) {
      await writeAdminAlert("", "⚠️ تعذّر تطبيع صوت درس",
        "الدرس " + lessonId + " فشل تطبيعه " + attempts + " مرات — يبقى على رابطه الأصلي ويحتاج نظرة.",
        { type: "audio_job_failed", lessonId });
    }
  } catch (e) {
    console.error("recordAudioJobFailure", lessonId, e);
  }
}

// مشغّل فوري: أي درس كتب/بدّل صوته يُطبَّع. حارس الخروج المبكر يمنع الدوران
// الذاتي (كتابتنا تضع sha256 + audioUrlAtSha المطابق) ويتجاهل كتابات views.
exports.onLessonAudioCanonical = functions
  .runWith({ timeoutSeconds: 540, memory: "1GB" })
  .firestore.document("lessons/{id}")
  .onWrite(async (change, context) => {
    if (!change.after.exists) return null;
    const raw = change.after.data() || {};
    const data = (raw.data && typeof raw.data === "object") ? raw.data : raw;
    const audioUrl = cleanString(data.audioUrl || raw.audioUrl, 2000);
    if (!audioUrl) return null;
    if (cleanString(raw.sha256, 80)
      && cleanString(raw.audioUrlAtSha, 2000) === audioUrl) return null;
    // لا تُعاد المحاولة تلقائياً بعد استنفاد المحاولات — الكنّاس يتولى الجدولة.
    const job = await db.collection("audio_jobs").doc(context.params.id).get();
    if (Number((job.data() || {}).attempts || 0) >= AUDIO_JOB_MAX_ATTEMPTS) return null;
    try {
      await processLessonAudioCanonical(context.params.id);
    } catch (error) {
      console.error("onLessonAudioCanonical", context.params.id, error);
      await recordAudioJobFailure(context.params.id, error);
    }
    return null;
  });

// كنّاس دوري: يلتقط ما فشل (بمحاولات دون السقف) وما فات المشغّل لأي سبب.
exports.audioJobsSweep = functions
  .runWith({ timeoutSeconds: 540, memory: "1GB" })
  .pubsub.schedule("every 6 hours")
  .onRun(async () => {
    const failed = await db.collection("audio_jobs")
      .where("status", "==", "failed")
      .where("attempts", "<", AUDIO_JOB_MAX_ATTEMPTS)
      .limit(5).get();
    for (const doc of failed.docs) {
      try {
        await processLessonAudioCanonical(doc.id);
      } catch (error) {
        await recordAudioJobFailure(doc.id, error);
      }
    }
    return null;
  });

// إعادة محاولة يدوية من المالك لدرس بعينه (بعد إصلاح سبب الفشل مثلاً).
exports.normalizeLessonAudio = functions
  .runWith({ timeoutSeconds: 540, memory: "1GB" })
  .https.onCall(async (data, context) => {
    await assertOwner(context);
    const lessonId = requireString(data && data.lessonId, "lessonId", 400);
    await db.collection("audio_jobs").doc(lessonId)
      .set({ attempts: 0, status: "retry" }, { merge: true });
    const result = await processLessonAudioCanonical(lessonId);
    return { ok: true, result };
  });
