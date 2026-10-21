package com.spendsnap.detection

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.spendsnap.MainActivity
import com.spendsnap.R
import com.spendsnap.container
import com.spendsnap.domain.Transaction
import com.spendsnap.domain.VerificationStatus
import com.spendsnap.domain.countsTowardTotals
import com.spendsnap.ui.formatAmount

/**
 * Tells the user what automatic detection just recorded, so a wrong capture is caught right away.
 * One notification per entry (id = entry id): a pending payment's notification is updated in place
 * when it succeeds or fails.
 */
object CaptureNotifier {
    private const val CHANNEL = "captured_payments"
    const val EXTRA_OPEN_TRANSACTION = "open_transaction"

    fun show(context: Context, t: Transaction) {
        if (!context.container.settings.paymentAlerts) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannel(context)
        val app = t.paymentApp.label
        val title = when {
            t.verificationStatus == VerificationStatus.PENDING -> "Payment pending in $app"
            t.verificationStatus == VerificationStatus.FAILED -> "Payment failed in $app"
            t.countsTowardTotals -> "Payment recorded from $app"
            else -> "Payment detected in $app"
        }
        val what = formatAmount(t.amountPaise) + (t.description?.let { " · $it" } ?: "")
        val next = when {
            t.verificationStatus == VerificationStatus.PENDING -> "Waiting for the success screen."
            t.verificationStatus == VerificationStatus.FAILED -> "Not counted."
            t.countsTowardTotals -> "Added to your totals. Tap to check."
            else -> "Not counted yet. Review to confirm the amount."
        }
        val open = PendingIntent.getActivity(
            context,
            t.id.toInt(),
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_TRANSACTION, t.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_payment)
            .setContentTitle(title)
            .setContentText("$what. $next")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$what. $next"))
            .setContentIntent(open)
            .addAction(0, "Review", open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        NotificationManagerCompat.from(context).notify(t.id.toInt(), notification)
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Detected payments", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "What SpendSnap recorded from Google Pay and PhonePe"
            },
        )
    }
}
