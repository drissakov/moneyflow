package com.moneyflow.app

import android.app.Application
import androidx.room.Room
import com.moneyflow.app.data.MoneyFlowRepository
import com.moneyflow.app.data.SessionStore
import com.moneyflow.app.data.local.MoneyFlowDatabase
import com.moneyflow.app.data.remote.ApiFactory

class MoneyFlowApplication : Application() {
    val repository: MoneyFlowRepository by lazy {
        val database = Room.databaseBuilder(this, MoneyFlowDatabase::class.java, "moneyflow.db").build()
        MoneyFlowRepository(database, SessionStore(this)) { token ->
            ApiFactory.create(BuildConfig.API_BASE_URL, token)
        }
    }
}
