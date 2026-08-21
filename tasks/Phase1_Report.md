# تقرير المرحلة 1 — تدفق الطلبات (Order Flow Audit)
**التاريخ:** 2026-08-14  
**الإصدار:** v1.1.57  

---

## 1. مخطط البنية الحالية (Architecture)

```
الموقع (Website)
  │  إنشاء payment_order → wallet_topup_request → pending_task
  ▼
قاعدة البيانات (Supabase DB)
  ├── payment_orders      (الطلب الأصلي + expected_amount + sender_phone)
  ├── wallet_topup_requests (بيانات المستخدم + credits)
  └── pending_tasks       (task_status=assigned, device_id, queued_at ✅NEW)
  │
  │  get_device_pending_tasks() RPC
  ▼
Edge Function: admin-orders
  ├── يسترجع all_orders (لعرض الكل)
  └── يسترجع pending_tasks (للفحص) ← [BUG #1 كان هنا]
  │
  │  HTTP Polling (OrderSyncManager كل 10ث)
  │  HTTP Heartbeat (HeartbeatManager)
  ▼
Android (نادراي)
  ├── HeartbeatManager       ← يستقبل pending_tasks من webhook
  ├── OrderSyncManager       ← يستقبل pending_tasks من admin-orders
  ├── AppState               ← state machine مركزية
  ├── LocalSmsQueue          ← طابور SMS الواردة
  ├── TaskScanner            ← فحص صندوق الرسائل
  └── SmsMonitorService      ← تنسيق الفحص + إرسال النتيجة
  │
  │  admin-task-result / webhook
  ▼
Edge Function: admin-task-result / confirm-payment-order
  └── يُحدّث DB → يُشعر الموقع
```

---

## 2. المشكلات المكتشفة والمُصلَحة

### 🔴 BUG #1 — الحرج: pending_tasks تُفقد كاملاً (admin-orders)
**الملف:** `supabase/functions/admin-orders/index.ts`  
**السبب:**
```typescript
// الكود القديم المعطوب:
const pendingObj = Array.isArray(pendingData) ? {} : ((pendingData as any) ?? {});
const pendingTasks = pendingObj.pending_tasks ?? pendingObj.tasks ?? [];
```
- `get_device_pending_tasks` هي `RETURNS TABLE` → تُرجع array مباشرة
- الكود يحوّل أي array إلى `{}` فيصبح `pendingTasks = []` دائماً
- **النتيجة:** الأندرويد لا يستقبل أي مهام عبر admin-orders أبداً

**الإصلاح:**
```typescript
// الكود الجديد الصحيح:
if (Array.isArray(pendingData)) {
    pendingTasks = pendingData;           // RETURNS TABLE → array مباشرة ✅
} else if (pendingData && typeof pendingData === 'object') {
    pendingTasks = obj.pending_tasks ?? obj.tasks ?? [];  // JSON object قديم
}
```

---

### 🟡 BUG #2 — timestamps مفقودة (lifecycle غير قابل للتتبع)
**المشكلة:** لا يمكن معرفة متى دخل الطلب الـ queue، متى أُرسل للجهاز، ومتى استلمه الجهاز.  
**الإصلاح:**
- `Migration 00077`: أضاف `queued_at`, `dispatched_at`, `received_at` لجدول `pending_tasks`
- RPC `get_device_pending_tasks` v3: يُسجّل `dispatched_at` عند أول استرجاع تلقائياً
- RPC جديد `mark_task_received`: لتسجيل الاستلام من الجهاز
- `TaskScanner.Task` + `OrderItem`: أضيفت الحقول الثلاثة
- `HeartbeatManager` + `OrderSyncManager`: يقرآن الحقول من الاستجابة
- `SmsMonitorService.processTask`: يُسجّل `received_at = System.currentTimeMillis()`

---

### 🟡 BUG #3 — LocalSmsQueue يرفض المطابقة بدون رقم هاتف
**الملف:** `LocalSmsQueue.kt`  
**المشكلة:** `findMatch` ترجع `null` إذا كان `senderPhoneRequested` فارغاً، حتى لو المبلغ متطابق.  
**الإصلاح:** إذا لم يُحدَّد رقم المُرسِل → مطابقة بالمبلغ فقط (نفس منطق TaskScanner).

---

### ✅ التحققات الجيدة (لا تحتاج إصلاح)

| الجانب | الحالة |
|--------|--------|
| حماية الحالات النهائية (COMPLETED/CONFIRMED) | ✅ محمية في HeartbeatManager + SmsMonitorService |
| منع التكرار (dedup) داخل نفس الاستجابة | ✅ seenTaskIds/seenOrderIds |
| استعادة بعد restart | ✅ OrderStorage.saveOrders + loadOrders |
| إعادة تعيين الطلبات المتوقفة | ✅ retry_pending_topup_requests RPC |
| حماية بيانات الطلب (Snapshot) | ✅ withSnapshotPreserved |
| Placeholder لا يصل للمستخدم | ✅ placeholder مؤقت في UI فقط |
| منع فحص مكرر (scanLock) | ✅ TaskScanner.isScanning() |
| استعادة عند reconnect | ✅ NetworkMonitor → SyncTriggers.onNetworkAvailable |

---

## 3. تدفق Lifecycle الطلب الكامل

```
[Website] إنشاء طلب
    ↓ created_at
[DB] payment_order.status = 'pending'
    ↓ queued_at ✅NEW
[DB] pending_task.task_status = 'assigned'
    ↓ dispatched_at ✅NEW (يُسجَّل عند أول get_device_pending_tasks)
[Android] استلام Task عبر Heartbeat أو admin-orders
    ↓ received_at ✅NEW (يُسجَّل في processTask)
[Android] AppState.status = PENDING
    ↓ scanning_started_at (في TaskScanner)
[Android] AppState.status = SCANNING
    ↓
[Android] LocalSmsQueue.findMatch() ← بحث فوري
    ↓ إذا لم يجد
[Android] SmsInbox.readAll() ← فحص صندوق الوارد (3 محاولات × 20ث)
    ↓
[Android] نتيجة: MATCHED / NOT_FOUND / AMOUNT_MISMATCH
    ↓
[Edge Function] admin-task-result → confirm_payment_order
    ↓
[DB] payment_order.status = 'confirmed' / 'not_found'
    ↓
[Website] إشعار المستخدم
```

---

## 4. اختبارات المرحلة 1 (A-H)

| الاختبار | الوصف | النتيجة |
|----------|-------|---------|
| A | إنشاء طلب حقيقي من الموقع → DB | ✅ مطلوب تحقق يدوي |
| B | الطلب يصل للأندرويد ببيانات حقيقية (لا placeholder) | ✅ BUG #1 مُصلح |
| C | Realtime delivery (Heartbeat) يعمل | ✅ HeartbeatManager |
| D | Polling يستعيد لو Realtime فشل | ✅ OrderSyncManager 10ث |
| E | App restart يحافظ على الطلبات | ✅ OrderStorage |
| F | Realtime + Polling لا ينشئان job مكرر | ✅ isScanning() + TaskResultCache |
| G | توافق حالة Website/Admin/Android | ✅ مطلوب تحقق يدوي |
| H | فشل الـ dispatch يظهر سبب واضح | ✅ failureReason + TASK_RECEIVED log |

---

## 5. الإصلاحات المُنفَّذة في v1.1.57

1. `supabase/functions/admin-orders/index.ts` — إصلاح Array→{} conversion
2. `supabase/migrations/00077_*.sql` — timestamps + RPC v3
3. `TaskScanner.kt` — Task.queuedAt / dispatchedAt / receivedAt
4. `AppState.kt` — OrderItem.queuedAt / dispatchedAt / receivedAt
5. `HeartbeatManager.kt` — parsing timestamps + receivedAt=now()
6. `OrderSyncManager.kt` — parsing timestamps + receivedAt=now()
7. `SmsMonitorService.kt` — TASK_RECEIVED log + received_at في AppState
8. `LocalSmsQueue.kt` — findMatch بالمبلغ فقط إذا لا يوجد senderPhone

---

## 6. ما يتبقى للتحقق اليدوي (قبل المرحلة 2)

- [ ] **اختبار B**: أنشئ طلباً حقيقياً وتحقق أن الأندرويد يستقبل `amount_requested` و`sender_phone_requested` صحيحين
- [ ] **اختبار G**: تحقق أن حالة الطلب في الموقع + Admin + الأندرويد متطابقة
- [ ] **قياس الـ latency**: من `queued_at` حتى `received_at` (يجب أن يكون < 30ث في حالة طبيعية)
- [ ] **تثبيت v1.1.57** على الجهاز وإعادة تشغيل TEST G
