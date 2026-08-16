package com.naderai.appstore

import android.app.Application
import android.util.Log

class NaderAiApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Global uncaught exception handler — يمنع crash صامت
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("NaderAI", "Uncaught crash on ${thread.name}: ${throwable.message}", throwable)
        }
        Log.d("NaderAI", "Application started v${BuildConfig.VERSION_NAME}")
    }
}
