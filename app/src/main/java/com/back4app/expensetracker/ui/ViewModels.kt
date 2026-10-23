package com.back4app.expensetracker.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.back4app.expensetracker.AppContainer
import com.back4app.expensetracker.ExpenseApp
import com.back4app.expensetracker.backend.Auth
import com.back4app.expensetracker.data.CategoryEntity
import com.back4app.expensetracker.data.CategoryTotal
import com.back4app.expensetracker.data.ConflictEntity
import com.back4app.expensetracker.data.EntryEntity
import com.back4app.expensetracker.domain.Dates
import com.back4app.expensetracker.domain.Money
import com.parse.ParseException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------------------------------------------
data class LoginUiState(
    val email: String = "",
    val password: String = "",
    val createAccount: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

class LoginViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()
    val backendConfigured get() = c.backendConfigured

    fun setEmail(v: String) = _state.update { it.copy(email = v, error = null) }
    fun setPassword(v: String) = _state.update { it.copy(password = v, error = null) }
    fun toggleCreateAccount() = _state.update { it.copy(createAccount = !it.createAccount, error = null) }

    fun submit(onSuccess: () -> Unit) {
        val s = _state.value
        if (s.email.isBlank() || !s.email.contains('@')) return fail("Enter a valid e-mail address.")
        if (s.password.length < 8) return fail("Password must be at least 8 characters.")
        run(onSuccess) { if (s.createAccount) Auth.signUp(s.email, s.password) else Auth.signIn(s.email, s.password) }
    }

    fun continueAsGuest(onSuccess: () -> Unit) = run(onSuccess) { Auth.continueAsGuest() }

    private fun run(onSuccess: () -> Unit, block: suspend () -> Any) {
        if (!c.backendConfigured) return fail("Backend keys missing: see README (local.properties).")
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                block()
                afterSignIn()
                _state.update { it.copy(busy = false, password = "") }
                onSuccess()
            } catch (e: ParseException) {
                fail(friendly(e))
            } catch (e: Exception) {
                fail(e.message ?: "Unknown error")
            }
        }
    }

    private suspend fun afterSignIn() {
        val userId = Auth.currentUser()?.objectId
        if (userId != null && c.settings.lastUserId != userId) {
            c.repository.clearAll() // the local database belongs to one account
            c.settings.lastUserId = userId
        }
        runCatching { Auth.ensureUser() }.onFailure { Log.w(ExpenseApp.TAG, "ensureUser failed: ${it.message}") }
        c.syncScheduler.ensurePeriodicSync()
        c.syncScheduler.requestSync()
    }

    private fun fail(msg: String) = _state.update { it.copy(busy = false, error = msg) }

    private fun friendly(e: ParseException) = when (e.code) {
        ParseException.OBJECT_NOT_FOUND -> "Invalid e-mail or password."
        ParseException.USERNAME_TAKEN, ParseException.EMAIL_TAKEN -> "An account with this e-mail already exists."
        ParseException.CONNECTION_FAILED -> "No connection to the server."
        else -> e.message ?: "Parse error ${e.code}"
    }
}

// ---------------------------------------------------------------------------------------------------------------
data class EntriesUiState(
    val month: String,
    val entries: List<EntryEntity> = emptyList(),
    val totalCents: Long = 0,
    val categoryTotals: List<CategoryTotal> = emptyList(),
    val budgetCents: Long = 0,
    val pendingOps: Int = 0,
)

@OptIn(ExperimentalCoroutinesApi::class)
class EntriesViewModel(private val c: AppContainer) : ViewModel() {
    private val month = MutableStateFlow(Dates.currentMonth())

    val state: StateFlow<EntriesUiState> = month.flatMapLatest { m ->
        combine(
            c.repository.observeMonth(m),
            c.repository.observeMonthTotal(m),
            c.repository.observeCategoryTotals(m),
            c.settings.changes().map { c.settings.monthlyBudgetCents },
            c.repository.observePendingOps(),
        ) { entries, total, cats, budget, pending -> EntriesUiState(m, entries, total, cats, budget, pending) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EntriesUiState(month.value))

    init {
        viewModelScope.launch { c.repository.ensureDefaultCategories() }
        if (c.backendConfigured) {
            c.syncScheduler.ensurePeriodicSync()
            c.syncScheduler.requestSync()
        }
    }

    fun shiftMonth(delta: Long) = month.update { Dates.shiftMonth(it, delta) }
}

// ---------------------------------------------------------------------------------------------------------------
data class EntryFormUiState(
    val loading: Boolean,
    val isEdit: Boolean,
    val amountText: String = "",
    val category: String = "",
    val date: String = Dates.today(),
    val note: String = "",
    val categories: List<String> = emptyList(),
    val error: String? = null,
    val deleted: Boolean = false,
)

class EntryFormViewModel(private val c: AppContainer, private val entryKey: String?) : ViewModel() {
    private val _state = MutableStateFlow(EntryFormUiState(loading = entryKey != null, isEdit = entryKey != null))
    val state: StateFlow<EntryFormUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            c.repository.observeCategories().collect { cats ->
                _state.update { s ->
                    val names = cats.map { it.name }
                    s.copy(categories = names, category = s.category.ifEmpty { names.firstOrNull() ?: "" })
                }
            }
        }
        if (entryKey != null) viewModelScope.launch {
            val e = c.repository.observeEntry(entryKey).first()
            _state.update { s ->
                if (e == null) s.copy(loading = false, error = "Entry not found.")
                else s.copy(
                    loading = false, amountText = Money.toInput(e.amountCents), category = e.category, date = e.date,
                    note = e.note, deleted = e.deletedAt != null,
                )
            }
        }
    }

    fun setAmount(v: String) = _state.update { it.copy(amountText = v, error = null) }
    fun setCategory(v: String) = _state.update { it.copy(category = v, error = null) }
    fun setDate(v: String) = _state.update { it.copy(date = v, error = null) }
    fun setNote(v: String) = _state.update { it.copy(note = v) }

    fun save(onDone: () -> Unit) {
        val s = _state.value
        val cents = Money.parseCents(s.amountText) ?: return _state.update { it.copy(error = "Amount must look like 12.34 (max two decimals).") }
        if (cents == 0L) return _state.update { it.copy(error = "Amount must be greater than zero.") }
        if (s.category.isBlank()) return _state.update { it.copy(error = "Pick a category.") }
        if (!Dates.isValidDate(s.date)) return _state.update { it.copy(error = "Date must be yyyy-MM-dd.") }
        viewModelScope.launch {
            if (entryKey == null) c.repository.create(cents, s.category, s.date, s.note.trim())
            else c.repository.update(entryKey, cents, s.category, s.date, s.note.trim())
            onDone()
        }
    }

    fun delete(onDone: () -> Unit) {
        val key = entryKey ?: return
        viewModelScope.launch {
            c.repository.delete(key)
            onDone()
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
class CategoriesViewModel(private val c: AppContainer) : ViewModel() {
    val categories: StateFlow<List<CategoryEntity>> =
        c.repository.observeCategories().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(name: String) = viewModelScope.launch { c.repository.addCategory(name) }
    fun remove(category: CategoryEntity) = viewModelScope.launch { c.repository.removeCategory(category) }
}

// ---------------------------------------------------------------------------------------------------------------
data class SettingsUiState(
    val budgetCents: Long = 0,
    val pendingOps: Int = 0,
    val conflictCount: Int = 0,
    val conflicts: List<ConflictEntity> = emptyList(),
    val lastSyncAt: Long? = null,
    val lastError: String? = null,
    val syncing: Boolean = false,
    val account: String = Auth.displayName(),
    val isGuest: Boolean = Auth.isGuest(),
    val backendConfigured: Boolean = true,
)

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val state: StateFlow<SettingsUiState> = combine(
        c.settings.changes(),
        c.repository.observePendingOps(),
        c.repository.observeConflictCount(),
        c.repository.observeConflicts(),
        c.syncScheduler.observeSyncing(),
    ) { _, pending, conflictCount, conflicts, syncing ->
        SettingsUiState(
            budgetCents = c.settings.monthlyBudgetCents,
            pendingOps = pending,
            conflictCount = conflictCount,
            conflicts = conflicts,
            lastSyncAt = c.settings.lastSyncAt,
            lastError = c.settings.lastSyncError,
            syncing = syncing,
            backendConfigured = c.backendConfigured,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState(backendConfigured = c.backendConfigured))

    /** Returns false when the text is not a valid amount. */
    fun saveBudget(text: String): Boolean {
        val cents = Money.parseCents(text) ?: return false
        c.settings.monthlyBudgetCents = cents
        return true
    }

    fun syncNow() = c.syncScheduler.requestSync(force = true)

    fun signOut(onDone: () -> Unit) = viewModelScope.launch {
        c.syncScheduler.cancelAll()
        runCatching { Auth.signOut() }.onFailure { Log.w(ExpenseApp.TAG, "logOut: ${it.message}") }
        c.repository.clearAll()
        c.settings.lastUserId = null
        onDone()
    }
}
