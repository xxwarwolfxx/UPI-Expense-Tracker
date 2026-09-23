package com.goushik.upiwallet.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import com.goushik.upiwallet.di.ServiceLocator
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/**
 * Renders the quota widget from the app's LIVE database on this device — not a constructed fixture.
 * Run against the side-by-side test copy after restoring the real backup, so what comes out is exactly
 * what the widget would show on the home screen right now.
 */
class QuotaLiveRenderTest {

    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics,
    ).toInt()

    @Test fun renderFromLiveData() {
        ServiceLocator.init(ctx.applicationContext)
        val snap = runBlocking { WidgetData.load(ServiceLocator.repository) }
        Log.i(TAG, "LIVE onboarded=${snap.onboarded} " +
            "week=${snap.weekPaise} cap=${snap.weekBudgetLimitPaise} " +
            "month=${snap.monthPaise} cap=${snap.monthBudgetLimitPaise}")
        for ((name, w) in listOf("live_2x1" to 160, "live_4x1" to 330)) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val rv = WidgetRenderer.build(ctx, WidgetSize.QUOTA, 1, snap, false, false)
                val host = FrameLayout(ctx)
                val view = rv.apply(ctx, host)
                val pw = dp(w); val ph = dp(110)
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(pw, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(ph, View.MeasureSpec.EXACTLY),
                )
                view.layout(0, 0, pw, ph)
                val bmp = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bmp))
                File(ctx.filesDir, "$name.png").outputStream().use {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }
    }
    private companion object { const val TAG = "QuotaLive" }
}
