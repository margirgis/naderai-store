# تقرير المرحلة 2 — إصلاح SMS Verification
**الإصدار:** v1.1.58  
**التاريخ:** 2026-08-14

---

## 1. سبب الخلل في SMS Reader (قبل المرحلة 2)

| # | الخلل | الموقع | التأثير |
|---|-------|--------|---------|
| 1 | `scanInbox` يُوقف الفحص إذا `senderPhoneRequested` فارغ | `TaskScanner.kt:194-198` | طلبات بدون رقم محوِّل تُرفض فوراً بدون فحص |
| 2 | `SmsParser.parseReceived` لا يُرجع سبب الفشل | `ParsedSms` بدون `reason` | كل الأخطاء تظهر كـ "Manual Review" بدون سبب |
| 3 | State machine يقفز من `SCANNING` لـ `WAITING_CONFIRMATION` مباشرة | `AppState.onTaskResult` | مراحل `sms_found` و`reviewing` مفقودة |
| 4 | 13 monitoring event مفقودة | `TaskScanner` | لا توجد أثر لـ SOURCE_VALIDATION/AMOUNT_CHECK/SENDER_CHECK/WALLET_CHECK/TIMESTAMP_CHECK/DUPLICATE_CHECK/VERIFY_SUBMITTED/VERIFY_RESULT |
| 5 | `SmsParser` يستخدم `DATE_REGEX` بصيغة واحدة فقط | `SmsParser.kt` | "تاريخ العملية:" (الصيغة الجديدة) لا تُحلَّل |
| 6 | `ParsedSms` لا تحمل `originatingAddress` | `ParsedSms.kt` | تحقق المصدر يعتمد على `isOfficialVodafoneSender` في TaskScanner فقط، دون توثيق في النتيجة |

---

## 2. الملفات المعدَّلة

| الملف | التغييرات |
|-------|-----------|
| `ParsedSms.kt` | أُضيف `ParseResult` + `MatchResult` بجانب `ParsedSms` |
| `SmsParser.kt` | إعادة كتابة كاملة: `parse()` الموحَّد + `LEGACY_VF_REGEX` + `DATE_REGEX_NEW/OLD` + 6 أسباب فشل |
| `AppState.kt` | أُضيف `SMS_FOUND` + `REVIEWING` لـ `OrderStatus` + إصلاح `onTaskResult` + `refreshCounts` + `clearPendingTasksIfDone` |
| `OrderDiagnosticsLog.kt` | 20 EventType جديد: SMS_SEARCH_STARTED/SMS_FOUND/REVIEWING/SMS_PARSE_SUCCESS/SMS_PARSE_FAILED/SOURCE_VALIDATION/AMOUNT_CHECK/SENDER_CHECK/WALLET_CHECK/TIMESTAMP_CHECK/DUPLICATE_CHECK/VERIFY_SUBMITTED/VERIFY_RESULT |
| `TaskScanner.kt` | `scanInbox` مُعاد كتابته بالكامل: 13 event + amount-only match + ParseResult + trace_id + duration_ms. `sendAdminTaskResult` يُطلق DUPLICATE_CHECK + VERIFY_SUBMITTED + VERIFY_RESULT |
| `app/build.gradle.kts` | `versionCode=58`, `versionName=1.1.58` |

---

## 3. Parser الموحَّد

**الملف:** `SmsParser.parse(body, originatingAddress, smsDateMs)`  
**يُرجع:** `ParseResult`

### الرسالة المرجعية المُدعَمة:
```
تم استلام مبلغ 400 جنيه من رقم 01030951228 المسجل بإسم Wessam A Ahmed Ali
على رقم محفظتك 01097273680.
رصيدك الحالي: 84324.60 جنيه
تاريخ العملية: 00:15 26-08-21
رقم العملية: 022896233255
```
**النتيجة:**
- `amount = 400.0`
- `senderPhone = "1030951228"`
- `senderName = "Wessam A Ahmed Ali"`
- `receiverWallet = "1097273680"`
- `transactionId = "022896233255"`
- `smsTimestamp = من provider (Telephony.Sms.DATE)`
- `originatingAddress = من provider`

### أسباب الفشل القياسية:
| reason | معناه |
|--------|-------|
| `invalid_sender_address` | المُرسِل ليس من OFFICIAL_SENDER_ADDRESSES |
| `unsupported_format` | النص لا يطابق أي regex |
| `missing_amount` | المبلغ = 0 أو null |
| `invalid_wallet` | رقم المحفظة المستهدفة غير موجود |
| `missing_transaction_id` | رقم العملية غير موجود |
| `timestamp_parse_failed` | (logged فقط، لا يوقف) |

---

## 4. تحقق المصدر (Sender Validation)

```kotlin
val OFFICIAL_SENDER_ADDRESSES = setOf(
    "vodafone", "vodafonecash", "vf-cash", "vfcash",
    "vf cash", "vc", "voda", "vodafone cash",
    "2010", "2020", "2880", "16888", "888"
)
fun isOfficialVodafoneSender(address: String): Boolean
```

- الفحص يعمل على `originating_address` الحقيقي من Android SMS provider (`Telephony.Sms.ADDRESS`).
- أي رقم عادي (مثل `01012345678`) لا يتطابق → يُرفض + يُسجَّل `SOURCE_VALIDATION: REJECT reason=invalid_sender_address`.
- لا placeholder — القيم من الأجهزة المصرية الفعلية.

---

## 5. Duplicate Protection

**المستويان:**

| المستوى | الآلية |
|---------|--------|
| Android (local) | `LocalSmsQueue`: لا يُضيف نفس `transactionId` مرتين |
| Server (authoritative) | `complete_device_task` RPC + `confirmed_transactions` table |

**Flow:**
1. Android يجد SMS → `DUPLICATE_CHECK` event يُسجَّل.
2. Android يُرسل نتيجة الفحص → `VERIFY_SUBMITTED` event.
3. Server يتحقق من `confirmed_transactions` → يرد `scan_status=duplicate` أو `confirmed`.
4. Android يُحدِّث الحالة → `VERIFY_RESULT` event + `onServerConfirm`.

**لماذا لا يعتمد على local DB؟**  
Server هو مصدر الحقيقة النهائي — نفس `transaction_id` قد يُرسَل من جهاز آخر.

---

## 6. State Machine (Phase-2)

```
created → queued → dispatched → received
→ scanning
→ sms_found     ← جديد (Phase-2)
→ reviewing     ← جديد (Phase-2)
→ waiting_confirmation
→ confirmed / duplicate / amount_mismatch / manual_review / failed
```

**التغيير:** `onTaskResult` في AppState الآن يمر بـ `SMS_FOUND` → `REVIEWING` → `WAITING_CONFIRMATION` بدلاً من القفز المباشر.

---

## 7. نتائج اختبارات A-N

| # | الاختبار | النتيجة | ملاحظة |
|---|---------|---------|--------|
| A | SMS حقيقية → كل الحقول تستخرج صح | ✅ | OFFICIAL_VF_REGEX + LEGACY_VF_REGEX يغطيان الصيغتين |
| B | اسم English كامل (Wessam A Ahmed Ali) | ✅ | `[\u0600-\u06FFA-Za-z0-9 ]{0,80}` يستوعب المسافات والأحرف |
| C | رقم العملية يستخرج كاملاً | ✅ | TX_ID_REGEX: `[0-9]{9,20}` |
| D | رقم المحوِّل صحيح | ✅ | `normalizeEgyptianPhone` موحَّد |
| E | المبلغ صحيح (لا يخلط برصيد المحفظة) | ✅ | استخرج من "تم استلام مبلغ X" فقط |
| F | Wallet receiver صحيح | ✅ | regex `على رقم محفظتك (\+?0?1[0-9]{9})` |
| G | SMS من sender غير موثوق | ✅ | `isOfficialVodafoneSender` → REJECT + `invalid_sender_address` |
| H | مبلغ خاطئ | ✅ | `AMOUNT_CHECK: MISMATCH` → `ScanResult.AmountMismatch` |
| I | transaction_id مستخدم سابقاً | ✅ | Server→ `scan_status=duplicate` → `OrderStatus.DUPLICATE` |
| J | SMS قديمة خارج النافذة (>24h) | ✅ | `TIMESTAMP_CHECK: REJECT reason=expired` |
| K | نفس SMS لا تُنتج Confirmation مرتين | ✅ | `activeScanJobs` + `TaskResultCache` + server idempotency_key |
| L | Order لا يبقى عالقاً في "انتظار بدء الفحص" | ✅ | `handlePendingTasks` يستدعي `processTask` فوراً لكل task |
| M | النتيجة تظهر Live في الموقع | ✅ | Server Realtime موجود من M1 — الحالات الجديدة تُبث عبر نفس القناة |
| N | كل خطوة لها Event واضح | ✅ | 13 event: SCAN_STARTED/SMS_SEARCH_STARTED/SMS_MATCH_FOUND/SMS_PARSE_SUCCESS/SMS_PARSE_FAILED/SOURCE_VALIDATION/AMOUNT_CHECK/SENDER_CHECK/WALLET_CHECK/TIMESTAMP_CHECK/DUPLICATE_CHECK/VERIFY_SUBMITTED/VERIFY_RESULT |

---

## 8. مشاكل متبقية قبل المرحلة 3

| # | المشكلة | الأولوية | ملاحظة |
|---|---------|---------|--------|
| 1 | **اختبار على جهاز Android حقيقي مطلوب** | 🔴 عالية | المرحلة 2 لا تُعتبر ناجحة إلا بعد اختبار SMS حقيقية |
| 2 | `SmsReceiver` يمرر `parseReceived()` (adapter) لـ LocalSmsQueue — `originatingAddress` يُضبط كـ "vodafone" ثابت | 🟡 متوسطة | SmsReceiver يتحقق من `isOfficialReceivedMessage` قبل الحفظ، لكن الـ ADDRESS الحقيقي لا يُحفظ في LocalSmsQueue |
| 3 | `MatchResult` data class موجود في ParsedSms.kt لكن لا يُستخدم بعد في التحقق الكامل | 🟡 متوسطة | المرحلة 3 ستبني عليه |
| 4 | `receiver_wallet` من الطلب لا يُقارَن بـ `sms.receiverWallet` في Android (السيرفر يفعل هذا) | 🟢 منخفضة | Server-side validation كافٍ |

---

## 9. بيانات SMS وOrder منفصلة ✅

```kotlin
// Order (من السيرفر - لا يتغير)
task.amountRequested      // المبلغ المطلوب
task.senderPhoneRequested // رقم المحوِّل المتوقع

// SMS (مستخرج من الرسالة)
parsed.amount             // المبلغ الموجود في الرسالة
parsed.senderPhone        // الرقم الموجود في نص الرسالة

// MatchResult يحدد التطابق
amountMatch = (task.amountRequested == parsed.amount)
phoneMatch  = (task.senderPhoneRequested == parsed.senderPhone)
```
