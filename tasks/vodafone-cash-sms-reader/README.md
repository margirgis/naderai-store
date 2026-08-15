# Vodafone Cash SMS Reader

تطبيق Android بسيط يقرأ رسائل فودافون كاش النصية ويرسل معلوماتها إلى Edge Function الموقع.

## مهمة الأمان

هذا التطبيق يركض على جهاز الأدمن/المالك ويقرأ الرسائل النصية فقط. يجب تأكيد الدفعات يدوياً أو من الواتساب.

## المتطلبات

- Android Studio Hedgehog أو أحدث
- Gradle 8.2+
- Java 17
- هاتف Android مع الـ SMS Receiver

## الإعدادات

1. فتح المشروع في Android Studio.
2. اذهب لأول صفحة (إعدادات) واملأ خانتين الفقط:

   **Supabase URL:**
   ```
   https://ccimllgqdxuvymdeikmn.supabase.co
   ```

   **Anon Key:**
   ```
   eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImNjaW1sbGdxZHh1dnltZGVpa21uIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODY2ODk3OTQsImV4cCI6MjEwMjI2NTc5NH0.intP2QkhXHswRigBpCYb127yNk3VAfj68rpS_Ujvies
   ```

3. اضغط **حفظ الإعدادات** ثم **تسجيل الجهاز**.
4. إذا بقي الاختبار بـ 404 — الصق من الـأول الى أسفل.

5. المالك الهاتف يجب أن يكون مفتوحاً ومتصلاً بالإنترنت.

## كيفية بناء وتحميل تطبيق Android

### الخطوة 1: فتح المشروع
افتح المجلد التالي في Android Studio:

```
/workspace/app-dpgpkghtekg1/tasks/vodafone-cash-sms-reader
```

أو إذا نقلته لجهازك:

```
C:\Android\vodafone-cash-sms-reader
```

### الخطوة 2: بناء ملف APK
فتح من نافذة Android Studio: **Terminal** (أو `Ctrl+~`):

```bash
./gradlew assembleDebug
```

أو من الويندوز:

```bash
gradlew.bat assembleDebug
```

### الخطوة 3: مكان ملف APK بعد البناء
بعد نجاح البناء، ستجد ملف APK في:

```
app/build/outputs/apk/debug/app-debug.apk
```

المسار الكامل من جذر المشروع:

```
/workspace/app-dpgpkghtekg1/tasks/vodafone-cash-sms-reader/app/build/outputs/apk/debug/app-debug.apk
```

### الخطوة 4: تثبيت APK على الهاتف

#### طريقة A: من Android Studio مباشرة
1. وصّل الهاتف بالـ USB.
2. فعّل **USB Debugging** من خيارات المطور.
3. اضغط **Run** (زر ▶️) في Android Studio.
4. سيتم تثبيت التطبيق وفتحه تلقائياً.

#### طريقة B: باستخدام ADB
أولاً، تأكد أن `adb` متاح في نفسك، ثم شغّل:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

#### طريقة C: نقل الملف يدوياً
1. أرسل `app-debug.apk` للهاتف (واتساب أو تيليجرام أو كابل).
2. على الهاتف، افتح الملف.
3. إذا ظهر تحذير "مصدر غير معروف"، اضغط **الإعدادات** وفعّل **السماح بالتثبيت من هذا المصدر**.
4. اضغط **تثبيت**.

### الخطوة 5: أول تشغيل
1. افتح التطبيق.
2. امنح إذن **SMS** عند الطلب.
3. التأكد من أن رابط الـ Webhook والسر مكتوبان بشكل صحيح.
4. أرسل رسالة فودافون كاش تجريبية أو اختبر من صفحة الويب.

### ملاحظات مهمة
- تأكد أن الجهاز مفتوح ومتصل بالإنترنت.
- لا تُغلق التطبيق تماماً؛ يجب أن يظل في الخلفية لاستقبال SMS.
- بعض الهواتف (شاومي/سامسونج) تحتاج إيقاف **تحسينات البطارية** للتطبيق.
- إذا لم يستلم التطبيق SMS، جرّب إعادة تشغيل الهاتف بعد منح الإذن.

## كيفية العمل

- لما يصل رسالة فودافون كاش لـ الهاتف، يقوم التطبيق تلقائياً بتحليل:
  - رقم المرسل
  - المبلغ
  - نص الرسالة
  - الوقت
- يرسل البيانات إلى الـ Edge Function، فيتحقق من مطابقتها مع أحد طلبات الشحن المعلقة ويضيف الرصيد تلقائياً.

## ملاحظات الأمان

- يمكن لأي شخص عنده الهاتف والسر إرسال ويبهوك مزيف. يفضّل تأكيد الدفعات يدوياً بعد الاستلام.
- لا تمسك رسائل البنوك أو رموز البطاقات المساحية.

## ملفات المكونات

- `MainActivity.kt` - واجهة الإعدادات
- `SmsReceiver.kt` - مستقبل الرسائل
- `WebhookSender.kt` - إرسال الويبهوك
