package com.moneyflow.app.data.remote

import com.moneyflow.app.data.domain.MoneyTransaction
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Only responses that guarantee a rolled-back write may release a durable pending request. */
internal fun isDefinitiveTransactionFailure(status: Int, code: String?): Boolean =
    status in setOf(400, 403, 404, 413, 415, 422) || (status == 409 && code == "balance_overflow")

/** PostgreSQL persists timestamps to microsecond precision; tolerate that deliberate normalization. */
internal fun matchesTransactionRequest(transaction: MoneyTransaction, request: CreateTransactionRequest): Boolean =
    transaction.accountId == request.accountId && transaction.kind == request.kind &&
        transaction.amountMinor.toString() == request.amountMinor && transaction.note == request.note &&
        Instant.parse(transaction.occurredAt).truncatedTo(ChronoUnit.MICROS) ==
        Instant.parse(request.occurredAt).truncatedTo(ChronoUnit.MICROS)
