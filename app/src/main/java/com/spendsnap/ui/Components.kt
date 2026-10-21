package com.spendsnap.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.spendsnap.domain.Inr
import com.spendsnap.domain.PaymentApp
import com.spendsnap.domain.Transaction
import com.spendsnap.domain.VerificationStatus
import com.spendsnap.domain.isVerified
import com.spendsnap.ui.theme.LocalExtraColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DATE_TIME = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a")
private val TIME = DateTimeFormatter.ofPattern("h:mm a")
private val SHORT_DAY = DateTimeFormatter.ofPattern("EEE, d MMM")

fun formatTimestamp(millis: Long): String = DATE_TIME.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

fun formatAmount(paise: Long?): String = paise?.let(Inr::format) ?: "₹ —"

fun timeOnly(millis: Long): String = TIME.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

/** "Today, 1:12 PM", "Yesterday, 7:22 PM", "Sun, 18 Oct, 9:49 AM". */
fun dayAndTime(millis: Long): String {
    val time = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    val day = when (time.toLocalDate()) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> SHORT_DAY.format(time)
    }
    return "$day, ${TIME.format(time)}"
}

data class AppTint(val container: Color, val content: Color)

@Composable
fun appTint(app: PaymentApp): AppTint {
    val extra = LocalExtraColors.current
    return when (app) {
        PaymentApp.GOOGLE_PAY -> AppTint(extra.googlePay, extra.onGooglePay)
        PaymentApp.PHONEPE -> AppTint(extra.phonePe, extra.onPhonePe)
        PaymentApp.CASH -> AppTint(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        PaymentApp.OTHER -> AppTint(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Launcher icons of the installed payment apps, decoded once. Keyed by package name. */
private val installedIcons = mutableMapOf<String, ImageBitmap?>()

/**
 * The payment app's own launcher icon as installed on this phone, so no brand artwork ships
 * with SpendSnap. Null when the app has no package (Cash, Other) or isn't installed.
 */
@Composable
fun installedAppIcon(app: PaymentApp): ImageBitmap? {
    val pkg = app.packageName ?: return null
    val pm = LocalContext.current.packageManager
    return remember(pkg) {
        installedIcons.getOrPut(pkg) {
            runCatching { pm.getApplicationIcon(pkg).toBitmap(144, 144).asImageBitmap() }.getOrNull()
        }
    }
}

/** Small app mark for "Paid with" choices: the real app icon, or a cash glyph. */
@Composable
fun PaymentAppMark(app: PaymentApp, size: Dp = 18.dp) {
    val icon = installedAppIcon(app)
    when {
        icon != null -> Image(icon, null, Modifier.size(size).clip(CircleShape))
        app == PaymentApp.CASH -> Icon(Icons.Rounded.Payments, null, Modifier.size(size))
        else -> Unit
    }
}

/**
 * Google Pay / PhonePe show their own installed icon. Cash shows a notes glyph. Anything else
 * falls back to [icon] or the first [letter] on a tinted circle.
 */
@Composable
fun AppAvatar(app: PaymentApp, letter: String? = null, icon: ImageVector? = null) {
    val appIcon = installedAppIcon(app)
    if (appIcon != null) {
        Image(appIcon, app.label, Modifier.size(40.dp).clip(CircleShape))
        return
    }
    val tint = appTint(app)
    val glyph = icon ?: Icons.Rounded.Payments.takeIf { app == PaymentApp.CASH }
    Box(Modifier.size(40.dp).background(tint.container, CircleShape), contentAlignment = Alignment.Center) {
        if (glyph != null) {
            Icon(glyph, null, tint = tint.content, modifier = Modifier.size(22.dp))
        } else {
            Text(letter.orEmpty(), color = tint.content, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun StatusBadge(transaction: Transaction) {
    val extra = LocalExtraColors.current
    val scheme = MaterialTheme.colorScheme
    val (icon, label, fg, bg) = when {
        transaction.verificationStatus == VerificationStatus.FAILED ->
            Quad(Icons.Rounded.Error, "Failed", scheme.onErrorContainer, scheme.errorContainer)
        transaction.isVerified -> Quad(Icons.Rounded.Verified, "Verified", scheme.primary, Color.Transparent)
        else -> Quad(Icons.Rounded.Schedule, "Unverified", extra.onWarningContainer, extra.warningContainer)
    }
    Row(
        Modifier.background(bg, RoundedCornerShape(6.dp)).padding(horizontal = if (bg == Color.Transparent) 0.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(14.dp))
        Text(label, color = fg, fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
    }
}

private data class Quad(val icon: ImageVector, val label: String, val fg: Color, val bg: Color)

@Composable
fun TransactionRow(transaction: Transaction, showDay: Boolean = true, onClick: () -> Unit) {
    val title = transaction.description ?: "No description"
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AppAvatar(transaction.paymentApp, letter = title.first().uppercase())
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${if (showDay) dayAndTime(transaction.timestamp) else timeOnly(transaction.timestamp)} · ${transaction.paymentApp.label}",
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(formatAmount(transaction.amountPaise), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            StatusBadge(transaction)
        }
    }
}

/** "🔒 Processed on this phone…" footnote used across capture screens. */
@Composable
fun PrivacyNote(text: String, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SectionCard(modifier: Modifier = Modifier, warning: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = if (warning) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}
