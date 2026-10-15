package com.moneyflow.app.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moneyflow.app.data.remote.PendingLedgerMutation
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LedgerRecoveryStorageTest {
    private val user = "a3333333-3333-4333-8333-333333333333"
    @Test fun exactBodyAndKeyAreEncryptedDurableAndCannotBeOverwritten() {
        val context = isolatedContext()
        val prefs = context.getSharedPreferences("moneyflow_ledger_recovery", Context.MODE_PRIVATE)
        val name = "pending_$user"
        prefs.edit().remove(name).commit()
        try {
            val store = EncryptedLedgerRecoveryStore(context)
            val pending = PendingLedgerMutation(user, "b3333333-3333-4333-8333-333333333333", "deleteaccount",
                "c3333333-3333-4333-8333-333333333333", "{\"expected_version\":1}")
            val valid = pending.copy(operation = "deletetransaction")
            store.write(valid)
            assertEquals(valid, EncryptedLedgerRecoveryStore(context).read(user))
            assertFalse(prefs.getString(name, "")!!.contains("expected_version"))
            assertThrows(IllegalArgumentException::class.java) { store.write(valid.copy(body = "{\"expected_version\":2}")) }
            store.clear(user, "d3333333-3333-4333-8333-333333333333")
            assertEquals(valid, store.read(user))
            store.clear(user, valid.key); assertNull(store.read(user))
        } finally { prefs.edit().remove(name).commit() }
    }
    @Test fun corruptConfirmationIsRetainedAndFailsClosed() {
        val context = isolatedContext()
        val prefs = context.getSharedPreferences("moneyflow_ledger_recovery", Context.MODE_PRIVATE)
        val name = "pending_$user"
        prefs.edit().putString(name, "damaged-confirmation").commit()
        try {
            assertThrows(LedgerRecoveryUnavailable::class.java) { EncryptedLedgerRecoveryStore(context).read(user) }
            assertEquals("damaged-confirmation", prefs.getString(name, null))
        } finally { prefs.edit().remove(name).commit() }
    }
    private fun isolatedContext(): Context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("ledger_recovery_test_$name", mode)
    }

}
