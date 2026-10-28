package com.spendsnap.ui

import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.ImageNotSupported
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.TextSnippet
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spendsnap.AppContainer
import com.spendsnap.container
import com.spendsnap.domain.Direction
import com.spendsnap.domain.ExtractionMethod
import com.spendsnap.domain.Inr
import com.spendsnap.domain.PaymentApp
import com.spendsnap.domain.Transaction
import com.spendsnap.domain.VerificationStatus
import com.spendsnap.parsing.LineTag
import com.spendsnap.parsing.LineTagger
import com.spendsnap.parsing.PaymentExtractor
import kotlinx.coroutines.launch

enum class CaptureMode { SCREENSHOT, MANUAL, INCOME }

sealed interface ReviewPhase {
    /** 0 = recognizing text, 1 = matching rules, 2 = checking duplicates. */
    data class Reading(val step: Int) : ReviewPhase
    data class Failed(val title: String, val body: String) : ReviewPhase
    data object Ready : ReviewPhase
}

data class ReviewForm(
    val manual: Boolean,
    val direction: Direction = Direction.EXPENSE,
    val amountText: String = "",
    val description: String = "",
    val app: PaymentApp = PaymentApp.OTHER,
    val detectedAmount: String? = null,
    val detectedDescription: String? = null,
    val descriptionRule: String? = null,
    val amountCandidates: List<Long> = emptyList(),
    val lines: List<Pair<String, LineTag?>> = emptyList(),
    val reference: String? = null,
    val at: Long = System.currentTimeMillis(),
    val amountError: Boolean = false,
    val descriptionError: Boolean = false,
    /** An already-saved entry this looks like; the user must choose to save anyway. */
    val duplicate: Transaction? = null,
)

class ReviewPaymentViewModel(private val container: AppContainer, mode: CaptureMode) : ViewModel() {
    var phase by mutableStateOf<ReviewPhase>(ReviewPhase.Ready)
        private set
    var form by mutableStateOf(
        ReviewForm(manual = mode != CaptureMode.SCREENSHOT, direction = if (mode == CaptureMode.INCOME) Direction.INCOME else Direction.EXPENSE),
    )
        private set
    var saved by mutableStateOf(false)
        private set

    init {
        if (mode == CaptureMode.SCREENSHOT) {
            val uri = container.pendingScreenshot.value
            container.pendingScreenshot.value = null
            if (uri == null) phase = noAmount() else read(uri)
        }
    }

    fun read(uri: Uri) {
        viewModelScope.launch {
            phase = ReviewPhase.Reading(0)
            val lines = try {
                container.ocr.readLines(uri)
            } catch (e: Exception) {
                Log.w("SpendSnap", "OCR failed", e)
                phase = ReviewPhase.Failed("Couldn’t open the image", "Pick the screenshot again, or enter the payment manually.")
                return@launch
            }
            if (lines.isEmpty()) {
                phase = ReviewPhase.Failed(
                    "No text found",
                    "If the screenshot is black, the payment app protects that screen and Android doesn’t allow capturing it.",
                )
                return@launch
            }
            phase = ReviewPhase.Reading(1)
            val e = PaymentExtractor.extract(lines)
            if (e == null) {
                phase = ReviewPhase.Failed("This looks like a PIN or OTP screen", "It was not read further. Use the payment success screen instead.")
                return@launch
            }
            if (e.amountPaise == null && e.amountCandidates.isEmpty()) {
                phase = noAmount()
                return@launch
            }
            phase = ReviewPhase.Reading(2)
            val duplicate = e.amountPaise?.let { amount ->
                e.description?.let { container.repository.findLikelyDuplicate(amount, it, e.transactionReference, System.currentTimeMillis()) }
            }
            val text = lines.joinToString(" ").lowercase()
            val amount = e.amountPaise?.let(Inr::toEditable)
            form = ReviewForm(
                manual = false,
                amountText = amount.orEmpty(),
                description = e.description.orEmpty(),
                app = when {
                    "phonepe" in text -> PaymentApp.PHONEPE
                    "google pay" in text || "gpay" in text -> PaymentApp.GOOGLE_PAY
                    else -> PaymentApp.OTHER
                },
                detectedAmount = amount,
                detectedDescription = e.description,
                descriptionRule = e.descriptionRule,
                amountCandidates = e.amountCandidates.takeIf { it.size > 1 }.orEmpty(),
                lines = LineTagger.tag(lines, e.amountPaise, e.description),
                reference = e.transactionReference,
                duplicate = duplicate,
            )
            phase = ReviewPhase.Ready
        }
    }

    private fun noAmount() = ReviewPhase.Failed(
        "Couldn’t find a payment amount",
        "This image doesn’t look like a Google Pay or PhonePe payment confirmation, or the text is too small to read.",
    )

    fun enterManually() {
        form = ReviewForm(manual = true)
        phase = ReviewPhase.Ready
    }

    fun update(transform: ReviewForm.() -> ReviewForm) {
        form = form.transform()
    }

    fun save() {
        val (amount, description) = validated() ?: return
        viewModelScope.launch {
            val duplicate = form.duplicate
                ?: container.repository.findLikelyDuplicate(amount, description, form.reference, form.at, form.direction)
            if (duplicate != null) form = form.copy(duplicate = duplicate) else persist(amount, description)
        }
    }

    /** The user saw the duplicate warning and still wants a separate entry. */
    fun saveAnyway() {
        val (amount, description) = validated() ?: return
        viewModelScope.launch { persist(amount, description) }
    }

    private fun validated(): Pair<Long, String>? {
        val amount = Inr.parseUserInput(form.amountText)
        val description = form.description.trim()
        if (amount == null || description.isEmpty()) {
            form = form.copy(amountError = amount == null, descriptionError = description.isEmpty())
            return null
        }
        return amount to description
    }

    private suspend fun persist(amount: Long, description: String) {
        container.repository.save(
            Transaction(
                description = description,
                amountPaise = amount,
                paymentApp = form.app,
                timestamp = form.at,
                // The user checked the values on this screen: that is the verification.
                verificationStatus = VerificationStatus.USER_CONFIRMED,
                extractionMethod = if (form.manual) ExtractionMethod.MANUAL else ExtractionMethod.OCR,
                transactionReference = form.reference,
                direction = form.direction,
            ),
        )
        saved = true
    }
}

@Composable
fun ReviewPaymentScreen(mode: CaptureMode, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val container = LocalContext.current.container
    val vm = viewModel(key = "review-$mode") { ReviewPaymentViewModel(container, mode) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::read) }
    LaunchedEffect(vm.saved) { if (vm.saved) onDone() }

    val phase = vm.phase
    val title = when {
        phase is ReviewPhase.Reading -> "Reading payment"
        vm.form.direction == Direction.INCOME -> "Add income"
        vm.form.manual -> "New payment"
        else -> "Review payment"
    }
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone) { Icon(Icons.Rounded.Close, "Close") }
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            when (phase) {
                is ReviewPhase.Reading -> ReadingContent(phase.step)
                is ReviewPhase.Failed -> FailedContent(
                    phase,
                    onRetry = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onManual = vm::enterManually,
                )
                ReviewPhase.Ready -> ReadyContent(vm.form, vm::update)
            }
        }
        if (phase == ReviewPhase.Ready) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (vm.form.duplicate != null) {
                    OutlinedButton(onClick = vm::saveAnyway, modifier = Modifier.weight(1f).height(48.dp)) {
                        Text("Save anyway", color = MaterialTheme.colorScheme.error)
                    }
                    Button(onClick = onDone, modifier = Modifier.weight(1f).height(48.dp)) { Text("Cancel, don’t save") }
                } else {
                    OutlinedButton(onClick = onDone, modifier = Modifier.weight(1f).height(48.dp)) { Text("Cancel") }
                    Button(onClick = vm::save, modifier = Modifier.weight(1f).height(48.dp)) {
                        Icon(Icons.Rounded.Check, null, modifier = Modifier.size(20.dp))
                        Text("Save", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadingContent(step: Int) {
    val scheme = MaterialTheme.colorScheme
    val shimmer by rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "alpha",
    )
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        LinearProgressIndicator(Modifier.fillMaxWidth(), trackColor = scheme.primaryContainer)
        Text("Reading payment details", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("Recognizing text on this phone", "Matching amount and payee rules", "Checking for duplicates").forEachIndexed { i, label ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    when {
                        i < step -> Icon(Icons.Rounded.CheckCircle, null, tint = scheme.primary, modifier = Modifier.size(20.dp))
                        i == step -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else -> Icon(Icons.Rounded.RadioButtonUnchecked, null, tint = scheme.outlineVariant, modifier = Modifier.size(20.dp))
                    }
                    Text(label, style = MaterialTheme.typography.bodyMedium, color = if (i <= step) scheme.onSurface else scheme.onSurfaceVariant)
                }
            }
        }
        listOf(72.dp to 12.dp, 56.dp to 12.dp, 40.dp to 20.dp).forEach { (h, r) ->
            Box(Modifier.fillMaxWidth().height(h).alpha(shimmer).background(scheme.surfaceContainerHigh, RoundedCornerShape(r)))
        }
        PrivacyNote("Processed on this phone. Nothing is uploaded.", Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun FailedContent(failed: ReviewPhase.Failed, onRetry: () -> Unit, onManual: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(88.dp).background(scheme.errorContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.ImageNotSupported, null, tint = scheme.onErrorContainer, modifier = Modifier.size(44.dp))
        }
        Text(failed.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
        Text(failed.body, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Column(
            Modifier.fillMaxWidth().padding(top = 8.dp).background(scheme.surfaceContainerLow, RoundedCornerShape(16.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Tip(Icons.Rounded.CheckCircle, "Use the payment success screen")
            Tip(Icons.Rounded.CheckCircle, "Make sure the amount and payee aren’t cropped")
            Tip(Icons.Rounded.Lock, "The image was not saved")
        }
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Try another screenshot") }
        OutlinedButton(onClick = onManual, modifier = Modifier.fillMaxWidth()) { Text("Enter manually") }
    }
}

@Composable
private fun Tip(icon: ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ReadyContent(form: ReviewForm, update: (ReviewForm.() -> ReviewForm) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Source card
        Row(
            Modifier.fillMaxWidth().background(scheme.surfaceContainerLow, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppAvatar(form.app, icon = if (form.manual) Icons.Rounded.EditNote else Icons.Rounded.Image)
            Column {
                Text(
                    when {
                        form.direction == Direction.INCOME -> if (form.app == PaymentApp.CASH) "Received in cash" else "Money received"
                        form.manual && form.app == PaymentApp.CASH -> "Paid in cash"
                        form.manual -> "Entered manually"
                        form.app == PaymentApp.OTHER -> "Read from screenshot"
                        else -> "Detected in ${form.app.label}"
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    (if (form.manual) "Manual entry" else "Screenshot · on-device OCR") + " · " + dayAndTime(form.at),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }

        if (form.manual) {
            val directions = Direction.entries
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                directions.forEachIndexed { i, d ->
                    SegmentedButton(
                        selected = form.direction == d,
                        onClick = { update { copy(direction = d, duplicate = null) } },
                        shape = SegmentedButtonDefaults.itemShape(i, directions.size),
                    ) { Text(if (d == Direction.INCOME) "Income (money in)" else "Expense (money out)") }
                }
            }
        }

        form.duplicate?.let { DuplicateCard(it) }

        val amountEdited = !form.manual && form.amountText != form.detectedAmount.orEmpty()
        LabeledField(
            value = form.amountText,
            onChange = { v -> update { copy(amountText = v, amountError = false, duplicate = null) } },
            label = "Amount (INR)",
            prefix = "₹",
            big = true,
            keyboard = KeyboardType.Decimal,
            error = form.amountError,
            help = when {
                form.amountError -> "Enter an amount greater than ₹0"
                form.manual -> "Amount in rupees, e.g. 349 or 1,286.50"
                form.detectedAmount == null -> "Several amounts on screen. Pick or type the right one."
                amountEdited -> "Edited by you"
                else -> "Matched rule: ₹ amount on the confirmation screen"
            },
            edited = amountEdited,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (form.amountCandidates.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                form.amountCandidates.forEach { paise ->
                    FilterChip(
                        selected = Inr.parseUserInput(form.amountText) == paise,
                        onClick = { update { copy(amountText = Inr.toEditable(paise), amountError = false, duplicate = null) } },
                        label = { Text(Inr.format(paise)) },
                    )
                }
            }
        }

        val descriptionEdited = !form.manual && form.description != form.detectedDescription.orEmpty()
        LabeledField(
            value = form.description,
            onChange = { v -> update { copy(description = v, descriptionError = false, duplicate = null) } },
            label = "Description",
            placeholder = if (form.direction == Direction.INCOME) "Who paid you" else "Who you paid",
            error = form.descriptionError,
            help = when {
                form.descriptionError -> "Add a description"
                form.manual && form.direction == Direction.INCOME -> "Who paid you or what it was for, e.g. Salary"
                form.manual -> "Who you paid or what it was for"
                form.detectedDescription == null -> "Not found on the screenshot"
                descriptionEdited -> "Edited by you"
                else -> "Matched rule: text after “${form.descriptionRule ?: "Paid to"}”"
            },
            edited = descriptionEdited,
        )

        Column {
            Text(if (form.direction == Direction.INCOME) "Received in" else "Paid with", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
            val apps = listOf(PaymentApp.GOOGLE_PAY, PaymentApp.PHONEPE, PaymentApp.CASH)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                apps.forEachIndexed { i, app ->
                    SegmentedButton(
                        selected = form.app == app,
                        onClick = { update { copy(app = app) } },
                        shape = SegmentedButtonDefaults.itemShape(i, apps.size),
                        icon = { PaymentAppMark(app) },
                    ) { Text(app.label, maxLines = 1) }
                }
            }
        }

        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.Schedule, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Text(dayAndTime(form.at), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }

        if (form.lines.isNotEmpty()) RecognizedText(form.lines)

        PrivacyNote(
            (if (form.manual) "Stored only on this phone." else "Screenshot discarded after reading. Nothing left this phone.") +
                " Saving marks this entry as Verified.",
            Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun DuplicateCard(existing: Transaction) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(scheme.errorContainer, RoundedCornerShape(16.dp)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.ContentCopy, null, tint = scheme.onErrorContainer, modifier = Modifier.size(22.dp))
            Text("Possible duplicate", style = MaterialTheme.typography.titleMedium, color = scheme.onErrorContainer)
        }
        Text(
            "You already saved ${formatAmount(existing.amountPaise)} ${if (existing.direction == Direction.INCOME) "from" else "to"} " +
                "${existing.description ?: "this payee"} via " +
                "${existing.paymentApp.label} · ${dayAndTime(existing.timestamp)}. Saving again would count it twice.",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onErrorContainer,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun LabeledField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    help: String,
    edited: Boolean,
    error: Boolean,
    modifier: Modifier = Modifier,
    prefix: String? = null,
    placeholder: String? = null,
    big: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
) {
    val scheme = MaterialTheme.colorScheme
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        prefix = prefix?.let { { Text(it, fontSize = 32.sp, color = scheme.onSurfaceVariant) } },
        placeholder = placeholder?.let { { Text(it) } },
        trailingIcon = { Icon(Icons.Rounded.Edit, null, tint = scheme.onSurfaceVariant) },
        textStyle = if (big) TextStyle(fontSize = 32.sp, lineHeight = 40.sp) else MaterialTheme.typography.bodyLarge,
        supportingText = { Text(help, color = if (error) scheme.error else if (edited) scheme.primary else scheme.onSurfaceVariant) },
        isError = error,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun RecognizedText(lines: List<Pair<String, LineTag?>>) {
    val scheme = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(true) }
    Column(Modifier.fillMaxWidth().border(1.dp, scheme.outlineVariant, RoundedCornerShape(16.dp))) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Rounded.TextSnippet, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text("Recognized text", style = MaterialTheme.typography.titleSmall)
                Text("Shown so you can check the rules. Not saved.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = scheme.onSurfaceVariant)
        }
        if (open) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                lines.forEach { (text, tag) ->
                    val (bg, fg) = when (tag) {
                        LineTag.AMOUNT -> scheme.primaryContainer to scheme.onPrimaryContainer
                        LineTag.DESCRIPTION -> scheme.tertiaryContainer to scheme.onTertiaryContainer
                        LineTag.ANCHOR -> scheme.surfaceContainerHigh to scheme.onSurface
                        null -> Color.Transparent to scheme.onSurfaceVariant
                    }
                    Row(
                        Modifier.fillMaxWidth().background(bg, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text, color = fg, fontSize = 13.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                        tag?.let { Text(it.label, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 8.dp)) }
                    }
                }
            }
        }
    }
}
