package com.spendsnap.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddAPhoto
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.PendingActions
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spendsnap.container
import com.spendsnap.data.TransactionRepository
import com.spendsnap.domain.DashboardTotals
import com.spendsnap.domain.Inr
import com.spendsnap.domain.PeriodTotal
import com.spendsnap.domain.Totals
import com.spendsnap.domain.Transaction
import com.spendsnap.ui.theme.LocalExtraColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class HomeState(
    val totals: DashboardTotals,
    val recent: List<Transaction>,
    val reviewCount: Int,
)

class HomeViewModel(repository: TransactionRepository) : ViewModel() {
    val state = combine(repository.observeAll(), repository.observeReviewQueue()) { all, review ->
        HomeState(
            totals = Totals.dashboard(all, Instant.now(), ZoneId.systemDefault()),
            recent = all.take(8),
            reviewCount = review.size,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

private val SINCE = DateTimeFormatter.ofPattern("d MMM")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpen: (Long) -> Unit,
    onReview: () -> Unit,
    onHistory: () -> Unit,
    onDetection: () -> Unit,
    onImportScreenshot: () -> Unit,
    onAddManual: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repository = context.container.repository
    val vm = viewModel { HomeViewModel(repository) }
    val state by vm.state.collectAsStateWithLifecycle()
    var detectionOn by remember { mutableStateOf(true) }
    var showSheet by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        detectionOn = isAccessibilityEnabled(context)
        onPauseOrDispose { }
    }
    val s = state ?: return

    Box(modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            item { HomeHeader(onSearch = onHistory) }
            item { TotalCard(s.totals) }
            item { PeriodCards(s.totals) }
            if (s.reviewCount > 0) item { ReviewBanner(s.reviewCount, onReview) }
            if (!detectionOn) item { DetectionOffCard(onDetection) }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 20.dp, bottom = 4.dp).height(40.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Recent", style = MaterialTheme.typography.titleMedium)
                    if (s.recent.isNotEmpty()) TextButton(onClick = onHistory) { Text("See all") }
                }
            }
            if (s.recent.isEmpty()) {
                item { EmptyState(onDetection) }
            } else {
                items(s.recent, key = { it.id }) { t -> TransactionRow(t) { onOpen(t.id) } }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { showSheet = true },
            icon = { Icon(Icons.Rounded.AddAPhoto, null) },
            text = { Text("Add payment") },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            AddPaymentSheet(
                onImport = { showSheet = false; onImportScreenshot() },
                onManual = { showSheet = false; onAddManual() },
            )
        }
    }
}

@Composable
private fun HomeHeader(onSearch: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.ReceiptLong, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp))
        }
        Text("SpendSnap", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 12.dp).weight(1f))
        IconButton(onClick = onSearch) { Icon(Icons.Rounded.Search, "Search", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun TotalCard(totals: DashboardTotals) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp).fillMaxWidth()
            .background(scheme.primaryContainer, RoundedCornerShape(28.dp))
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 18.dp),
    ) {
        Text("Total spent", style = MaterialTheme.typography.titleSmall, color = scheme.onPrimaryContainer)
        Text(
            Inr.format(totals.allTime.paise),
            fontSize = 45.sp,
            lineHeight = 52.sp,
            letterSpacing = (-0.5).sp,
            color = scheme.onPrimaryContainer,
            modifier = Modifier.padding(top = 4.dp),
        )
        val count = totals.allTime.count
        val since = totals.sinceMillis?.let { " since " + SINCE.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) }.orEmpty()
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.Receipt, null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(18.dp))
            Text(
                "$count transaction${if (count == 1) "" else "s"} recorded$since",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun PeriodCards(totals: DashboardTotals) {
    Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Today" to totals.today, "This week" to totals.week, "This month" to totals.month).forEach { (label, total) ->
            PeriodCard(label, total, Modifier.weight(1f))
        }
    }
}

@Composable
private fun PeriodCard(label: String, total: PeriodTotal, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier.background(scheme.surfaceContainerLow, RoundedCornerShape(16.dp))
            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 14.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        Text(
            Inr.format(total.paise),
            fontSize = 18.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            "${total.count} payment${if (total.count == 1) "" else "s"}",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun ActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    container: Color,
    content: Color,
    iconTint: Color,
    subtitleColor: Color,
    border: BorderStroke?,
    topPadding: Int,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, top = topPadding.dp).fillMaxWidth()
            .then(if (border != null) Modifier.border(border, shape) else Modifier)
            .background(container, shape)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, tint = iconTint)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = content)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = subtitleColor)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = subtitleColor)
    }
}

@Composable
private fun ReviewBanner(count: Int, onClick: () -> Unit) {
    val extra = LocalExtraColors.current
    ActionCard(
        icon = Icons.Rounded.PendingActions,
        title = "$count ${if (count == 1) "entry needs" else "entries need"} review",
        subtitle = "Not counted until checked. Confirm the amount and payee.",
        container = extra.warningContainer,
        content = extra.onWarningContainer,
        iconTint = extra.onWarningContainer,
        subtitleColor = extra.onWarningContainer,
        border = null,
        topPadding = 12,
        onClick = onClick,
    )
}

@Composable
private fun DetectionOffCard(onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    ActionCard(
        icon = Icons.Rounded.Radar,
        title = "Automatic detection is off",
        subtitle = "Set it up to record payments as you pay",
        container = scheme.surfaceContainerLowest,
        content = scheme.onSurface,
        iconTint = scheme.primary,
        subtitleColor = scheme.onSurfaceVariant,
        border = BorderStroke(1.dp, scheme.outlineVariant),
        topPadding = 8,
        onClick = onClick,
    )
}

@Composable
private fun EmptyState(onSetUpDetection: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(96.dp).background(scheme.surfaceContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.ReceiptLong, null, tint = scheme.primary, modifier = Modifier.size(48.dp))
        }
        Text("No expenses yet", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 4.dp))
        Text(
            "Add a Google Pay or PhonePe payment confirmation, or turn on automatic detection so payments are recorded as you pay.",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        OutlinedButton(onClick = onSetUpDetection, modifier = Modifier.padding(top = 8.dp)) { Text("Set up detection") }
    }
}

@Composable
private fun AddPaymentSheet(onImport: () -> Unit, onManual: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.padding(bottom = 24.dp)) {
        Text("Add a payment", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 4.dp))
        Text(
            "From a Google Pay or PhonePe confirmation",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
        )
        SheetOption(Icons.Rounded.PhotoLibrary, scheme.primaryContainer, scheme.onPrimaryContainer, "Import screenshot", "Pick a confirmation from Photos", onImport)
        SheetOption(Icons.Rounded.EditNote, scheme.surfaceContainerHighest, scheme.onSurfaceVariant, "Enter manually", "Type the description and amount", onManual)
        PrivacyNote("Images are read on this phone and not stored.", Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp))
    }
}

@Composable
private fun SheetOption(icon: ImageVector, tile: Color, onTile: Color, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(44.dp).background(tile, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = onTile)
        }
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
