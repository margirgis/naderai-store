package com.naderai.app

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.webkit.*
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import com.naderai.app.databinding.ActivityMainBinding
import com.naderai.app.sms.SmsReaderService
import com.naderai.app.sms.WebAppInterface

/**
 * النشاط الرئيسي: يعرض الموقع كاملاً داخل WebView.
 * يضيف JavaScript bridge للتطبيق (SMS، Device ID، حالة الخدمة).
 * يبدأ SmsReaderService في الخلفية.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    companion object {
        // رابط الموقع الأساسي (Supabase backend)
        const val SITE_URL = "https://ccimllgqdxuvymdeikmn.supabase.co"
        // رابط الموقع الفعلي (تطبيق Nader AI المباشر)
        const val APP_URL = "https://g1.appmedo.com"

        // extra key لتمرير رابط دعوة اختياري من InviteActivity
        const val EXTRA_URL = "extra_url"
    }

    private var startUrl: String = APP_URL

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // إذا جاء رابط دعوة من شاشة الدعوة، نحمله بدلاً من الصفحة الافتراضية
        startUrl = intent.getStringExtra(EXTRA_URL) ?: APP_URL

        setupWebView()
        handleIntent(intent)

        // تشغيل خدمة قراءة الرسائل في الخلفية
        SmsReaderService.start(this)

        // زر فتح شاشة اختبار SMS
        binding.fabSmsTest.setOnClickListener {
            startActivity(Intent(this, SmsTestActivity::class.java))
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val wv = binding.webView
        val settings = wv.settings

        // إعدادات WebView
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mediaPlaybackRequiresUserGesture = false
        settings.userAgentString = "NaderAI-Android/${BuildConfig.VERSION_NAME} " + settings.userAgentString

        // JavaScript Bridge — التطبيق يكشف واجهة "NaderAI" للموقع
        wv.addJavascriptInterface(WebAppInterface(this), "NaderAI")

        // WebViewClient: يمنع فتح روابط خارجية في المتصفح
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                return when {
                    // السماح بنطاقات التطبيق والـ Supabase
                    url.contains("appmedo.com") || url.contains("medo.dev") || url.contains("supabase.co") -> false
                    // روابط البريد/الهاتف تفتح خارجياً
                    url.startsWith("mailto:") || url.startsWith("tel:") -> {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        true
                    }
                    // باقي الروابط تبقى داخل WebView
                    else -> false
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                binding.progressBar.visibility = View.GONE
                // إخفاء splash بعد تحميل أول صفحة
                binding.splashView.animate().alpha(0f).setDuration(400).withEndAction {
                    binding.splashView.visibility = View.GONE
                }.start()
                // حقن معلومات التطبيق في الصفحة
                injectAppInfo(view)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    binding.progressBar.visibility = View.GONE
                    // إظهار صفحة خطأ بسيطة
                    view.loadData(buildErrorPage(), "text/html; charset=utf-8", "UTF-8")
                }
            }
        }

        // WebChromeClient: يدعم console.log وحوارات JS
        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (newProgress < 100) {
                    binding.progressBar.visibility = View.VISIBLE
                } else {
                    binding.progressBar.visibility = View.GONE
                }
            }

            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                android.util.Log.d("WebView", "[${msg.sourceId()}:${msg.lineNumber()}] ${msg.message()}")
                return true
            }
        }

        // تفعيل WebView debugging في debug build
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        wv.loadUrl(startUrl)
    }

    /** حقن معلومات التطبيق في الصفحة بعد التحميل */
    private fun injectAppInfo(wv: WebView) {
        val deviceId = com.naderai.app.sms.DeviceInfo.getDeviceId(this)
        val version = BuildConfig.VERSION_NAME
        val script = """
            (function() {
                window.__NADERAI_APP__ = {
                    version: '$version',
                    deviceId: '$deviceId',
                    platform: 'android',
                    smsEnabled: true
                };
                // إطلاق حدث لإعلام الصفحة بأن التطبيق جاهز
                window.dispatchEvent(new CustomEvent('naderai_app_ready', {
                    detail: window.__NADERAI_APP__
                }));
            })();
        """.trimIndent()
        wv.evaluateJavascript(script, null)
    }

    /** زر Back: يتنقل داخل WebView بدلاً من إغلاق التطبيق */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && binding.webView.canGoBack()) {
            binding.webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** معالجة deep links: naderai://open?url=... */
    private fun handleIntent(intent: Intent) {
        val data = intent.data ?: return
        if (data.scheme == "naderai") {
            val targetUrl = data.getQueryParameter("url")
            if (!targetUrl.isNullOrEmpty()) {
                binding.webView.loadUrl(targetUrl)
            }
        }
    }

    private fun buildErrorPage() = """
        <!DOCTYPE html>
        <html dir="rtl">
        <head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>
          body{font-family:sans-serif;display:flex;flex-direction:column;align-items:center;
               justify-content:center;height:100vh;margin:0;background:#F5F3FF;color:#1F2937;}
          h2{color:#7C3AED;margin-bottom:8px;} p{color:#6B7280;text-align:center;padding:0 24px;}
          button{margin-top:24px;padding:14px 32px;background:#7C3AED;color:#fff;border:none;
                 border-radius:12px;font-size:16px;cursor:pointer;}
        </style></head>
        <body>
          <h2>⚡ Nader AI</h2>
          <p>تعذر الاتصال بالموقع. تحقق من اتصالك بالإنترنت ثم أعد المحاولة.</p>
          <button onclick="location.reload()">إعادة المحاولة</button>
        </body></html>
    """.trimIndent()

    override fun onDestroy() {
        super.onDestroy()
        binding.webView.destroy()
    }
}
