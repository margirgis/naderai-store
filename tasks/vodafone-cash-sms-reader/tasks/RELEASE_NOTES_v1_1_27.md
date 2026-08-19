# Release Notes — v1.1.27 (versionCode 38)

**Release Date:** 2026-08-14  
**Branch:** main  
**Tag:** v1.1.27

---

## ما الجديد في هذا الإصدار

### 🔄 إدارة دورة حياة الطلب — المرحلة الثانية

هذا الإصدار يُكمل تحويل النظام من نموذج بسيط (pending/approved/rejected) إلى نموذج احترافي كامل بحالات منفصلة لحالة الطلب وحالة التحقق.

---

## تغييرات Android

### OrderStatus — حالات جديدة
| الحالة | الوصف | اللون |
|---|---|---|
| `ADMIN_OFFLINE` | الجهاز غير متصل — الطلب ينتظر | `#F97316` (برتقالي) |
| `WAITING_FOR_VERIFICATION` | ينتظر الجهاز ليبدأ الفحص | `#FBBF24` (أصفر) |
| `REOPENED` | أُعيد فتح طلب منتهي أو فاشل | `#8B5CF6` (بنفسجي) |

### OrderAdapter
- أيقونة `📵` لحالة `ADMIN_OFFLINE`
- أيقونة `🔓` لحالة `REOPENED`
- رسالة `orderFailureReason` واضحة لـ `ADMIN_OFFLINE`
- `scanProgress` يعرض نص مناسب للحالات الثلاث الجديدة
- أزرار الإجراءات تظهر لـ `ADMIN_OFFLINE` و`WAITING_FOR_VERIFICATION` و`REOPENED`

### item_order.xml — بطاقة احترافية
- تصميم جديد: مبلغ + كريدت في شبكة 2 عمود
- قسم مستقل للعميل (الاسم + الإيميل)
- قسم مستقل للمحوّل (رقم الهاتف + الاسم)
- جميع الحقول الـ 15 منظمة بترتيب واضح

### OrdersFragment — تأكيد يدوي صحيح ✅
- **قبل:** كان يولد `transactionId` وهمي (`manual-{id}-{timestamp}`)
- **بعد:** يُرسل مباشرة إلى `admin-manual-confirm` Edge Function
- Edge Function تُنفذ:
  1. التحقق من صحة الطلب
  2. قفل الطلب (SELECT FOR UPDATE)
  3. التحقق من عدم تكرار transaction_id
  4. التحقق من المبلغ
  5. إضافة الكريدت
  6. تحديث حالة الطلب
  7. (كل ذلك في transaction واحدة — all-or-nothing)
- **Fallback:** للطلبات القديمة بدون `paymentOrderId` يبقى المسار القديم

### SupabaseConfig — مسارات جديدة
```kotlin
getAdminManualConfirmUrl() → /functions/v1/admin-manual-confirm
getAdminReopenOrderUrl()   → /functions/v1/admin-reopen-order  
getAdminOpenCaseUrl()      → /functions/v1/admin-open-case
```

---

## تغييرات Web UI

### AdminTopupRequestsPage — إعادة كتابة كاملة

#### بطاقات الطلبات الاحترافية
- **16 حالة** مع لون مميز لكل منها (pill ملون + نقطة متحركة للحالات النشطة)
- قسم المبلغ والكريدت بشكل بارز في الأعلى
- زر نسخ للبريد الإلكتروني ورقم العملية
- تفاصيل قابلة للطي (محاولات الفحص، تاريخ الانتهاء، الجهاز، حالة التحقق)
- تحذير مضمّن لحالة `ADMIN_OFFLINE` مع توجيه للعميل

#### Dashboard Stats (من Backend مباشرة)
يُجلب من `get_topup_dashboard_stats` RPC:
- قيد الفحص | تم التأكيد | فشل | منتهي | ينتظر الاتصال | أُعيد فتحه | الكل (48h)

#### شريط حالة الجهاز
- 🟢 متصل / 🔴 غير متصل
- عدد الطلبات في الانتظار
- وقت آخر اتصال

#### Realtime (بدون Refresh)
- يشترك في `wallet_topup_requests` + `payment_orders`
- INSERT → يُضيف الطلب في الأعلى + toast
- UPDATE → يُحدّث البطاقة في مكانها بدون إعادة تحميل الصفحة

#### أزرار الإجراءات الجديدة
| الزر | متى يظهر | Edge Function |
|---|---|---|
| تأكيد يدوي | أي حالة غير نهائية | `admin-manual-confirm` |
| رفض | أي حالة غير نهائية | `wallet_topup_requests` update |
| إعادة الفحص | `not_found / amount_mismatch / no_match / failed` | `admin_rescan_topup_request` RPC |
| إعادة فتح الطلب | `expired / failed / rejected` | `admin-reopen-order` |
| فتح قضية | أي حالة غير مكتملة | `admin-open-case` |

#### Filters
`قيد المراجعة` ← `pending + scanning` (الافتراضي)  
`جميع الطلبات` | `معلق` | `جاري الفحص` | `تمت الموافقة` | `مرفوض` | `منتهي` | `أُعيد فتحه`

---

## Database (تم التطبيق مسبقاً)

Migration `00048` — مُطبَّق بالفعل:
- `wallet_topup_requests.verification_status` ENUM
- `payment_orders.verification_status` ENUM + حالة `REOPENED`
- جدول `support_cases`
- RPC: `reopen_payment_order`
- RPC: `admin_manual_confirm_order`
- RPC: `open_support_case`
- RPC: `get_topup_dashboard_stats`

---

## كيفية البناء

> **المتطلب:** Android Studio Hedgehog أو أحدث + Java 17

```bash
# من داخل مجلد المشروع
cd tasks/vodafone-cash-sms-reader

# Debug (للاختبار)
./gradlew assembleDebug

# Release (للنشر)
./gradlew assembleRelease \
  -Pandroid.injected.signing.store.file=signing/release.keystore \
  -Pandroid.injected.signing.store.password=naderai2024 \
  -Pandroid.injected.signing.key.alias=naderai \
  -Pandroid.injected.signing.key.password=naderai2024
```

ملف APK:
```
app/build/outputs/apk/release/app-release.apk
```

---

## سيناريوهات الاختبار المطلوبة

انظر `TEST_PLAN_v1_1_27.md`
