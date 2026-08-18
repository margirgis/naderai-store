# خطة اختبار Nader AI SMS Reader v1.1.26

## الاختبارات الستة المطلوبة

### Test 1 — مطابقة مبلغ صحيح (600.50 → SUCCESS)
- أنشئ طلب بقيمة 600.50.
- أرسل رسالة: `تم استلام مبلغ 600.50 جنيه من رقم 01012345678 ...`.
- **المتوقع:** الحالة تصبح COMPLETED، ولا يُضاف الرصيد مرتين.

### Test 2 — مبلغ غير مطابق (600.50 طلب / 600.05 تحويل → REJECT)
- أنشئ طلب بقيمة 600.50.
- أرسل رسالة بقيمة 600.05.
- **المتوقع:** الحالة AMOUNT_MISMATCH، لا يضاف رصيد، الإشعار يظهر مرة واحدة فقط.

### Test 3 — نفس Transaction ID مرتين
- أنشئ طلب وأرسل رسالة tx=111.
- أرسل رسالة أخرى بنفس tx=111.
- **المتوقع:** LocalSmsQueue ترفض التكرار، وإذا تم فحصها فإن RPC `atomic_confirm_payment_order` يرفض لأن tx محجوز.

### Test 4 — SMS تصل قبل الطلب
- أرسل رسالة فودافون كاش قبل ظهور الطلب في التطبيق.
- بعدها أنشئ الطلب المطابق.
- **المتوقع:** يُعثر عليها في LocalSmsQueue فورًا بدون حاجة لقراءة صندوق الرسائل.

### Test 5 — إغلاق التطبيق وإعادة فتحه
- أغلق التطبيق تمامًا (Kill).
- أعد فتحه.
- **المتوقع:** الطلبات السابقة تُحمل من OrderStorage، والـ Snapshot يحافظ على البيانات الأساسية.

### Test 6 — تحديث APK
- ثبّت الإصدار الجديد فوق الإصدار القديم.
- **المتوقع:** لا يُمسح التخزين المحلي (OrderStorage.markVersion فقط)، والطلبات تبقى.

## كيفية التشغيل

```bash
cd /workspace/app-dpgpkghtekg1/tasks/vodafone-cash-sms-reader
./gradlew test
```

> ملاحظة: بيئة التطوير الحالية لا تحتوي على Android Gradle Wrapper، لذا يجب تشغيل الاختبارات على جهاز يحتوي على Android SDK.

## ملفات الاختبار

- `app/src/test/java/com/naderai/smsreader/SmsParserTest.kt`
- `app/src/test/java/com/naderai/smsreader/OrderSnapshotTest.kt`
- `app/src/test/java/com/naderai/smsreader/TaskScannerLockTest.kt`
- `app/src/test/java/com/naderai/smsreader/EndToEndScenarioTest.kt`
