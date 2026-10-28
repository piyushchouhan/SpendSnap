package com.spendsnap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.spendsnap.container
import com.spendsnap.domain.Direction
import com.spendsnap.domain.Inr
import com.spendsnap.domain.PaymentApp
import com.spendsnap.domain.ReviewIssue
import com.spendsnap.domain.Transaction
import com.spendsnap.domain.VerificationStatus
import com.spendsnap.domain.isVerified
import kotlinx.coroutines.launch

/**
 * Design 13: edit an entry in a bottom sheet. The Verified switch is the user's own confirmation;
 * turning it off puts the entry back into review. [onDeleted] receives the removed entry for Undo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTransactionSheet(id: Long, onDismiss: () -> Unit, onDeleted: (Transaction) -> Unit) {
    val repository = LocalContext.current.container.repository
    var original by remember { mutableStateOf<Transaction?>(null) }
    LaunchedEffect(id) {
        original = repository.get(id)
        if (original == null) onDismiss()
    }
    val t = original ?: return
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var description by remember(t) { mutableStateOf(t.description.orEmpty()) }
    var amountText by remember(t) { mutableStateOf(t.amountPaise?.let(Inr::toEditable).orEmpty()) }
    var category by remember(t) { mutableStateOf(t.category.orEmpty()) }
    var app by remember(t) { mutableStateOf(t.paymentApp) }
    var direction by remember(t) { mutableStateOf(t.direction) }
    var verified by remember(t) { mutableStateOf(t.isVerified) }
    var amountError by remember { mutableStateOf(false) }
    var descriptionError by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun save() {
        val amount = Inr.parseUserInput(amountText)
        amountError = amount == null
        descriptionError = description.isBlank()
        if (amount == null || description.isBlank()) return
        val duplicateIssue = setOfNotNull(ReviewIssue.POSSIBLE_DUPLICATE.takeIf { t.possibleDuplicateOfId != null })
        val (status, issues) = when {
            verified -> VerificationStatus.USER_CONFIRMED to duplicateIssue
            // Switched off by the user: back to review, not counted.
            t.isVerified -> VerificationStatus.UNVERIFIED to duplicateIssue + ReviewIssue.STATUS_UNVERIFIED
            else -> t.verificationStatus to t.reviewIssues
        }
        scope.launch {
            repository.save(
                t.copy(
                    description = description.trim(),
                    amountPaise = amount,
                    category = category.trim().ifEmpty { null },
                    paymentApp = app,
                    direction = direction,
                    verificationStatus = status,
                    reviewIssues = issues,
                ),
            )
            onDismiss()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        val scheme = MaterialTheme.colorScheme
        Column(
            Modifier.verticalScroll(rememberScrollState()).imePadding().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column {
                Text("Edit transaction", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "${dayAndTime(t.timestamp)} · ${t.extractionMethod.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                val open = t.reviewIssues - ReviewIssue.POSSIBLE_DUPLICATE
                if (open.isNotEmpty() && !verified) {
                    Text(
                        open.joinToString(" · ") { it.label },
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            t.possibleDuplicateOfId?.let { otherId ->
                Column(
                    Modifier.fillMaxWidth().background(scheme.errorContainer, RoundedCornerShape(16.dp)).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.ContentCopy, null, tint = scheme.onErrorContainer, modifier = Modifier.size(20.dp))
                        Text("Possible duplicate of entry #$otherId", style = MaterialTheme.typography.titleSmall, color = scheme.onErrorContainer)
                    }
                    Text("Not counted until you decide.", style = MaterialTheme.typography.bodySmall, color = scheme.onErrorContainer)
                    OutlinedButton(onClick = {
                        scope.launch {
                            repository.keepBoth(t)
                            original = repository.get(t.id)
                        }
                    }) { Text("Keep both") }
                }
            }

            OutlinedTextField(
                value = description,
                onValueChange = { description = it; descriptionError = false },
                label = { Text("Description") },
                isError = descriptionError,
                supportingText = if (descriptionError) ({ Text("Add a description") }) else null,
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it; amountError = false },
                label = { Text("Amount (INR)") },
                prefix = { Text("₹ ") },
                isError = amountError,
                supportingText = if (amountError) ({ Text("Enter an amount greater than ₹0") }) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = category,
                onValueChange = { category = it },
                label = { Text("Category (optional)") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Direction.entries.forEachIndexed { i, d ->
                    SegmentedButton(
                        selected = direction == d,
                        onClick = { direction = d },
                        shape = SegmentedButtonDefaults.itemShape(i, Direction.entries.size),
                    ) { Text(if (d == Direction.INCOME) "Income" else "Expense") }
                }
            }
            val apps = listOf(PaymentApp.GOOGLE_PAY, PaymentApp.PHONEPE, PaymentApp.CASH)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                apps.forEachIndexed { i, a ->
                    SegmentedButton(
                        selected = app == a,
                        onClick = { app = a },
                        shape = SegmentedButtonDefaults.itemShape(i, apps.size),
                        icon = { PaymentAppMark(a) },
                    ) { Text(a.label, maxLines = 1) }
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Icons.Rounded.Verified, null, tint = if (verified) scheme.primary else scheme.onSurfaceVariant)
                Column(Modifier.weight(1f)) {
                    Text("Verified", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (verified) "Confirmed against your payment app" else "Not yet checked. Turn on once it matches.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
                Switch(checked = verified, onCheckedChange = { verified = it })
            }

            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Rounded.Delete, null, tint = scheme.error, modifier = Modifier.size(18.dp))
                    Text("Delete", color = scheme.error, modifier = Modifier.padding(start = 8.dp))
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = ::save, modifier = Modifier.padding(start = 8.dp)) { Text("Save") }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete transaction?") },
            text = { Text("${t.description ?: "This entry"} · ${formatAmount(t.amountPaise)} will be removed from this phone.") },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        repository.delete(t)
                        onDeleted(t)
                        onDismiss()
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
        )
    }
}
