package com.goushik.upiwallet.util

import android.content.Intent
import android.net.Uri

/**
 * The app's only outbound-payment path (the donate button). The app never handles money itself: the
 * donate button opens a hosted payment page ([DONATE_PAGE_URL]) in the user's browser, and the
 * payment happens entirely outside the app. There is no payment SDK and no in-app UPI intent — this
 * keeps the app's "nothing leaves your phone" guarantee intact (the browser, not the app, makes the
 * request).
 *
 * The caller launches this via an ActivityResult launcher and treats a *return* as "donated"
 * (payment results are not observable from here), wrapping the launch in try/catch for the
 * no-browser edge case.
 */
object Upi {
    /** The hosted donate page, opened in the user's default browser. */
    const val DONATE_PAGE_URL = "https://razorpay.me/@goushikganesan"

    /** The donate intent: open the hosted donate page in the user's browser. */
    fun donatePageIntent(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(DONATE_PAGE_URL))
}
