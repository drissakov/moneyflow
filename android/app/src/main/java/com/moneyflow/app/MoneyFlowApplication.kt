package com.moneyflow.app

import android.app.Application
import androidx.room.Room
import com.moneyflow.app.data.MoneyFlowRepository
import com.moneyflow.app.data.SessionStore
import com.moneyflow.app.data.EncryptedLedgerRecoveryStore
import com.moneyflow.app.data.EncryptedImportRecoveryStore
import com.moneyflow.app.data.local.MoneyFlowDatabase
import com.moneyflow.app.data.remote.ApiFactory

class MoneyFlowApplication : Application() {
    val repository: MoneyFlowRepository by lazy {
        val database = Room.databaseBuilder(this, MoneyFlowDatabase::class.java, "moneyflow.db")
            .addMigrations(MoneyFlowDatabase.MIGRATION_1_2, MoneyFlowDatabase.MIGRATION_2_3, MoneyFlowDatabase.MIGRATION_3_4, MoneyFlowDatabase.MIGRATION_4_5).build()
        MoneyFlowRepository(database, SessionStore(this), ledgerRecovery = EncryptedLedgerRecoveryStore(this),
            importBlocked = { userId -> EncryptedImportRecoveryStore(this).read(userId) != null }, importsApiProvider = { token ->
            ApiFactory.createImports(BuildConfig.API_BASE_URL, token)
        }) { token ->
            ApiFactory.create(BuildConfig.API_BASE_URL, token)
        }
    }
}
