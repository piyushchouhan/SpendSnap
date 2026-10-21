package com.spendsnap.detection

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.spendsnap.container
import com.spendsnap.domain.ExtractionMethod
import com.spendsnap.domain.PaymentApp
import com.spendsnap.domain.ReviewIssue
import com.spendsnap.domain.ScreenStatus
import com.spendsnap.parsing.PaymentExtractor
import com.spendsnap.parsing.toTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Reads notifications from Google Pay and PhonePe only; everything else is ignored unread.
 * A payment-app notification is posted after the payment is processed, so it is a second,
 * independent signal next to the success screen.
 */
class PaymentNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handled = LinkedHashSet<String>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val app = PaymentApp.fromPackage(sbn.packageName)?.takeIf { container.settings.detects(it) } ?: return
        val notification = sbn.notification
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = notification.extras
        val lines = listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT, Notification.EXTRA_SUB_TEXT)
            .mapNotNull { extras.getCharSequence(it)?.toString() }
            .distinct()
        if (lines.isEmpty()) return

        val extraction = PaymentExtractor.extract(lines) ?: return
        DiagnosticLog.dump(this, "notification/${app.name}/${extraction.status}", lines)
        if (extraction.status != ScreenStatus.SUCCESS && extraction.status != ScreenStatus.FAILED) return
        if (extraction.amountPaise == null) return

        // Apps re-post (update) the same notification; handle each one once.
        val key = "${sbn.key}|${extraction.status}|${extraction.amountPaise}"
        if (!handled.add(key)) return
        if (handled.size > 200) handled.remove(handled.first())

        val transaction = extraction.toTransaction(
            app,
            ExtractionMethod.NOTIFICATION,
            sbn.postTime,
            extraIssues = if (container.settings.reviewBeforeSaving) setOf(ReviewIssue.AWAITING_REVIEW) else emptySet(),
        )
        scope.launch {
            try {
                val id = container.repository.ingest(transaction)
                container.repository.get(id)?.let { CaptureNotifier.show(this@PaymentNotificationListener, it) }
            } catch (e: Exception) {
                Log.e("SpendSnap", "Could not store notification payment", e)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
