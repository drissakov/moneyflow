package com.moneyflow.app.data.remote

import com.google.gson.Gson
import com.moneyflow.app.data.Session
import com.moneyflow.app.data.domain.User
import com.moneyflow.app.data.domain.MoneyTransaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ResponseValidationTest {
    private val account = AccountDto("f7d784a7-8055-4135-9459-237f8b11002d", "Card", "USD", "0", "1", "2026-10-09T00:00:00Z")

    @Test fun `unknown currency and malformed balances are rejected before caching`() {
        assertThrows(IllegalArgumentException::class.java) { account.copy(currency = "XXX").toDomain() }
        assertThrows(IllegalArgumentException::class.java) { account.copy(balanceMinor = "1.2").toDomain() }
        assertThrows(IllegalArgumentException::class.java) { account.copy(balanceMinor = "01").toDomain() }
        assertThrows(IllegalArgumentException::class.java) { account.copy(openingBalanceMinor = "1000000000000001").toDomain() }
        assertEquals(Long.MIN_VALUE, account.copy(balanceMinor = Long.MIN_VALUE.toString()).toDomain().balanceMinor)
    }

    @Test fun `sessions loaded from old or incomplete JSON cannot validate`() {
        val now = Instant.parse("2026-10-09T00:00:00Z")
        val valid = Session("test", "2026-10-10T00:00:00Z", User(account.id, "test@example.com"))
        valid.validate(now)
        assertThrows(IllegalArgumentException::class.java) { valid.copy(user = User("bad-id", "test@example.com")).validate(now) }
        assertThrows(RuntimeException::class.java) { valid.copy(expiresAt = "invalid").validate(now) }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(expiresAt = "2026-10-08T00:00:00Z").validate(now) }
        val incomplete = Gson().fromJson("""{"token":"test"}""", Session::class.java)
        assertThrows(RuntimeException::class.java) { incomplete.validate(now) }
    }

    @Test fun `a restored pending request must use a known kind positive canonical amount and valid date`() {
        val valid = CreateTransactionRequest(account.id, "expense", "125", "Lunch", "2026-10-09T00:00:00Z")
        valid.validate()
        assertThrows(IllegalArgumentException::class.java) { valid.copy(kind = "transfer").validate() }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(amountMinor = "0").validate() }
        assertThrows(IllegalArgumentException::class.java) { valid.copy(amountMinor = "0125").validate() }
        assertThrows(RuntimeException::class.java) { valid.copy(occurredAt = "tomorrow").validate() }
        val incomplete = Gson().fromJson("""{"account_id":"${account.id}"}""", CreateTransactionRequest::class.java)
        assertThrows(RuntimeException::class.java) { incomplete.validate() }
    }

    @Test fun `balance overflow releases the pending request but ambiguous failures preserve it`() {
        assertTrue(isDefinitiveTransactionFailure(409, "balance_overflow"))
        assertTrue(isDefinitiveTransactionFailure(413, "payload_too_large"))
        assertTrue(isDefinitiveTransactionFailure(400, "validation_error"))
        assertFalse(isDefinitiveTransactionFailure(409, "idempotency_conflict"))
        assertFalse(isDefinitiveTransactionFailure(409, null))
        assertFalse(isDefinitiveTransactionFailure(500, "internal_error"))
        assertFalse(isDefinitiveTransactionFailure(408, null))
        assertFalse(isDefinitiveTransactionFailure(401, "unauthorized"))
        assertFalse(isDefinitiveTransactionFailure(429, "rate_limit"))
    }

    @Test fun `server acknowledgement may truncate nanoseconds to PostgreSQL microseconds`() {
        val request = CreateTransactionRequest(account.id, "expense", "125", "Lunch", "2026-10-09T00:00:00.123456789Z")
        val transaction = MoneyTransaction("c237bd71-8383-4aaf-9f45-17d796befd65", account.id,
            "expense", 125, "USD", "Lunch", "2026-10-09T00:00:00.123456Z", "2026-10-09T00:00:00Z")
        assertTrue(matchesTransactionRequest(transaction, request))
        assertFalse(matchesTransactionRequest(transaction.copy(amountMinor = 126), request))
        assertFalse(matchesTransactionRequest(transaction.copy(occurredAt = "2026-10-09T00:00:00.123455Z"), request))
    }
}
