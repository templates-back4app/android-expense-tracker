package com.back4app.expensetracker.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.back4app.expensetracker.AppContainer
import com.back4app.expensetracker.data.EntryEntity
import com.back4app.expensetracker.domain.Dates
import com.back4app.expensetracker.domain.Money
import com.back4app.expensetracker.ui.EntriesViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntriesScreen(
    container: AppContainer,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onCategories: () -> Unit,
    onSettings: () -> Unit,
) {
    val vm: EntriesViewModel = viewModel { EntriesViewModel(container) }
    val s by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Expenses") },
                actions = {
                    IconButton(onClick = onCategories) { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Categories") }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) { Icon(Icons.Filled.Add, contentDescription = "Add expense") }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            MonthSelector(month = s.month, onPrev = { vm.shiftMonth(-1) }, onNext = { vm.shiftMonth(1) })
            BudgetCard(totalCents = s.totalCents, budgetCents = s.budgetCents, pendingOps = s.pendingOps)
            if (s.categoryTotals.isNotEmpty()) {
                CategoryBarChart(s.categoryTotals, Modifier.padding(horizontal = 16.dp))
            }
            HorizontalDivider(Modifier.padding(top = 8.dp))
            if (s.entries.isEmpty()) {
                Text(
                    "No expenses in ${Dates.monthLabel(s.month)}. Tap + to add one — it works offline.",
                    modifier = Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(s.entries, key = { it.entryKey }) { entry -> EntryRow(entry, onClick = { onEdit(entry.entryKey) }) }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }
}

@Composable
private fun MonthSelector(month: String, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrev) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month") }
        Text(Dates.monthLabel(month), style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = onNext) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month") }
    }
}

@Composable
private fun BudgetCard(totalCents: Long, budgetCents: Long, pendingOps: Int) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(Money.format(totalCents), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (pendingOps > 0) {
                    Text("$pendingOps unsynced", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                }
            }
            if (budgetCents > 0) {
                val percent = Money.budgetPercent(totalCents, budgetCents)
                Text(
                    "of ${Money.format(budgetCents)} monthly budget · $percent%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (totalCents > budgetCents) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { Money.budgetFraction(totalCents, budgetCents) },
                    modifier = Modifier.fillMaxWidth(),
                    color = if (totalCents > budgetCents) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            } else {
                Text("No monthly budget yet — set one in Settings.", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun EntryRow(entry: EntryEntity, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(entry.category) },
        supportingContent = { Text(listOf(entry.date, entry.note).filter { it.isNotBlank() }.joinToString(" · ")) },
        trailingContent = {
            Text(
                (if (entry.dirty) "● " else "") + Money.format(entry.amountCents),
                style = MaterialTheme.typography.titleMedium,
            )
        },
    )
    HorizontalDivider()
}
