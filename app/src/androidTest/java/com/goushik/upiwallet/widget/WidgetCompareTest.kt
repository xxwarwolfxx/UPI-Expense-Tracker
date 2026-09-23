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
 * Renders the EXISTING widgets next to the new QUOTA one, each at its declared cell size, so the new
 * one can be judged against the set rather than against a browser mockup.
 */
class WidgetCompareTest {

    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val outDir: File get() = ctx.getExternalFilesDir(null)!!

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics,
    ).toInt()

    private val real = WidgetSnapshot(
        onboarded = true,
        availablePaise = 1_234_500L,
        accounts = listOf(WidgetAccount("HDFC", 1_800_000L), WidgetAccount("SBI", 13_600_000L)),
        dayPaise = 6_200L, dayCount = 1,
        monthPaise = 6_115_303L, monthCount = 110,
        weekPaise = 1_077_967L, weekCount = 23,
        showBalance = false,
        monthBudgetLimitPaise = 8_000_000L,
        weekBudgetLimitPaise = 700_000L,
    )

    private fun shoot(
        name: String, size: WidgetSize, wDp: Int, hDp: Int,
        paused: Boolean = false, stuck: Boolean = false, snap: WidgetSnapshot = real,
    ) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val rv = WidgetRenderer.build(ctx, size, 1, snap, revealed = false, spendHidden = false, capturePaused = paused, captureStuck = stuck)
            val host = FrameLayout(ctx)
            val view = rv.apply(ctx, host)
            val w = dp(wDp); val h = dp(hDp)
            view.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, w, h)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bmp))
            File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Log.i("WidgetCompare", "$name  ${wDp}x${hDp}dp")
        }
    }

    @Test fun renderTheWholeSet() {
        shoot("cmp_small",  WidgetSize.SMALL,  160, 110)
        shoot("cmp_budget", WidgetSize.BUDGET, 160, 110)
        shoot("cmp_quota",  WidgetSize.QUOTA,  160, 110)
        shoot("cmp_big",    WidgetSize.BIG,    330, 110)
    }

    /** The budget widget exactly at its cap ("0% limit reached"), past it ("125% of budget used") and in
     *  the ordinary "% left" state, at the declared cell and at the resize floor (140x96dp: narrower, a
     *  three-digit percent wraps; shorter, the two-line label loses its bottom). The test copy's "TEST · "
     *  labels are the longest case. */
    @Test fun renderBudgetEdges() {
        val atLimit = real.copy(monthPaise = real.monthBudgetLimitPaise)
        val over = real.copy(monthPaise = real.monthBudgetLimitPaise * 5 / 4)
        shoot("budget_limit",        WidgetSize.BUDGET, 160, 110, snap = atLimit)
        shoot("budget_over",         WidgetSize.BUDGET, 160, 110, snap = over)
        shoot("budget_limit_floor",  WidgetSize.BUDGET, 140,  96, snap = atLimit)
        shoot("budget_over_floor",   WidgetSize.BUDGET, 140,  96, snap = over)
        shoot("budget_normal_floor", WidgetSize.BUDGET, 140,  96)
    }

    /** The "capture paused" card every size shows while the a11y service is off — at the declared cells
     *  AND at the 90dp resize floor, so nothing clips (the quota widget once lost half a row at 110dp). */
    @Test fun renderPausedSet() {
        shoot("paused_small",  WidgetSize.SMALL,  160, 110, paused = true)
        shoot("paused_budget", WidgetSize.BUDGET, 160, 110, paused = true)
        shoot("paused_quota",  WidgetSize.QUOTA,  160, 110, paused = true)
        shoot("paused_big",    WidgetSize.BIG,    330, 110, paused = true)
        shoot("paused_large",  WidgetSize.LARGE,  330, 230, paused = true)
        shoot("paused_floor",  WidgetSize.SMALL,  100,  90, paused = true)
        shoot("paused_stuck",  WidgetSize.SMALL,  160, 110, paused = true, stuck = true)
        shoot("paused_stuck_floor", WidgetSize.SMALL, 100, 90, paused = true, stuck = true)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            // Natural height must fit the 110dp cell — the guard the quota widget taught us.
            val rv = WidgetRenderer.build(ctx, WidgetSize.SMALL, 1, real, revealed = false, spendHidden = false, capturePaused = true)
            val view = rv.apply(ctx, FrameLayout(ctx))
            view.measure(
                View.MeasureSpec.makeMeasureSpec(dp(160), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(400), View.MeasureSpec.AT_MOST),
            )
            val naturalDp = view.measuredHeight / ctx.resources.displayMetrics.density
            Log.i("WidgetCompare", "paused natural height = ${naturalDp}dp")
            check(naturalDp <= 90f) { "paused card is ${naturalDp}dp tall — must fit the 90dp resize floor" }
        }
    }
}
