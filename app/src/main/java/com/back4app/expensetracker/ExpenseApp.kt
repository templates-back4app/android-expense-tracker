package com.back4app.expensetracker

import android.app.Application
import android.util.Log
import com.back4app.expensetracker.backend.BackendConfig
import com.back4app.expensetracker.backend.ParseSyncGateway
import com.back4app.expensetracker.data.AppDatabase
import com.back4app.expensetracker.data.EntryRepository
import com.back4app.expensetracker.data.Settings
import com.back4app.expensetracker.sync.SyncScheduler
import com.parse.Parse

/**
 * Application: initialises the Parse SDK once and owns the single [AppContainer] (manual dependency wiring —
 * there is no DI framework in this project on purpose).
 */
class ExpenseApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        val config = BackendConfig.load(this)
        if (config != null) {
            Parse.initialize(
                Parse.Configuration.Builder(this)
                    .applicationId(config.appId)
                    .clientKey(config.clientKey)
                    .server(config.serverUrl)
                    .build()
            )
        } else {
            Log.w(TAG, "Backend not configured: add parse.appId / parse.clientKey to local.properties (see README).")
        }
        container = AppContainer(this, config)
    }

    companion object {
        const val TAG = "ExpenseTracker"
    }
}

class AppContainer(app: Application, val backendConfig: BackendConfig?) {
    val database: AppDatabase = AppDatabase.build(app)
    val settings: Settings = Settings(app)
    val syncScheduler: SyncScheduler = SyncScheduler(app)
    val repository: EntryRepository = EntryRepository(database, settings, syncScheduler)
    val gateway: ParseSyncGateway = ParseSyncGateway()
    val backendConfigured: Boolean get() = backendConfig != null
}
