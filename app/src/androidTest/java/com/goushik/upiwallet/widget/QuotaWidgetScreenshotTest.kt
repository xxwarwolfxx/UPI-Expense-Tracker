package com.goushik.upiwallet.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import java.io.File

/**
 * Renders the QUOTA widget's real [android.widget.RemoteViews] — the exact objects the launcher
 * inflates — into PNGs so the layout, fonts, band colours and bar fills can be LOOKED AT instead of
 * assumed. The figures are a realistic ledger rather than tidy round numbers: a week well past its cap
 * (153%) alongside a month still under one (76%), so both band states appear in a single pass.
 *
 * Also logs the natural wrap-content height, which is what tells us the cell size to declare.
 */
class QuotaWidgetScreenshotTest {

    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val outDir: File get() = ctx.getExternalFilesDir(null)!!

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics,
    ).toInt()

    private fun snap(
        weekSpent: Long, weekCap: Long, monthSpent: Long, monthCap: Long,
    ) = WidgetSnapshot(
        onboarded = true,
        availablePaise = 0L,
        accounts = emptyList(),
        dayPaise = 0L, dayCount = 0,
        monthPaise = monthSpent, monthCount = 110,
        weekPaise = weekSpent, weekCount = 23,
        showBalance = false,
        monthBudgetLimitPaise = monthCap,
        weekBudgetLimitPaise = weekCap,
    )

    /** Inflate + measure + draw. Height 0 = wrap-content (returns the natural height). */
    private fun shoot(name: String, widthDp: Int, heightDp: Int, s: WidgetSnapshot): Int {
        var natural = 0
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val rv = WidgetRenderer.build(
                ctx, WidgetSize.QUOTA, 1, s, revealed = false, spendHidden = false,
            )
            val host = FrameLayout(ctx)
            val view = rv.apply(ctx, host)
            val w = dp(widthDp)
            val hSpec = if (heightDp <= 0) {
                View.MeasureSpec.makeMeasureSpec(dp(400), View.MeasureSpec.AT_MOST)
            } else {
                View.MeasureSpec.makeMeasureSpec(dp(heightDp), View.MeasureSpec.EXACTLY)
            }
            view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), hSpec)
            val h = view.measuredHeight
            natural = (h / ctx.resources.displayMetrics.density).toInt()
            view.layout(0, 0, w, h)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bmp))
            File(outDir, "$name.png").outputStream().use {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            Log.i(TAG, "$name → ${w}x${h}px  (natural ${natural}dp tall at ${widthDp}dp wide)")
        }
        return natural
    }

    // The reference fixture: one window over its cap, one under.
    private val real = snap(
        weekSpent = 1_077_967L, weekCap = 700_000L,      // ₹10,779.67 of ₹7,000 → 153%
        monthSpent = 6_115_303L, monthCap = 8_000_000L,  // ₹61,153.03 of ₹80,000 → 76%
    )

    @Test fun renderRealLedger() {
        val nat2 = shoot("quota_2x1_natural", 160, 0, real)
        Log.i(TAG, "NATURAL HEIGHT at 2-col width = ${nat2}dp")
        shoot("quota_2x1_at110", 160, 110, real)          // the height currently DECLARED
        shoot("quota_4x1_natural", 330, 0, real)
    }

    @Test fun renderOtherStates() {
        // near: 88% of the month cap; week unset so that row hides
        shoot("quota_near_only_month", 160, 0, snap(0L, 0L, 7_040_000L, 8_000_000L))
        // no caps at all → the empty line
        shoot("quota_empty", 160, 0, snap(0L, 0L, 0L, 0L))
    }

    private companion object { const val TAG = "QuotaShot" }
}
