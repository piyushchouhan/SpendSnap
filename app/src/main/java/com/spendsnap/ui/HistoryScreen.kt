package com.spendsnap.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spendsnap.container
import com.spendsnap.domain.History
import com.spendsnap.domain.HistoryFilter
import com.spendsnap.domain.HistorySort
import com.spendsnap.domain.Inr
import com.spendsnap.domain.Totals
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(onOpen: (Long) -> Unit, unverifiedOnly: Boolean, modifier: Modifier = Modifier) {
    val repository = LocalContext.current.container.repository
    val flow = remember { repository.observeAll() }
    val all = flow.collectAsStateWithLifecycle(null).value ?: return
    var filter by rememberSaveable(unverifiedOnly, stateSaver = HistoryFilterSaver) { mutableStateOf(HistoryFilter(unverified = unverifiedOnly)) }

    val zone = ZoneId.systemDefault()
    val list = remember(all, filter) { History.filter(all, filter) }
    val groups = remember(list, filter.sort) { History.group(list, filter.sort, LocalDate.now(zone), zone) }
    val scheme = MaterialTheme.colorScheme

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        stickyHeader {
            Column(Modifier.fillMaxWidth().background(scheme.background).padding(bottom = 8.dp)) {
                Row(
                    Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("History", style = MaterialTheme.typography.titleLarge)
                    Text("${all.size} total", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                }
                SearchBar(filter.query, onChange = { filter = filter.copy(query = it) })
                FilterRow(filter, onChange = { filter = it })
            }
        }
        if (filter.isFiltering && list.isNotEmpty()) {
            item {
                val total = Totals.sum(list, Long.MIN_VALUE, Long.MAX_VALUE).paise
                Text(
                    "${list.size} result${if (list.size == 1) "" else "s"} · ${Inr.format(total)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                )
            }
        }
        groups.forEach { group ->
            item(key = "h-${group.label}") {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(group.label, style = MaterialTheme.typography.titleSmall, color = scheme.onSurfaceVariant)
                    Text(Inr.format(group.totalPaise), style = MaterialTheme.typography.titleSmall, color = scheme.onSurfaceVariant)
                }
            }
            val byDay = filter.sort == HistorySort.NEWEST || filter.sort == HistorySort.OLDEST
            items(group.items, key = { it.id }) { t -> TransactionRow(t, showDay = !byDay) { onOpen(t.id) } }
        }
        if (list.isEmpty()) {
            item { EmptyHistory(hasAny = all.isNotEmpty(), canClear = filter.isFiltering, onClear = { filter = HistoryFilter(sort = filter.sort) }) }
        }
    }
}

@Composable
private fun SearchBar(query: String, onChange: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(56.dp)
            .background(scheme.surfaceContainerHigh, RoundedCornerShape(28.dp))
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.Search, null, tint = scheme.onSurfaceVariant)
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text("Search payee or amount", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            IconButton(onClick = { onChange("") }) { Icon(Icons.Rounded.Close, "Clear search", tint = scheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun FilterRow(filter: HistoryFilter, onChange: (HistoryFilter) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var sortOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            FilterChip(
                selected = false,
                onClick = { sortOpen = true },
                label = { Text(filter.sort.short) },
                leadingIcon = { Icon(Icons.Rounded.SwapVert, null, Modifier.size(18.dp)) },
                trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, null, Modifier.size(18.dp)) },
            )
            DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                HistorySort.entries.forEach { sort ->
                    DropdownMenuItem(
                        text = { Text(sort.label) },
                        leadingIcon = {
                            Box(Modifier.width(24.dp)) {
                                if (sort == filter.sort) Icon(Icons.Rounded.Check, null, tint = scheme.primary)
                            }
                        },
                        onClick = {
                            sortOpen = false
                            onChange(filter.copy(sort = sort))
                        },
                    )
                }
            }
        }
        Box(Modifier.width(1.dp).height(24.dp).background(scheme.outlineVariant))
        Chip("Google Pay", filter.googlePay) { onChange(filter.copy(googlePay = !filter.googlePay)) }
        Chip("PhonePe", filter.phonePe) { onChange(filter.copy(phonePe = !filter.phonePe)) }
        Chip("Cash", filter.cash) { onChange(filter.copy(cash = !filter.cash)) }
        Chip("Verified", filter.verified) { onChange(filter.copy(verified = !filter.verified)) }
        Chip("Unverified", filter.unverified) { onChange(filter.copy(unverified = !filter.unverified)) }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Rounded.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }
        } else {
            null
        },
    )
}

@Composable
private fun EmptyHistory(hasAny: Boolean, canClear: Boolean, onClear: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(start = 40.dp, end = 40.dp, top = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(if (hasAny) Icons.Rounded.SearchOff else Icons.Rounded.ReceiptLong, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
        Text(
            if (hasAny) "No matching transactions" else "No transactions yet",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            if (hasAny) "Try a different payee or amount, or clear the filters." else "Payments you add or detect will be listed here.",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (canClear) OutlinedButton(onClick = onClear, modifier = Modifier.padding(top = 8.dp)) { Text("Clear search and filters") }
    }
}

private val HistoryFilterSaver = androidx.compose.runtime.saveable.Saver<HistoryFilter, List<Any>>(
    save = { listOf(it.query, it.googlePay, it.phonePe, it.cash, it.verified, it.unverified, it.sort.name) },
    restore = {
        HistoryFilter(
            query = it[0] as String,
            googlePay = it[1] as Boolean,
            phonePe = it[2] as Boolean,
            cash = it[3] as Boolean,
            verified = it[4] as Boolean,
            unverified = it[5] as Boolean,
            sort = HistorySort.valueOf(it[6] as String),
        )
    },
)
