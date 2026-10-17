package com.moneyflow.app

import android.app.Application
import androidx.room.Room
import com.moneyflow.app.data.MoneyFlowRepository
import com.moneyflow.app.data.SessionStore
import com.moneyflow.app.data.EncryptedLedgerRecoveryStore
import com.moneyflow.app.data.EncryptedImportRecoveryStore
import com.moneyflow.app.data.local.MoneyFlowDatabase
import com.moneyflow.app.data.remote.ApiFactory
import com.moneyflow.app.planning.PlanningReminders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MoneyFlowApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            repository.session.collectLatest { session ->
                runCatching { PlanningReminders.synchronizeSession(this@MoneyFlowApplication, session?.user?.id) }
            }
        }
    }
    val repository: MoneyFlowRepository by lazy {
        val database = Room.databaseBuilder(this, MoneyFlowDatabase::class.java, "moneyflow.db")
            .addMigrations(MoneyFlowDatabase.MIGRATION_1_2, MoneyFlowDatabase.MIGRATION_2_3, MoneyFlowDatabase.MIGRATION_3_4, MoneyFlowDatabase.MIGRATION_4_5, MoneyFlowDatabase.MIGRATION_5_6, MoneyFlowDatabase.MIGRATION_6_7).build()
        MoneyFlowRepository(database, SessionStore(this), ledgerRecovery = EncryptedLedgerRecoveryStore(this),
            importBlocked = { userId -> EncryptedImportRecoveryStore(this).read(userId) != null }, importsApiProvider = { token ->
            ApiFactory.createImports(BuildConfig.API_BASE_URL, token)
        }) { token ->
            ApiFactory.create(BuildConfig.API_BASE_URL, token)
        }
    }
}
