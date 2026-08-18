# Nader AI SMS Reader v1.1.26 — Release Notes

## الإصدار
- `versionCode = 37`
- `versionName = "1.1.26"`

## أهم الإصلاحات
1. **إزالة Scanner Loop**: كل task له lock واحد، ولا يُسمح بأكثر من 3 محاولات فحص.
2. **Parser جديد**: يقرأ `تم استلام مبلغ X` فقط، ولا يخلط بين مبلغ التحويل ورصيد المحفظة.
3. **Snapshot ثابت**: بيانات الطلب الأساسية لا تُمسح أثناء تحديثات السيرفر.
4. **بطاقة الطلب**: تصميم Premium مع ألوان حسب الحالة وعداد محاولات.
5. **Live Update**: Heartbeat 10s عند وجود مهام، 60s في الخمول، بالإضافة لـ OrderSyncManager كل 10s للأدمن.
6. **Atomic Confirm**: التحقق من sender_phone + transaction_id + amount + قفل + إضافة الرصيد + إكمال داخل عملية واحدة.
7. **Local SMS Queue**: dedup حسب tx_id، لا يُحذف إلا بعد نجاح المطابقة.
8. **أداء**: فحص واحد للـ task، قراءة SMS محدودة بـ 3 محاولات، فواصل Heartbeat متغيرة.

## كيفية البناء والنشر

1. على جهاز يحتوي على Android SDK:

```bash
cd /workspace/app-dpgpkghtekg1/tasks/vodafone-cash-sms-reader
./gradlew assembleRelease
```

2. سيتم إنشاء APK موقع في:
`app/build/outputs/apk/release/app-release.apk`

3. اختبر الـ 6 سيناريوهات الموجودة في `tasks/TEST_PLAN_v1_1_26.md`.

4. بعد التحقق:

```bash
git add -A
git commit -m "v1.1.26: atomic confirm, SMS parser, state machine, live sync"
git tag v1.1.26
git push origin main --tags
```

> ملاحظة: بيئة التطوير الحالية لا تحتوي على Git أو Android Gradle Wrapper، لذا يتم إعداد كل شيء جاهزًا للبناء والنشر يدويًا.
