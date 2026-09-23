package com.goushik.upiwallet.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log

/**
 * The app's only logger for anything that can carry personal data (screen text, SMS bodies, payee names,
 * amounts, bank reference numbers). It writes to logcat ONLY in a debuggable build: a release build (the
 * one friends and F-Droid users run) logs nothing through it, so `adb logcat` or a bug report can never
 * leak a payment. [init] runs once from the Application; until then — and in plain JVM unit tests, where
 * `android.util.Log` is a stub that throws — every call is a no-op.
 *
 * Messages are lambdas so the string is never even built when logging is off.
 */
object Dbg {
    const val TAG = "UpiWallet"

    @Volatile var enabled: Boolean = false
        private set

    fun init(context: Context) {
        enabled = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    inline fun d(msg: () -> String) { if (enabled) Log.d(TAG, msg()) }
    inline fun v(msg: () -> String) { if (enabled) Log.v(TAG, msg()) }
    inline fun i(msg: () -> String) { if (enabled) Log.i(TAG, msg()) }
    inline fun w(msg: () -> String) { if (enabled) Log.w(TAG, msg()) }
    inline fun w(t: Throwable, msg: () -> String) { if (enabled) Log.w(TAG, msg(), t) }

    /** Tagged variant for the learning-mode harvest, which uses its own tag. */
    inline fun d(tag: String, msg: () -> String) { if (enabled) Log.d(tag, msg()) }
}
