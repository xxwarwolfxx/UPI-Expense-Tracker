package com.goushik.upiwallet.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.goushik.upiwallet.data.Source
import com.goushik.upiwallet.di.ServiceLocator
import com.goushik.upiwallet.parse.RawCapture
import kotlinx.coroutines.launch

/**
 * Bank SMS — the durable floor (RRN + credits). A manifest receiver dies when onReceive returns, so it
 * CANNOT feed an in-process coroutine; it does its own insert+merge under goAsync() and is fully
 * independent of the a11y service being enabled.
 */
class SmsCaptureReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        // Concatenate multi-part PDUs per sender BEFORE parsing — the 12-digit RRN can land in part 2.
        val bySender = messages.groupBy { it.displayOriginatingAddress ?: it.originatingAddress ?: "?" }
        val now = System.currentTimeMillis()

        val pending = goAsync()
        ServiceLocator.appScope.launch {
            try {
                for ((sender, parts) in bySender) {
                    val body = parts.joinToString("") { it.displayMessageBody ?: it.messageBody ?: "" }
                    if (body.isBlank()) continue
                    val raw = RawCapture(Source.SMS, body, sender = sender, capturedAt = now)
                    val parsed = ServiceLocator.parserRegistry.parse(raw) ?: continue
                    val outcome = ServiceLocator.reconciler.onSms(parsed, body, sender)
                    Log.d(
                        TAG,
                        "SMS ${parsed.direction} amt=${parsed.amountPaise} rrn=${parsed.rrn} from $sender -> $outcome",
                    )
                }
                // Categorize the freshly-landed SMS row(s) — idempotent NULL-only sweep.
                com.goushik.upiwallet.domain.categorize.Categorization.run(ServiceLocator.repository)
                // Repaint placed widgets now (this process is already awake for the capture — no new wakeup).
                com.goushik.upiwallet.widget.WidgetUpdater.loadAndPush(context)
                // A fresh spend may have crossed a budget line — nudge (opt-in, deduped per period).
                com.goushik.upiwallet.domain.budget.BudgetAlerts.check(context)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val TAG = "UpiWallet"
    }
}
