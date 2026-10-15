package com.moneyflow.app.data.domain

data class User(val id: String, val email: String)

data class Account(
    val id: String,
    val name: String,
    val currency: String,
    val openingBalanceMinor: Long,
    val balanceMinor: Long,
    val createdAt: String,
    val type: String = "card",
    val archived: Boolean = false,
    val includeInBalance: Boolean = true,
    val sortOrder: Int = 0,
    val version: Long = 1,
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
    val categoryId: String? = null,
    val excludeFromAnalytics: Boolean = false,
    val version: Long = 1,
    val deletedAt: String? = null,
    val source: String = "manual",
    val importId: String? = null,
    val transferId: String? = null,
    val transferRole: String? = null,
)


data class MoneyTransfer(
    val id: String,
    val fromAccountId: String,
    val toAccountId: String,
    val fromAmountMinor: Long,
    val toAmountMinor: Long,
    val fromCurrency: String,
    val toCurrency: String,
    val feeAccountId: String?,
    val feeAmountMinor: Long,
    val feeCurrency: String?,
    val note: String,
    val occurredAt: String,
    val createdAt: String,
    val version: Long,
    val deletedAt: String?,
    val transactions: List<MoneyTransaction>,
)
