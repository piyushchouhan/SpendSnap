package com.spendsnap.detection

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.spendsnap.container
import com.spendsnap.domain.ExtractionMethod
import com.spendsnap.domain.PaymentApp
import com.spendsnap.domain.ReviewIssue
import com.spendsnap.domain.ScreenStatus
import com.spendsnap.domain.Transaction
import com.spendsnap.domain.VerificationStatus
import com.spendsnap.parsing.AmountParser
import com.spendsnap.parsing.AmountResult
import com.spendsnap.parsing.BubbleMatcher
import com.spendsnap.parsing.ChatBubble
import com.spendsnap.parsing.ChatBubbleParser
import com.spendsnap.parsing.PaymentDraft
import com.spendsnap.parsing.PaymentFormParser
import com.spendsnap.parsing.RecordedPayment
import com.spendsnap.parsing.ScreenNode
import com.spendsnap.parsing.SensitiveContent
import com.spendsnap.parsing.StatusClassifier
import com.spendsnap.parsing.toVerificationStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Payment flow inside Google Pay / PhonePe (restricted by packageNames in the service config):
 *
 * 1. Amount-entry screen: the amount and note the user typed (editable fields) are the source of truth.
 * 2. Tap on Pay: an entry is created as PENDING. Nothing is created by merely viewing screens.
 * 3. A later success/failure screen, or a new chat bubble with the same amount and note timed after
 *    the tap, updates that entry. Without such a signal it stays pending in Review.
 */
class PaymentAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var inspectJob: Job? = null
    private var draft: Pair<PaymentApp, PaymentDraft>? = null
    /** Pay taps still waiting for a success/failure signal. */
    private val submitted = mutableMapOf<PaymentApp, MutableList<RecordedPayment>>()
    /** Payments recorded by this service, so their chat bubble does not create a second entry. */
    private val recorded = mutableMapOf<PaymentApp, MutableList<RecordedPayment>>()
    private val claimedBubbles = mutableSetOf<String>()

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val app = PaymentApp.fromPackage(event.packageName?.toString())?.takeIf { container.settings.detects(it) } ?: return
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val label = (event.text.joinToString(" ").ifBlank { null } ?: event.contentDescription?.toString()).orEmpty()
            DiagnosticLog.line(this, "click: \"$label\"")
            if (PaymentFormParser.isPayButton(label)) {
                // Read the form now: right after the tap PhonePe opens a fingerprint prompt, which
                // becomes the active window and would leave only a stale, half-typed draft.
                val form = readScreen(app)?.let(PaymentFormParser::parse)
                scope.launch { onPayTapped(app, form) }
            }
            return
        }
        // Throttle, don't debounce: animated screens (e.g. PhonePe's PAY button) never stop sending events.
        if (inspectJob?.isActive == true) return
        inspectJob = scope.launch {
            delay(INSPECT_INTERVAL_MILLIS)
            inspectScreen(app)
        }
    }

    private fun readScreen(app: PaymentApp): List<ScreenNode>? {
        val root = rootInActiveWindow ?: return null
        if (root.packageName?.toString() != app.packageName) return null
        val nodes = collectNodes(root)
        if (nodes.isEmpty()) {
            DiagnosticLog.line(this, "no readable text on this ${app.name} screen")
            return null
        }
        if (SensitiveContent.isSensitive(nodes.map { it.text })) {
            DiagnosticLog.line(this, "sensitive screen skipped (${app.name}), content not read further")
            return null
        }
        return nodes
    }

    private suspend fun inspectScreen(app: PaymentApp) {
        val nodes = readScreen(app) ?: return
        val lines = nodes.map { it.text }
        val status = StatusClassifier.classify(lines)
        DiagnosticLog.dumpNodes(this, "a11y/${app.name}/$status", nodes)

        PaymentFormParser.parse(nodes)?.let { draft = app to it }
        PaymentFormParser.parsePaySheet(nodes)?.let { sheet ->
            // The sheet stays up while the PIN is typed and fires many events: record it once.
            val now = System.currentTimeMillis()
            val seen = recorded[app].orEmpty().any { it.draft.amountPaise == sheet.amountPaise && now - it.at < PAY_SHEET_DEDUPE_MILLIS }
            if (!seen) submit(app, sheet, "pay sheet")
        }
        verifySubmitted(app, lines, status)
    }

    private suspend fun onPayTapped(app: PaymentApp, formAtTap: PaymentDraft?) {
        val form = formAtTap ?: draft?.takeIf { it.first == app }?.second ?: return
        draft = null
        submit(app, form, "pay tapped")
    }

    /** Records a payment the user just started as PENDING; a later success/failure signal resolves it. */
    private suspend fun submit(app: PaymentApp, form: PaymentDraft, source: String) {
        val now = System.currentTimeMillis()
        DiagnosticLog.line(this, "$source: ${app.name} amount=${form.amountPaise} note=${form.note} payee=${form.payee}")

        val transaction = Transaction(
            description = form.note ?: form.payee,
            amountPaise = form.amountPaise,
            paymentApp = app,
            timestamp = now,
            verificationStatus = VerificationStatus.PENDING,
            extractionMethod = ExtractionMethod.ACCESSIBILITY,
            reviewIssues = buildSet {
                add(ReviewIssue.STATUS_UNVERIFIED)
                if (form.note == null && form.payee == null) add(ReviewIssue.DESCRIPTION_MISSING)
                if (container.settings.reviewBeforeSaving) add(ReviewIssue.AWAITING_REVIEW)
            },
        )
        try {
            // Each Pay tap is a separate payment: never matched against earlier entries.
            val id = container.repository.save(transaction)
            CaptureNotifier.show(this, transaction.copy(id = id))
            val time = LocalTime.now()
            RecordedPayment(id, form, time.hour * 60 + time.minute, now).let {
                submitted.getOrPut(app) { mutableListOf() } += it
                recorded.getOrPut(app) { mutableListOf() } += it
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not store payment", e)
        }
    }

    private suspend fun verifySubmitted(app: PaymentApp, lines: List<String>, status: ScreenStatus) {
        val now = System.currentTimeMillis()
        (submitted.values + recorded.values).forEach { list -> list.removeAll { now - it.at > VERIFY_WINDOW_MILLIS } }
        val bubbles = BubbleMatcher.keyed(ChatBubbleParser.parse(lines))
        val screenAmount = (AmountParser.parse(lines) as? AmountResult.Found)?.paise
        val pending = submitted[app].orEmpty()

        for (s in pending.toList()) {
            val bubble = bubbles.lastOrNull { (key, b) -> key !in claimedBubbles && ChatBubbleParser.confirms(b, s.draft, s.minuteOfDay) }
            bubble?.let { (key, _) ->
                claimedBubbles += key
                s.bubbleKey = key
            }
            val outcome = bubble?.second?.status
                // A dedicated result screen ("Payment successful ₹1") also counts when the amount matches.
                ?: status.takeIf { (it == ScreenStatus.SUCCESS || it == ScreenStatus.FAILED) && screenAmount == s.draft.amountPaise }
                ?: continue
            DiagnosticLog.line(this, "verified #${s.id}: $outcome")
            // A Google Pay bubble carries the note that its unreadable amount screen hid.
            container.repository.applyOutcome(s.id, outcome.toVerificationStatus(), note = bubble?.second?.note)
            container.repository.get(s.id)?.let { CaptureNotifier.show(this, it) }
            if (outcome != ScreenStatus.PENDING) submitted[app]?.remove(s)
        }
        recordFreshBubbles(app, bubbles, now)
    }

    /**
     * Fallback when the amount-entry screen could not be read (Google Pay's is a secure screen):
     * a "Paid" bubble timed within the last few minutes is a payment that just happened.
     * Older bubbles are history and are ignored.
     */
    private suspend fun recordFreshBubbles(app: PaymentApp, bubbles: List<Pair<String, ChatBubble>>, now: Long) {
        val time = LocalTime.now()
        val nowMinute = time.hour * 60 + time.minute
        val known = recorded.getOrPut(app) { mutableListOf() }
        for (b in BubbleMatcher.unrecorded(bubbles, known, claimedBubbles, nowMinute, FRESH_BUBBLE_MINUTES)) {
            DiagnosticLog.line(this, "new bubble: ${app.name} amount=${b.amountPaise} note=${b.note} payee=${b.payee}")
            val transaction = Transaction(
                    description = b.note ?: b.payee,
                    amountPaise = b.amountPaise,
                    paymentApp = app,
                    // The bubble's own time, not when the chat was reopened.
                    timestamp = LocalDate.now().atTime(b.minuteOfDay / 60, b.minuteOfDay % 60)
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    verificationStatus = VerificationStatus.SUCCESS_REPORTED,
                    extractionMethod = ExtractionMethod.ACCESSIBILITY,
                    reviewIssues = buildSet {
                        if (b.note == null && b.payee == null) add(ReviewIssue.DESCRIPTION_MISSING)
                        if (container.settings.reviewBeforeSaving) add(ReviewIssue.AWAITING_REVIEW)
                    },
            )
            val id = container.repository.save(transaction)
            CaptureNotifier.show(this, transaction.copy(id = id))
            // Already linked to its own bubble (claimed above), so it must not absorb another one.
            known += RecordedPayment(id, PaymentDraft(b.amountPaise, b.note, b.payee), b.minuteOfDay, now).also { it.bubbleKey = "self" }
        }
    }

    private fun collectNodes(root: AccessibilityNodeInfo): List<ScreenNode> {
        val out = ArrayList<ScreenNode>()
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > MAX_DEPTH || out.size >= MAX_NODES || !node.isVisibleToUser) return
            if (node.isPassword) return
            if (!node.isShowingHintText) {
                val text = node.text?.toString() ?: node.contentDescription?.toString()
                if (!text.isNullOrBlank()) out += ScreenNode(text, node.isEditable, node.isClickable, node.viewIdResourceName)
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        return out
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "SpendSnap"
        const val INSPECT_INTERVAL_MILLIS = 700L
        const val VERIFY_WINDOW_MILLIS = 15 * 60_000L
        const val FRESH_BUBBLE_MINUTES = 3
        const val PAY_SHEET_DEDUPE_MILLIS = 90_000L
        const val MAX_DEPTH = 40
        const val MAX_NODES = 300
    }
}
