package com.back4app.expensetracker.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.back4app.expensetracker.AppContainer
import com.back4app.expensetracker.domain.Dates
import com.back4app.expensetracker.ui.EntryFormViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryFormScreen(container: AppContainer, entryKey: String?, onDone: () -> Unit) {
    val vm: EntryFormViewModel = viewModel(key = entryKey ?: "new") { EntryFormViewModel(container, entryKey) }
    val s by vm.state.collectAsStateWithLifecycle()
    var categoryMenu by remember { mutableStateOf(false) }
    var datePicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (s.isEdit) "Edit expense" else "New expense") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    if (s.isEdit && !s.deleted) {
                        IconButton(onClick = { vm.delete(onDone) }) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
                    }
                },
            )
        },
    ) { padding ->
        if (s.loading) {
            Box(Modifier.fillMaxSize().padding(padding)) { CircularProgressIndicator(Modifier.padding(32.dp)) }
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            OutlinedTextField(
                value = s.amountText, onValueChange = vm::setAmount, label = { Text("Amount (USD)") }, placeholder = { Text("12.34") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(), enabled = !s.deleted,
            )
            Spacer(Modifier.height(12.dp))

            Box(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = s.category, onValueChange = {}, readOnly = true, label = { Text("Category") },
                    trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(), enabled = !s.deleted,
                )
                // The overlay takes the taps; give it the field's meaning too, or uiautomator/TalkBack see an unnamed box.
                Box(
                    Modifier.matchParentSize()
                        .semantics { contentDescription = "Category: ${s.category.ifEmpty { "none" }}"; role = Role.DropdownList }
                        .clickable(enabled = !s.deleted, onClickLabel = "Pick a category") { categoryMenu = true },
                )
                DropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                    s.categories.forEach { name ->
                        DropdownMenuItem(text = { Text(name) }, onClick = { vm.setCategory(name); categoryMenu = false })
                    }
                    if (s.categories.isEmpty()) DropdownMenuItem(text = { Text("No categories — add some first") }, onClick = { categoryMenu = false })
                }
            }
            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = s.date, onValueChange = vm::setDate, label = { Text("Date (yyyy-MM-dd)") }, singleLine = true,
                    modifier = Modifier.weight(1f), enabled = !s.deleted,
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { datePicker = true }, enabled = !s.deleted, modifier = Modifier.padding(top = 8.dp)) {
                    Icon(Icons.Filled.DateRange, contentDescription = "Pick a date")
                }
            }
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = s.note, onValueChange = vm::setNote, label = { Text("Note (optional)") },
                modifier = Modifier.fillMaxWidth(), minLines = 2, enabled = !s.deleted,
            )
            Spacer(Modifier.height(20.dp))

            if (s.deleted) {
                Text("This expense was deleted (tombstone). It stays hidden and is removed from the server after 90 days.",
                    color = MaterialTheme.colorScheme.error)
            } else {
                Button(onClick = { vm.save(onDone) }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
            }
            s.error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Saved locally first, then synced in the background. Amounts are stored as integer cents.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (datePicker) {
        val initial = runCatching { Dates.isoToEpochMillis(s.date) }.getOrNull()
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { datePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { vm.setDate(Dates.epochDayToIso(it)) }
                    datePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { datePicker = false }) { Text("Cancel") } },
        ) { DatePicker(state = pickerState) }
    }
}
