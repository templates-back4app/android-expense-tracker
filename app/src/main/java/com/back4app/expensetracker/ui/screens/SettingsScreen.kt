package com.back4app.expensetracker.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.back4app.expensetracker.AppContainer
import com.back4app.expensetracker.domain.Money
import com.back4app.expensetracker.ui.SettingsViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit, onSignedOut: () -> Unit) {
    val vm: SettingsViewModel = viewModel { SettingsViewModel(container) }
    val s by vm.state.collectAsStateWithLifecycle()
    var budgetText by rememberSaveable { mutableStateOf("") }
    var budgetError by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(s.budgetCents) { if (budgetText.isEmpty() && s.budgetCents > 0) budgetText = Money.toInput(s.budgetCents) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            Text("Monthly budget", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = budgetText, onValueChange = { budgetText = it; budgetError = false }, label = { Text("USD, e.g. 1500.00") },
                    singleLine = true, isError = budgetError, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { budgetError = !vm.saveBudget(budgetText) }) { Text("Save") }
            }
            if (budgetError) Text("Enter an amount like 1500.00", color = MaterialTheme.colorScheme.error)
            Text("Stored on this device only (${Money.format(s.budgetCents)} now).", style = MaterialTheme.typography.bodySmall)

            Spacer(Modifier.height(24.dp))
            Text("Sync", style = MaterialTheme.typography.titleMedium)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    StatusLine("Status", when {
                        !s.backendConfigured -> "backend keys missing"
                        s.syncing -> "syncing…"
                        s.pendingOps > 0 -> "waiting for network / retry"
                        else -> "up to date"
                    })
                    StatusLine("Pending operations", s.pendingOps.toString())
                    StatusLine("Conflicts recorded", s.conflictCount.toString())
                    StatusLine("Last successful sync", s.lastSyncAt?.let(::formatTime) ?: "never")
                    s.lastError?.let { StatusLine("Last error", it) }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = vm::syncNow, enabled = s.backendConfigured) { Text("Sync now") }
                }
            }
            if (s.conflicts.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Recent conflicts (evidence log, newest first)", style = MaterialTheme.typography.labelLarge)
                s.conflicts.take(10).forEach { c ->
                    Text(
                        "${formatTime(c.recordedAt)} · ${c.reason} · entry ${c.entryKey.take(8)} · base v${c.baseVersion ?: "-"} vs server v${c.serverVersion ?: "-"}",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Text("Account", style = MaterialTheme.typography.titleMedium)
            Text(s.account, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Button(onClick = { vm.signOut(onSignedOut) }) {
                Text(if (s.pendingOps > 0) "Sign out (discards ${s.pendingOps} unsynced change(s))" else "Sign out")
            }
            if (s.isGuest) {
                Text(
                    "Guest accounts cannot sign back in: signing out deletes the local data for good.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text("$label: ", style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
private fun formatTime(epochMillis: Long): String = TIME_FORMAT.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
