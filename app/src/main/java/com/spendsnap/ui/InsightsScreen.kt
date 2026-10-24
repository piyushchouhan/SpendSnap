package com.spendsnap.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeliveryDining
import androidx.compose.material.icons.rounded.EventRepeat
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PendingActions
import androidx.compose.material.icons.rounded.PhonelinkLock
import androidx.compose.material.icons.rounded.QuestionAnswer
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.ShoppingBasket
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Summarize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spendsnap.AppContainer
import com.spendsnap.ai.AiEngineKind
import com.spendsnap.ai.AskResult
import com.spendsnap.ai.CategoryShare
import com.spendsnap.ai.Insights
import com.spendsnap.ai.SavingKind
import com.spendsnap.ai.SavingOpportunity
import com.spendsnap.ai.SavingsAnalyzer
import com.spendsnap.ai.SavingsPlan
import com.spendsnap.ai.SavingsReport
import com.spendsnap.ai.SpendCategory
import com.spendsnap.ai.Summary
import com.spendsnap.container
import com.spendsnap.domain.Inr
import com.spendsnap.domain.Transaction
import com.spendsnap.domain.isUnverified
import com.spendsnap.ui.theme.LocalExtraColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val QUICK_QUESTIONS = listOf("How much on food this month?", "Biggest expense this week?", "Google Pay vs PhonePe", "Transport this month")
private val MONTH = DateTimeFormatter.ofPattern("MMMM")
private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMM")

class InsightsViewModel(private val container: AppContainer) : ViewModel() {
    val all = container.repository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    var summary by mutableStateOf<Summary?>(null)
        private set
    var summaryLoading by mutableStateOf(false)
        private set
    var asked by mutableStateOf<String?>(null)
        private set
    var answer by mutableStateOf<AskResult?>(null)
        private set
    var categorizeNote by mutableStateOf<String?>(null)
        private set
    var dismissed by mutableStateOf(container.settings.dismissedTips)
        private set
    var plan by mutableStateOf<SavingsPlan?>(null)
        private set
    var planLoading by mutableStateOf(false)
        private set

    private suspend fun payments(): List<Transaction> = all.filterNotNull().first()

    fun loadSummary(force: Boolean) {
        if (summaryLoading) return
        viewModelScope.launch {
            summaryLoading = true
            summary = container.ai.summary(payments(), force)
            summaryLoading = false
        }
    }

    fun loadPlan(report: SavingsReport, force: Boolean) {
        if (planLoading) return
        viewModelScope.launch {
            planLoading = true
            plan = container.ai.savingsPlan(report, force)
            planLoading = false
        }
    }

    fun categorize() {
        if (!container.settings.autoCategorize) return
        viewModelScope.launch {
            categorizeNote = try {
                container.ai.categorizeMissing(payments())
                null
            } catch (e: Exception) {
                "Couldn’t auto-categorize some payments: ${e.message}"
            }
        }
    }

    fun ask(question: String) {
        val q = question.trim()
        if (q.isEmpty()) return
        asked = q
        answer = null
        viewModelScope.launch {
            val result = container.ai.ask(q, payments())
            if (asked == q) answer = result
        }
    }

    fun clearAnswer() {
        asked = null
        answer = null
    }

    fun dismiss(id: String) {
        dismissed = dismissed + id
        container.settings.dismissedTips = dismissed
    }
}

private data class Tip(val id: String, val icon: ImageVector, val title: String, val body: String, val action: String?, val onAction: () -> Unit)

/** Design "SpendSnap AI": weekly summary, questions, tips, category breakdown and month-end estimate. */
@Composable
fun InsightsScreen(onReview: () -> Unit, onHistory: () -> Unit, modifier: Modifier = Modifier) {
    val container = LocalContext.current.container
    val vm = viewModel { InsightsViewModel(container) }
    val all = vm.all.collectAsStateWithLifecycle().value
    val aiOn by container.settings.aiEnabled.collectAsStateWithLifecycle()
    val engine by container.settings.aiEngine.collectAsStateWithLifecycle()
    val onDevice = engine == AiEngineKind.ON_DEVICE
    var showConsent by remember { mutableStateOf(false) }

    LaunchedEffect(aiOn, engine) {
        if (aiOn) {
            vm.loadSummary(force = false)
            vm.categorize()
        }
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Insights", style = MaterialTheme.typography.titleLarge)
            Row(
                Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val (chipIcon, chipText) = when {
                    !aiOn -> Icons.Rounded.CloudOff to "AI off"
                    onDevice -> Icons.Rounded.PhonelinkLock to "On-device AI"
                    else -> Icons.Rounded.Cloud to "Gemini API"
                }
                Icon(chipIcon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(chipText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (!aiOn) {
            AiOffCard(configured = container.ai.isConfigured, onDevice = onDevice, onTurnOn = { showConsent = true })
        } else {
            SummaryCard(vm.summary, vm.summaryLoading, onDevice, onRefresh = { vm.loadSummary(force = true) })
            AskSection(vm.asked, vm.answer, onAsk = vm::ask, onClear = vm::clearAnswer, onHistory = onHistory, total = all?.size ?: 0)
        }

        if (all != null) {
            val report = remember(all) { SavingsAnalyzer.analyze(all, Instant.now(), ZoneId.systemDefault()) }
            LaunchedEffect(aiOn, engine, report) { if (aiOn && report.opportunities.isNotEmpty()) vm.loadPlan(report, force = false) }
            SavingsSection(report, aiOn, vm.plan, vm.planLoading, onRefresh = { vm.loadPlan(report, force = true) })
        }

        if (all != null) {
            val zone = ZoneId.systemDefault()
            val now = Instant.now()
            val month = remember(all) { Insights.month(all, now, zone) }
            val breakdown = remember(month) { Insights.breakdown(month) }
            val monthName = MONTH.format(now.atZone(zone))
            val tips = buildList {
                val unverified = all.count { it.isUnverified }
                if (unverified > 0) {
                    add(Tip("unverified", Icons.Rounded.PendingActions, "$unverified payment${if (unverified == 1) " needs" else "s need"} review",
                        "They aren’t counted in totals or insights until you check the amount and payee.", "Review", onReview))
                }
                breakdown.firstOrNull { it.count >= 2 && it.category != SpendCategory.OTHER && it.category != SpendCategory.RENT }?.let { top ->
                    add(Tip("top-${top.label}-$monthName", iconFor(top.category), "${top.label}: ${Inr.format(top.paise)} this month",
                        "${top.count} payments since 1 $monthName. Biggest: ${top.top.description ?: "one payment"}.",
                        if (aiOn) "Break it down" else null) { vm.ask("How much on ${top.label} this month?") })
                }
            }.filter { it.id !in vm.dismissed }

            if (tips.isNotEmpty()) {
                SectionTitle("Worth a look")
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    tips.forEach { TipCard(it, onDismiss = { vm.dismiss(it.id) }) }
                }
            }
            if (breakdown.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Where it went · $monthName", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Text(if (aiOn && container.settings.autoCategorize) "Auto-categorized" else "By rules on this phone", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                vm.categorizeNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) }
                Breakdown(breakdown)
                ForecastCard(all, now, zone)
            }
        }

        HowItWorks(onDevice)
    }

    if (showConsent) {
        AiConsentDialog(
            onDevice = onDevice,
            onDismiss = { showConsent = false },
            onConfirm = {
                showConsent = false
                container.settings.setAiEnabled(true)
            },
        )
    }
}

@Composable
private fun AiOffCard(configured: Boolean, onDevice: Boolean, onTurnOn: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(96.dp).background(scheme.surfaceContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = scheme.tertiary, modifier = Modifier.size(48.dp))
        }
        Text("AI Insights is off", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 4.dp))
        Text(
            if (onDevice) {
                "Turn it on for weekly summaries, automatic categories and answers about your spending. Everything runs with Gemini Nano on this phone."
            } else if (configured) {
                "Turn it on for weekly summaries, automatic categories and answers about your spending, powered by Google’s Gemini API."
            } else {
                "This build has no Gemini API key. Add it to api.txt in the project and rebuild."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (configured) Button(onClick = onTurnOn, modifier = Modifier.padding(top = 8.dp)) { Text("Turn on") }
    }
}

@Composable
private fun SummaryCard(summary: Summary?, loading: Boolean, onDevice: Boolean, onRefresh: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().background(scheme.tertiaryContainer, RoundedCornerShape(28.dp)).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = scheme.onTertiaryContainer, modifier = Modifier.size(22.dp))
            Text("This week so far", style = MaterialTheme.typography.titleSmall, color = scheme.onTertiaryContainer, modifier = Modifier.weight(1f))
            IconButton(onClick = onRefresh, enabled = !loading, colors = IconButtonDefaults.iconButtonColors(contentColor = scheme.onTertiaryContainer)) {
                Icon(Icons.Rounded.Refresh, "Regenerate summary")
            }
        }
        if (loading || summary == null) {
            val alpha by rememberInfiniteTransition(label = "shimmer").animateFloat(0.15f, 0.35f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
            Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(1f, 0.88f, 0.62f).forEach { w ->
                    Box(Modifier.fillMaxWidth(w).height(16.dp).alpha(alpha).background(scheme.onTertiaryContainer, RoundedCornerShape(8.dp)))
                }
            }
        } else {
            Text(summary.text, fontSize = 18.sp, lineHeight = 26.sp, color = scheme.onTertiaryContainer, modifier = Modifier.padding(top = 12.dp))
            summary.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onTertiaryContainer, modifier = Modifier.padding(top = 8.dp)) }
        }
        Text(
            if (summary?.fromAi == true && !onDevice) {
                "Numbers are calculated on this phone from your saved payments. Gemini only writes the wording."
            } else {
                "Written on this phone from your saved payments. Figures match History."
            },
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onTertiaryContainer,
            modifier = Modifier.padding(top = 14.dp),
        )
    }
}

@Composable
private fun AskSection(asked: String?, answer: AskResult?, onAsk: (String) -> Unit, onClear: () -> Unit, onHistory: () -> Unit, total: Int) {
    val scheme = MaterialTheme.colorScheme
    var input by remember { mutableStateOf("") }
    val send = {
        onAsk(input)
        input = ""
    }
    SectionTitle("Ask about your spending")
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(56.dp)
            .background(scheme.surfaceContainerHigh, RoundedCornerShape(28.dp)).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.AutoAwesome, null, tint = scheme.tertiary)
        Box(Modifier.weight(1f)) {
            if (input.isEmpty()) Text("e.g. How much on food this month?", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        IconButton(
            onClick = send,
            enabled = input.isNotBlank(),
            colors = IconButtonDefaults.iconButtonColors(containerColor = scheme.primary, contentColor = scheme.onPrimary, disabledContainerColor = Color.Transparent),
        ) { Icon(Icons.Rounded.ArrowUpward, "Ask") }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QUICK_QUESTIONS.forEach { q -> FilterChip(selected = false, onClick = { onAsk(q) }, label = { Text(q) }) }
    }

    if (asked != null) {
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth()
                .background(scheme.surfaceContainerLow, RoundedCornerShape(20.dp))
                .border(1.dp, scheme.outlineVariant, RoundedCornerShape(20.dp)).padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Text("“$asked”", style = MaterialTheme.typography.titleSmall, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                IconButton(onClick = onClear, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.Close, "Clear answer", Modifier.size(20.dp)) }
            }
            if (answer == null) {
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = scheme.tertiary)
                    Text("Looking through $total payments…", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                }
            } else {
                val a = answer.answer
                if (a.figure.isNotEmpty()) Text(a.figure, fontSize = 32.sp, lineHeight = 40.sp, modifier = Modifier.padding(top = 10.dp))
                Text(a.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                if (a.rows.isNotEmpty()) {
                    Column(Modifier.padding(top = 10.dp)) {
                        a.rows.forEach { t ->
                            Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.outlineVariant))
                            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(t.description ?: "No description", style = MaterialTheme.typography.bodyMedium)
                                    Text("${dayAndTime(t.timestamp)} · ${t.paymentApp.label}", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                                }
                                Text(formatAmount(t.amountPaise), style = MaterialTheme.typography.titleSmall)
                            }
                        }
                    }
                }
                answer.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.error, modifier = Modifier.padding(top = 8.dp)) }
                if (a.basis.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Storage, null, Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
                        Text(a.basis, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp).weight(1f))
                        TextButton(onClick = onHistory) { Text("Open History") }
                    }
                }
            }
        }
    }
}

/** "Where you can save": on-phone findings, plus an AI-worded plan when AI Insights is on. */
@Composable
private fun SavingsSection(report: SavingsReport, aiOn: Boolean, plan: SavingsPlan?, planLoading: Boolean, onRefresh: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    SectionTitle("Where you can save")
    if (!report.enoughData) {
        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp).fillMaxWidth()
                .background(LocalExtraColors.current.warningContainer, RoundedCornerShape(16.dp)).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Rounded.HourglassTop, null, tint = LocalExtraColors.current.onWarningContainer)
            Text(
                "Keep tracking: ${SavingsReport.MIN_DAYS - report.daysTracked} more day${if (SavingsReport.MIN_DAYS - report.daysTracked == 1) "" else "s"} " +
                    "of payments will make this analysis reliable.",
                style = MaterialTheme.typography.bodyMedium,
                color = LocalExtraColors.current.onWarningContainer,
            )
        }
    }
    if (report.opportunities.isEmpty()) {
        Text(
            if (report.enoughData) "No obvious unnecessary spending in the last 30 days." else "Nothing stands out yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        return
    }

    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().background(scheme.primaryContainer, RoundedCornerShape(28.dp)).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Savings, null, tint = scheme.onPrimaryContainer)
            Text("You could save up to", style = MaterialTheme.typography.titleSmall, color = scheme.onPrimaryContainer, modifier = Modifier.padding(start = 8.dp).weight(1f))
            if (aiOn) {
                IconButton(onClick = onRefresh, enabled = !planLoading, colors = IconButtonDefaults.iconButtonColors(contentColor = scheme.onPrimaryContainer)) {
                    Icon(Icons.Rounded.Refresh, "Rewrite plan")
                }
            }
        }
        Text("${Inr.format(report.totalSavingPaise)} a month", fontSize = 32.sp, lineHeight = 40.sp, color = scheme.onPrimaryContainer, modifier = Modifier.padding(top = 4.dp))
        Text(
            "If you make the changes below. Based on your last 30–90 days.",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onPrimaryContainer,
        )
        val lines = if (aiOn) plan?.lines else SavingsAnalyzer.localPlan(report)
        if (aiOn && (planLoading || plan == null)) {
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = scheme.onPrimaryContainer)
                Text("Writing your plan…", style = MaterialTheme.typography.bodyMedium, color = scheme.onPrimaryContainer)
            }
        } else if (!lines.isNullOrEmpty()) {
            Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                lines.forEach { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(18.dp).padding(top = 2.dp))
                        Text(line, style = MaterialTheme.typography.bodyMedium, color = scheme.onPrimaryContainer)
                    }
                }
            }
            plan?.note?.takeIf { aiOn }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onPrimaryContainer, modifier = Modifier.padding(top = 8.dp)) }
        }
    }

    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).fillMaxWidth().border(1.dp, scheme.outlineVariant, RoundedCornerShape(16.dp))) {
        report.opportunities.forEachIndexed { i, o ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.outlineVariant))
            OpportunityRow(o)
        }
    }
}

@Composable
private fun OpportunityRow(o: SavingOpportunity) {
    val scheme = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(
                when (o.kind) {
                    SavingKind.SUBSCRIPTION -> Icons.Rounded.EventRepeat
                    SavingKind.FOOD_DELIVERY -> Icons.Rounded.DeliveryDining
                    SavingKind.FREQUENT_SMALL -> Icons.Rounded.LocalCafe
                    SavingKind.LATE_NIGHT -> Icons.Rounded.Bedtime
                    SavingKind.CATEGORY_UP -> Icons.Rounded.TrendingUp
                },
                null,
                tint = scheme.onSurfaceVariant,
            )
            Column(Modifier.weight(1f)) {
                Text(o.title, style = MaterialTheme.typography.titleSmall)
                Text(o.detail, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                Text("→ ${o.change}", style = MaterialTheme.typography.bodySmall, color = scheme.primary, modifier = Modifier.padding(top = 4.dp))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Inr.format(o.savingPaise), style = MaterialTheme.typography.titleSmall, color = scheme.primary)
                Text("/month", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
        }
        if (open) {
            Column(Modifier.padding(start = 40.dp, top = 8.dp)) {
                o.evidence.forEach { t ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text("${t.description ?: "No description"} · ${dayAndTime(t.timestamp)}", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Text(formatAmount(t.amountPaise), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun TipCard(tip: Tip, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val warn = tip.id == "unverified"
    Row(
        Modifier.fillMaxWidth().background(scheme.surfaceContainerLow, RoundedCornerShape(16.dp)).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier.size(40.dp).background(if (warn) LocalExtraColors.current.warningContainer else scheme.secondaryContainer, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(tip.icon, null, tint = if (warn) LocalExtraColors.current.onWarningContainer else scheme.onSecondaryContainer) }
        Column(Modifier.weight(1f)) {
            Text(tip.title, style = MaterialTheme.typography.titleMedium)
            Text(tip.body, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            Row(Modifier.padding(top = 4.dp)) {
                TextButton(onClick = onDismiss) { Text("Dismiss", color = scheme.onSurfaceVariant) }
                if (tip.action != null) TextButton(onClick = tip.onAction) { Text(tip.action) }
            }
        }
    }
}

@Composable
private fun Breakdown(shares: List<CategoryShare>) {
    val scheme = MaterialTheme.colorScheme
    val total = shares.sumOf { it.paise }.coerceAtLeast(1)
    val max = shares.first().paise.coerceAtLeast(1)
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().border(1.dp, scheme.outlineVariant, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 4.dp)) {
        shares.forEachIndexed { i, c ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.outlineVariant))
            Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(iconFor(c.category), null, tint = scheme.onSurfaceVariant)
                Column(Modifier.weight(1f)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(c.label, style = MaterialTheme.typography.bodyMedium)
                        Text(Inr.format(c.paise), style = MaterialTheme.typography.titleSmall)
                    }
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f).height(6.dp).background(scheme.surfaceContainerHigh, RoundedCornerShape(3.dp))) {
                            Box(Modifier.fillMaxWidth(maxOf(0.02f, c.paise.toFloat() / max)).height(6.dp).background(scheme.primary, RoundedCornerShape(3.dp)))
                        }
                        Text("${c.paise * 100 / total}%", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.width(34.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ForecastCard(all: List<Transaction>, now: Instant, zone: ZoneId) {
    val scheme = MaterialTheme.colorScheme
    val f = remember(all) { Insights.forecast(all, now, zone) }
    SectionTitle("Month-end estimate")
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().background(scheme.surfaceContainerLow, RoundedCornerShape(16.dp)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(Inr.format(f.projectedPaise), fontSize = 28.sp, lineHeight = 36.sp)
            Text("by ${DAY_MONTH.format(f.monthEnd)}", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
        }
        Box(Modifier.padding(top = 12.dp).fillMaxWidth().height(8.dp).background(scheme.surfaceContainerHigh, RoundedCornerShape(4.dp))) {
            val share = if (f.projectedPaise > 0) f.spentPaise.toFloat() / f.projectedPaise else 0f
            Box(Modifier.fillMaxWidth(share.coerceIn(0f, 1f)).height(8.dp).background(scheme.primary, RoundedCornerShape(4.dp)))
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${Inr.format(f.spentPaise)} spent · day ${f.day} of ${f.daysInMonth}", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            Text("${Inr.format(maxOf(0, f.projectedPaise - f.spentPaise))} more", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
        Text("At your current daily pace. Rent is counted once, not projected.", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun HowItWorks(onDevice: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).fillMaxWidth().border(1.dp, scheme.outlineVariant, RoundedCornerShape(16.dp)).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.Info, null, tint = scheme.tertiary)
        Column {
            Text("How AI Insights works", style = MaterialTheme.typography.titleSmall)
            Text(
                if (onDevice) {
                    "Runs with Gemini Nano through Android AICore on this Pixel. Your payments never leave the phone. " +
                        "Amounts are calculated from your saved entries; the AI only writes the wording and suggests categories, " +
                        "so it can occasionally get a category wrong."
                } else {
                    "Uses Google’s Gemini API over the internet. Amounts, totals and the estimate are calculated on this phone; " +
                        "Gemini only reads your question, writes the summary wording and suggests categories for unfamiliar payees, " +
                        "so it can occasionally get a category wrong. Screenshots, UPI references and account numbers are never sent."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 12.dp),
    )
}

private fun iconFor(category: SpendCategory?): ImageVector = when (category) {
    SpendCategory.GROCERIES -> Icons.Rounded.ShoppingBasket
    SpendCategory.FOOD -> Icons.Rounded.Restaurant
    SpendCategory.TRANSPORT -> Icons.Rounded.DirectionsCar
    SpendCategory.BILLS -> Icons.Rounded.Bolt
    SpendCategory.RENT -> Icons.Rounded.Home
    SpendCategory.ENTERTAINMENT -> Icons.Rounded.Movie
    SpendCategory.HEALTH -> Icons.Rounded.Favorite
    SpendCategory.SHOPPING -> Icons.Rounded.ShoppingBag
    SpendCategory.OTHER, null -> Icons.Rounded.Category
}

/** Explains exactly what goes to Google before AI Insights is switched on. */
@Composable
fun AiConsentDialog(onDevice: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.AutoAwesome, null, tint = scheme.tertiary) },
        title = { Text("Turn on AI Insights?", textAlign = TextAlign.Center) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (onDevice) {
                        "SpendSnap will use Gemini Nano, the model Android keeps on this Pixel for AI features."
                    } else {
                        "SpendSnap will use Google’s Gemini API over the internet. It sends only:"
                    },
                )
                (
                    if (onDevice) {
                        listOf(
                            Icons.Rounded.PhonelinkLock to "Runs entirely on this phone through Android AICore",
                            Icons.Rounded.CloudOff to "Nothing is sent to any server",
                            Icons.Rounded.Category to "Writes summaries, answers questions and suggests categories",
                        )
                    } else {
                        listOf(
                            Icons.Rounded.QuestionAnswer to "The questions you ask",
                            Icons.Rounded.Summarize to "This week’s totals per category, for the summary",
                            Icons.Rounded.Category to "Payee names the app can’t categorise itself (no amounts)",
                            Icons.Rounded.CloudOff to "Never: screenshots, UPI references, account numbers or screen text",
                        )
                    }
                ).forEach { (icon, text) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(icon, null, tint = scheme.primary, modifier = Modifier.size(20.dp))
                        Text(text, color = scheme.onSurface)
                    }
                }
                Text("You can turn it off any time in Settings.", style = MaterialTheme.typography.bodySmall)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Turn on") } },
    )
}
