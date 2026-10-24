package com.spendsnap.ui

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.PhonelinkLock
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.RateReview
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.genai.common.DownloadStatus
import com.spendsnap.BuildConfig
import com.spendsnap.ThemeMode
import com.spendsnap.ai.AiEngineKind
import com.spendsnap.ai.NanoState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.spendsnap.container
import com.spendsnap.detection.PaymentAccessibilityService
import com.spendsnap.domain.CsvExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

internal fun isAccessibilityEnabled(context: Context): Boolean {
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    val ours = ComponentName(context, PaymentAccessibilityService::class.java)
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == ours }
}

internal fun isNotificationListenerEnabled(context: Context): Boolean =
    context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

/** Design 19 (Settings) and 20 (Delete all data). */
@Composable
fun SettingsScreen(onDetection: () -> Unit, onPrivacy: () -> Unit, onMessage: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = context.container
    val settings = container.settings
    val scope = rememberCoroutineScope()
    val count = remember { container.repository.observeAll() }.collectAsStateWithLifecycle(emptyList()).value.size
    val theme by settings.theme.collectAsStateWithLifecycle()
    var accessibilityOn by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    // Local copies so switches redraw immediately; each change is written through to AppSettings.
    var googlePay by remember { mutableStateOf(settings.googlePayEnabled) }
    var phonePe by remember { mutableStateOf(settings.phonePeEnabled) }
    var reviewFirst by remember { mutableStateOf(settings.reviewBeforeSaving) }
    var alerts by remember { mutableStateOf(settings.paymentAlerts) }
    var diagnostics by remember { mutableStateOf(settings.diagnosticsEnabled) }
    val aiOn by settings.aiEnabled.collectAsStateWithLifecycle()
    var categorize by remember { mutableStateOf(settings.autoCategorize) }
    val engine by settings.aiEngine.collectAsStateWithLifecycle()
    var consentFor by remember { mutableStateOf<AiEngineKind?>(null) }
    var nanoState by remember { mutableStateOf<NanoState?>(null) }
    var nanoProgress by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(engine) { if (engine == AiEngineKind.ON_DEVICE) nanoState = container.nano.state() }
    LifecycleResumeEffect(Unit) {
        accessibilityOn = isAccessibilityEnabled(context)
        onPauseOrDispose { }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            onMessage(
                try {
                    val all = container.repository.getAll()
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(CsvExporter.toCsv(all, ZoneId.systemDefault()).toByteArray()) }
                    }
                    "Exported ${all.size} transactions"
                } catch (e: Exception) {
                    "Export failed: ${e.message}"
                },
            )
        }
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        GroupLabel("Detection")
        SettingRow(
            Icons.Rounded.AccessibilityNew, "Accessibility detection",
            if (accessibilityOn) "On · Google Pay and PhonePe" else "Off · Tap to set up",
            onClick = onDetection,
        ) { Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        SwitchRow(Icons.Rounded.AccountBalanceWallet, "Google Pay", "Detect payments made in Google Pay", googlePay) {
            googlePay = it; settings.googlePayEnabled = it
        }
        SwitchRow(Icons.Rounded.AccountBalance, "PhonePe", "Detect payments made in PhonePe", phonePe) {
            phonePe = it; settings.phonePeEnabled = it
        }
        SwitchRow(
            Icons.Rounded.RateReview, "Review before saving",
            if (reviewFirst) "Detected payments count only after you check them" else "Detected payments count right away",
            reviewFirst,
        ) { reviewFirst = it; settings.reviewBeforeSaving = it }
        SwitchRow(Icons.Rounded.NotificationsActive, "Payment alerts", "Notify me when a payment is detected", alerts) {
            alerts = it; settings.paymentAlerts = it
        }

        GroupLabel("AI Insights")
        SwitchRow(
            Icons.Rounded.AutoAwesome, "AI Insights",
            when {
                !aiOn -> "Off. No AI runs"
                engine == AiEngineKind.ON_DEVICE -> "Summaries, answers and categories with Gemini Nano on this phone"
                else -> "Summaries, answers and categories via Google’s Gemini API (cloud)"
            },
            aiOn,
        ) { on -> if (on) consentFor = engine else settings.setAiEnabled(false) }

        Text(
            "AI engine",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 56.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp)) {
            AiEngineKind.entries.forEachIndexed { i, kind ->
                SegmentedButton(
                    selected = engine == kind,
                    onClick = {
                        // Moving to the cloud while AI is on sends data off the phone: ask first.
                        if (kind == AiEngineKind.CLOUD && aiOn && engine != kind) consentFor = kind else settings.setAiEngine(kind)
                    },
                    shape = SegmentedButtonDefaults.itemShape(i, AiEngineKind.entries.size),
                ) { Text(if (kind == AiEngineKind.ON_DEVICE) "On-device (Nano)" else "Cloud (API)", maxLines = 1) }
            }
        }
        if (engine == AiEngineKind.ON_DEVICE) {
            SettingRow(
                Icons.Rounded.PhonelinkLock, "Gemini Nano",
                nanoProgress ?: nanoState?.label ?: "Checking…",
            ) {
                if (nanoState == NanoState.DOWNLOADABLE && nanoProgress == null) {
                    TextButton(onClick = {
                        scope.launch {
                            container.nano.download().collect { s ->
                                nanoProgress = when (s) {
                                    is DownloadStatus.DownloadStarted -> "Starting download…"
                                    is DownloadStatus.DownloadProgress -> "Downloading… ${s.totalBytesDownloaded / 1_000_000} MB"
                                    is DownloadStatus.DownloadFailed -> "Download failed: ${s.e.message}"
                                    else -> null
                                }
                            }
                            nanoState = container.nano.state()
                        }
                    }) { Text("Download") }
                }
            }
        } else {
            SettingRow(
                Icons.Rounded.Cloud, "Gemini API",
                if (container.cloud.isConfigured) "Key from api.txt · ${BuildConfig.GEMINI_MODEL}" else "No API key in this build (add it to api.txt)",
            )
        }
        SwitchRow(
            Icons.Rounded.Category, "Auto-categorize payments",
            if (categorize) "Rules on this phone first; the AI suggests the rest" else "Off. Only on-phone rules and your own categories",
            categorize,
        ) { categorize = it; settings.autoCategorize = it }

        GroupLabel("Appearance")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            ThemeMode.entries.forEachIndexed { i, mode ->
                SegmentedButton(
                    selected = theme == mode,
                    onClick = { settings.setTheme(mode) },
                    shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                ) { Text(mode.label) }
            }
        }

        GroupLabel("Your data")
        SettingRow(
            Icons.Rounded.Download, "Export to CSV",
            "Save $count transaction${if (count == 1) "" else "s"} to a file you choose",
            onClick = { exportLauncher.launch("spendsnap-${LocalDate.now()}.csv") },
        )
        SettingRow(
            Icons.Rounded.DeleteForever, "Delete all data", "Removes every transaction stored on this phone",
            danger = true,
            onClick = { confirmDeleteAll = true },
        )

        GroupLabel("Advanced")
        SettingRow(Icons.Rounded.Shield, "Privacy information", "What is stored and what never leaves this phone", onClick = onPrivacy) {
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SwitchRow(
            Icons.Rounded.BugReport, "Diagnostic screen dump",
            "Writes Google Pay/PhonePe screen text to logcat (adb logcat -s SpendSnapDump) for tuning rules. Turn off when done.",
            diagnostics,
        ) { diagnostics = it; settings.diagnosticsEnabled = it }
    }

    consentFor?.let { kind ->
        AiConsentDialog(
            onDevice = kind == AiEngineKind.ON_DEVICE,
            onDismiss = { consentFor = null },
            onConfirm = {
                consentFor = null
                settings.setAiEngine(kind)
                settings.setAiEnabled(true)
            },
        )
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            icon = { Icon(Icons.Rounded.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete all data?") },
            text = {
                Text(
                    "This permanently removes all $count transaction${if (count == 1) "" else "s"} stored on this phone. " +
                        "It can’t be undone. Export to CSV first if you want a copy.",
                )
            },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") } },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteAll = false
                    scope.launch {
                        container.repository.deleteAll()
                        onMessage("All data deleted")
                    }
                }) { Text("Delete all", color = MaterialTheme.colorScheme.error) }
            },
        )
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    danger: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, null, tint = if (danger) scheme.error else scheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (danger) scheme.error else scheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingRow(icon, title, subtitle, onClick = { onChange(!checked) }) {
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun PrivacyScreen(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        listOf(
            "Internet" to "Used only by AI Insights, which is off until you turn it on. Then your questions, this week’s category totals and payee names the app can’t categorise go to Google’s Gemini API. Screenshots, UPI references and account numbers are never sent. No analytics, tracking or sync.",
            "What is stored" to "Only per payment: description, amount, app, time, verification status, capture method, an optional UPI reference and your category. Stored in a local database on this phone and excluded from cloud backup.",
            "Screenshots" to "Shared screenshots are read once for on-device OCR (ML Kit, bundled model) and never copied or saved by SpendSnap.",
            "Never collected" to "UPI PIN, OTP, passwords, login data, account balances, or text from any app other than Google Pay and PhonePe.",
            "Accuracy" to "Text on a payment screen or notification is not proof that money was transferred. Unclear captures are marked unverified and sent to Review; nothing is guessed.",
            "Your control" to "Turn off detection any time in Android Settings → Accessibility / Notification access. Export everything to CSV, or delete entries individually. Uninstalling removes all data.",
        ).forEach { (title, body) ->
            SectionCard {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
