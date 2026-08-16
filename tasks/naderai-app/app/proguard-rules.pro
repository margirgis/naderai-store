# لا حاجة لقواعد Proguard — التطبيق WebView ولا يمرر أي كود عبر reflect
-keep class com.naderai.app.sms.WebAppInterface { *; }
-keep class com.naderai.app.sms.TaskScanner { *; }
-keepattributes JavascriptInterface
-dontwarn okhttp3.**
-dontwarn okio.**
