package com.moneyflow.app.data.remote

import com.google.gson.annotations.SerializedName
import com.moneyflow.app.data.domain.Account
import com.moneyflow.app.data.domain.MoneyTransaction
import com.moneyflow.app.data.domain.Money
import com.moneyflow.app.data.domain.SupportedCurrency
import com.moneyflow.app.data.domain.User
import java.time.Instant
import java.util.UUID
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

data class Credentials(val email: String, val password: String)

data class AuthResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("expires_at") val expiresAt: String,
    val user: User,
)

data class AccountDto(
    val id: String,
    val name: String,
    val currency: String,
    @SerializedName("opening_balance_minor") val openingBalanceMinor: String,
    @SerializedName("balance_minor") val balanceMinor: String,
    @SerializedName("created_at") val createdAt: String,
) {
    fun toDomain(): Account {
        validateUuid(id)
        require(name.codePointCount(0, name.length) in 1..100)
        SupportedCurrency.fromCode(currency)
        Instant.parse(createdAt)
        return Account(id, name, currency, parseServerMinor(openingBalanceMinor, bounded = true),
            parseServerMinor(balanceMinor), createdAt)
    }
}

data class AccountsResponse(val accounts: List<AccountDto>)

data class CreateAccountRequest(
    val name: String,
    val currency: String,
    @SerializedName("opening_balance_minor") val openingBalanceMinor: String,
)

data class TransactionDto(
    val id: String,
    @SerializedName("account_id") val accountId: String,
    val kind: String,
    @SerializedName("amount_minor") val amountMinor: String,
    val currency: String,
    val note: String,
    @SerializedName("occurred_at") val occurredAt: String,
    @SerializedName("created_at") val createdAt: String,
) {
    fun toDomain(): MoneyTransaction {
        validateUuid(id)
        validateUuid(accountId)
        require(kind == "income" || kind == "expense")
        SupportedCurrency.fromCode(currency)
        require(note.codePointCount(0, note.length) <= 500)
        Instant.parse(occurredAt)
        Instant.parse(createdAt)
        return MoneyTransaction(id, accountId, kind, parseServerMinor(amountMinor, positive = true, bounded = true),
            currency, note, occurredAt, createdAt)
    }
}

data class TransactionsResponse(val transactions: List<TransactionDto>)

data class CreateTransactionRequest(
    @SerializedName("account_id") val accountId: String,
    val kind: String,
    @SerializedName("amount_minor") val amountMinor: String,
    val note: String,
    @SerializedName("occurred_at") val occurredAt: String,
) {
    fun validate() {
        validateUuid(accountId)
        require(kind == "income" || kind == "expense")
        parseServerMinor(amountMinor, positive = true, bounded = true)
        require(note.codePointCount(0, note.length) <= 500)
        Instant.parse(occurredAt)
    }
}

data class ErrorBody(val error: ErrorDetail)
data class ErrorDetail(val code: String, val message: String)

interface MoneyFlowApi {
    @POST("api/v1/auth/register") suspend fun register(@Body credentials: Credentials): AuthResponse
    @POST("api/v1/auth/login") suspend fun login(@Body credentials: Credentials): AuthResponse
    @POST("api/v1/auth/logout") suspend fun logout(@Header("Authorization") authorization: String? = null)
    @GET("api/v1/me") suspend fun me(): User
    @GET("api/v1/accounts") suspend fun accounts(): AccountsResponse
    @POST("api/v1/accounts") suspend fun createAccount(@Body body: CreateAccountRequest): AccountDto
    @GET("api/v1/transactions") suspend fun transactions(
        @Query("account_id") accountId: String,
        @Query("limit") limit: Int = 50,
    ): TransactionsResponse
    @POST("api/v1/transactions") suspend fun createTransaction(
        @Header("Idempotency-Key") key: String,
        @Body body: CreateTransactionRequest,
    ): TransactionDto
}

internal fun validateUuid(value: String) {
    require(UUID.fromString(value).toString().equals(value, ignoreCase = true)) { "Некорректный идентификатор в ответе сервера" }
}

internal fun parseServerMinor(value: String, positive: Boolean = false, bounded: Boolean = false): Long {
    require(value.matches(Regex("0|-?[1-9]\\d*"))) { "Некорректная сумма в ответе сервера" }
    val minor = value.toLong()
    if (positive) require(minor > 0)
    if (bounded) require(minor in -Money.MAX_INPUT_MINOR..Money.MAX_INPUT_MINOR)
    return minor
}
