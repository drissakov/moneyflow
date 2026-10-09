package com.moneyflow.app.data.domain

data class User(val id: String, val email: String)

data class Account(
    val id: String,
    val name: String,
    val currency: String,
    val openingBalanceMinor: Long,
    val balanceMinor: Long,
    val createdAt: String,
)

data class MoneyTransaction(
    val id: String,
    val accountId: String,
    val kind: String,
    val amountMinor: Long,
    val currency: String,
    val note: String,
    val occurredAt: String,
    val createdAt: String,
)
