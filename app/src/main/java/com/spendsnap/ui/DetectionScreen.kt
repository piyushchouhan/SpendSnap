package com.spendsnap.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CurrencyRupee
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.NoPhotography
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

private val HOW_IT_WORKS = listOf(
    "You pay with Google Pay or PhonePe" to "Detection only runs inside these two apps.",
    "SpendSnap reads the confirmation text" to "The accessibility service reads on-screen text. No screenshot is taken.",
    "Text rules pick out amount and payee" to "The amount and note you type, then the success screen or chat bubble. All on this phone.",
    "You get a notification" to "Each capture is saved and announced. Tap it to fix anything that looks wrong.",
)

private val LIMITS = listOf(
    Icons.Rounded.Visibility to "Works only while the payment screens are on this phone. Payments made on another device aren’t detected.",
    Icons.Rounded.Update to "If Google Pay or PhonePe changes its layout, a payment may be missed or saved as Unverified for you to check.",
    Icons.Rounded.Block to "Secure screens show up black in screenshots. Enter those payments manually.",
    Icons.Rounded.BatteryAlert to "Android may stop the service to save battery. Re-enable it here if detection pauses.",
)

/** Designs 15–18: detection status, how it works, permission setup and limits. */
@Composable
fun DetectionScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var accessibilityOn by remember { mutableStateOf(false) }
    var listenerOn by remember { mutableStateOf(false) }
    var alertsOn by remember { mutableStateOf(false) }
    var showDisclosure by remember { mutableStateOf(false) }
    val alertsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { alertsOn = it }
    LifecycleResumeEffect(Unit) {
        accessibilityOn = isAccessibilityEnabled(context)
        listenerOn = isNotificationListenerEnabled(context)
        alertsOn = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        onPauseOrDispose { }
    }
    val openAccessibility = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
        StatusCard(accessibilityOn, onAction = { if (accessibilityOn) openAccessibility() else showDisclosure = true })

        Row(
            Modifier.padding(top = 12.dp).fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Column {
                Text("Accessibility is not screen capture", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Accessibility access lets SpendSnap read the text Google Pay and PhonePe show on screen. It can’t record your screen or take screenshots.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionLabel("How it works")
        HOW_IT_WORKS.forEachIndexed { i, (title, body) -> HowStep(i + 1, title, body, last = i == HOW_IT_WORKS.lastIndex) }

        SectionLabel("Setup")
        Column(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))) {
            PermissionRow(
                Icons.Rounded.AccessibilityNew, "Accessibility service",
                "Required for automatic detection. Reads text in Google Pay and PhonePe only.",
                on = accessibilityOn, onLabel = "On", offLabel = "Off",
                action = if (accessibilityOn) "Manage" else "Turn on",
                onAction = { if (accessibilityOn) openAccessibility() else showDisclosure = true },
            )
            PermissionRow(
                Icons.Rounded.Receipt, "Payment notifications",
                "Optional. Reads Google Pay and PhonePe notifications as a second confirmation. Others are ignored unread.",
                on = listenerOn, onLabel = "On", offLabel = "Off",
                action = if (listenerOn) "Manage" else "Turn on",
                onAction = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
            )
            PermissionRow(
                Icons.Rounded.Notifications, "Capture alerts",
                "Optional. Tells you what was recorded right after a payment is detected.",
                on = alertsOn, onLabel = "Allowed", offLabel = "Not allowed",
                action = if (alertsOn) null else "Allow",
                onAction = { alertsPermission.launch(Manifest.permission.POST_NOTIFICATIONS) },
            )
            PermissionRow(
                Icons.Rounded.LockOpen, "If Android blocks a switch",
                "Sideloaded apps need “Allow restricted settings”: App info → ⋮. If there’s no ⋮ menu, run: " +
                    "adb shell appops set com.spendsnap ACCESS_RESTRICTED_SETTINGS allow",
                on = null, onLabel = "", offLabel = "",
                action = "Open App info",
                onAction = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                },
                divider = false,
            )
        }

        SectionLabel("Limitations")
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(16.dp))) {
            LIMITS.forEachIndexed { i, (icon, text) ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (showDisclosure) {
        DisclosureDialog(
            onDismiss = { showDisclosure = false },
            onContinue = {
                showDisclosure = false
                openAccessibility()
            },
        )
    }
}

@Composable
private fun StatusCard(on: Boolean, onAction: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val bg = if (on) scheme.primaryContainer else scheme.surfaceContainer
    val fg = if (on) scheme.onPrimaryContainer else scheme.onSurface
    Column(Modifier.padding(top = 8.dp).fillMaxWidth().background(bg, RoundedCornerShape(28.dp)).padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).background(if (on) scheme.primary else scheme.surfaceContainerHighest, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Radar, null, tint = if (on) scheme.onPrimary else scheme.onSurfaceVariant) }
            Row(
                Modifier.background(if (on) scheme.background else scheme.surfaceContainerHighest, RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(6.dp).background(if (on) scheme.primary else scheme.outline, CircleShape))
                Text(if (on) "Active" else "Off", style = MaterialTheme.typography.labelMedium, color = if (on) scheme.onSurface else scheme.onSurfaceVariant)
            }
        }
        Text(
            if (on) "Automatic detection is on" else "Automatic detection is off",
            style = MaterialTheme.typography.headlineSmall,
            color = fg,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            if (on) {
                "SpendSnap watches Google Pay and PhonePe for payments you make. Each one is saved and you get a notification to check it."
            } else {
                "Turn on the accessibility service to record Google Pay and PhonePe payments without typing them in. You can still import screenshots."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = fg,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (on) {
            Button(onClick = onAction, modifier = Modifier.padding(top = 16.dp)) { Text("Manage in Android settings") }
        } else {
            Button(onClick = onAction, modifier = Modifier.padding(top = 16.dp)) { Text("Turn on") }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 24.dp, bottom = 12.dp),
    )
}

@Composable
private fun HowStep(n: Int, title: String, body: String, last: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(28.dp).background(scheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                Text("$n", style = MaterialTheme.typography.labelLarge, color = scheme.onSecondaryContainer)
            }
            if (!last) Box(Modifier.width(2.dp).height(40.dp).background(scheme.outlineVariant))
        }
        Column(Modifier.padding(bottom = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    on: Boolean?,
    onLabel: String,
    offLabel: String,
    action: String?,
    onAction: () -> Unit,
    divider: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, tint = scheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (on != null) {
                    Text(
                        if (on) onLabel else offLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (on) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                        modifier = Modifier.background(if (on) scheme.primaryContainer else scheme.surfaceContainerHighest, RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            if (action != null) {
                TextButton(onClick = onAction, modifier = Modifier.padding(top = 4.dp)) { Text(action) }
            }
        }
    }
    if (divider) Box(Modifier.padding(start = 56.dp).fillMaxWidth().height(1.dp).background(scheme.outlineVariant))
}

/** Design 16: shown before sending the user to Android's accessibility settings. */
@Composable
private fun DisclosureDialog(onDismiss: () -> Unit, onContinue: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.AccessibilityNew, null, tint = scheme.primary) },
        title = { Text("Detect payments automatically?", textAlign = TextAlign.Center) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("SpendSnap uses Android’s accessibility service to read the payment text Google Pay and PhonePe show while you pay.")
                listOf(
                    Icons.Rounded.Visibility to "Reads text only in Google Pay and PhonePe",
                    Icons.Rounded.CurrencyRupee to "Keeps only the payee description and amount",
                    Icons.Rounded.CloudOff to "Detection runs on this phone. Screen text is never uploaded",
                    Icons.Rounded.NoPhotography to "Doesn’t record your screen or take screenshots",
                ).forEach { (icon, text) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(icon, null, tint = scheme.primary, modifier = Modifier.size(20.dp))
                        Text(text, color = scheme.onSurface)
                    }
                }
                Text("Next, Android opens Accessibility settings.", style = MaterialTheme.typography.bodySmall)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
        confirmButton = { TextButton(onClick = onContinue) { Text("Continue") } },
    )
}
