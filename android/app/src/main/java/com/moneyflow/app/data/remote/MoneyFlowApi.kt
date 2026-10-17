package com.moneyflow.app.data.remote

import com.google.gson.annotations.SerializedName
import com.moneyflow.app.data.domain.Account
import com.moneyflow.app.data.domain.MoneyTransaction
import com.moneyflow.app.data.domain.Money
import com.moneyflow.app.data.domain.SupportedCurrency
import com.moneyflow.app.data.domain.User
import java.time.Instant
import java.util.UUID
import com.google.gson.JsonObject
import okhttp3.RequestBody
import retrofit2.http.HTTP
import retrofit2.http.Url
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
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
    val type: String? = null,
    val archived: Boolean? = null,
    @SerializedName("include_in_balance") val includeInBalance: Boolean? = null,
    @SerializedName("sort_order") val sortOrder: Int? = null,
    val version: Long? = null,
) {
    fun toDomain(): Account {
        validateUuid(id)
        require(name.codePointCount(0, name.length) in 1..100)
        SupportedCurrency.fromCode(currency)
        Instant.parse(createdAt)
        return Account(id, name, currency, parseServerMinor(openingBalanceMinor, bounded = true),
            parseServerMinor(balanceMinor), createdAt, (type ?: "card").also { require(it in ACCOUNT_TYPES) },
            archived ?: false, includeInBalance ?: true, sortOrder ?: 0, (version ?: 1).also { require(it >= 1) })
    }
}

data class AccountsResponse(val accounts: List<AccountDto>)

data class CreateAccountRequest(
    val name: String,
    val currency: String,
    @SerializedName("opening_balance_minor") val openingBalanceMinor: String,
    val type: String = "card",
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
    @SerializedName("category_id") val categoryId: String? = null,
    @SerializedName("exclude_from_analytics") val excludeFromAnalytics: Boolean = false,
    val version: Long? = null,
    @SerializedName("deleted_at") val deletedAt: String? = null,
    val source: String? = null,
    @SerializedName("import_id") val importId: String? = null,
    @SerializedName("transfer_id") val transferId: String? = null,
    @SerializedName("transfer_role") val transferRole: String? = null,
) {
    fun toDomain(): MoneyTransaction {
        validateUuid(id)
        validateUuid(accountId)
        categoryId?.let(::validateUuid)
        require(kind == "income" || kind == "expense")
        SupportedCurrency.fromCode(currency)
        require(note.codePointCount(0, note.length) <= 500)
        Instant.parse(occurredAt)
        Instant.parse(createdAt)
        deletedAt?.let(Instant::parse)
        importId?.let(::validateUuid)
        transferId?.let(::validateUuid)
        require(source == null || source in setOf("manual", "import", "transfer"))
        require(transferRole == null || transferRole in setOf("from", "to", "fee"))
        require((transferId == null) == (transferRole == null))
        return MoneyTransaction(id, accountId, kind, parseServerMinor(amountMinor, positive = true, bounded = true),
            currency, note, occurredAt, createdAt, categoryId, excludeFromAnalytics,
            (version ?: 1).also { require(it >= 1) }, deletedAt, source ?: "manual", importId, transferId, transferRole)
    }
}

data class TransactionsResponse(val transactions: List<TransactionDto>,
    @SerializedName("next_cursor") val nextCursor: String? = null)

data class CreateTransactionRequest(
    @SerializedName("account_id") val accountId: String,
    val kind: String,
    @SerializedName("amount_minor") val amountMinor: String,
    val note: String,
    @SerializedName("occurred_at") val occurredAt: String,
    @SerializedName("category_id") val categoryId: String? = null,
) {
    fun validate() {
        validateUuid(accountId)
        categoryId?.let(::validateUuid)
        require(kind == "income" || kind == "expense")
        parseServerMinor(amountMinor, positive = true, bounded = true)
        require(note.codePointCount(0, note.length) <= 500)
        Instant.parse(occurredAt)
    }
}

data class ErrorBody(val error: ErrorDetail)
data class ErrorDetail(val code: String, val message: String)

interface MoneyFlowApi {
    @GET("api/v1/analytics/dashboard") suspend fun analyticsDashboard(
        @Query("month") month: String,
        @Query("currency") currency: String,
        @Query("months") months: Int,
        @Query("account_id") accountId: String?,
        @Header("Authorization") authorization: String,
        @Query("mode") mode: String = "original",
        @Query("valuation") valuation: String? = null,
    ): AdvancedAnalyticsReportDto
    @GET("api/v1/planning") suspend fun planning(
        @Query("from") from: String,
        @Query("to") to: String,
        @Header("Authorization") authorization: String,
    ): PlanningResponseDto
    @GET("api/v1/planning/forecast") suspend fun forecast(
        @Query("until") until: String? = null,
        @Header("Authorization") authorization: String,
    ): ForecastResponseDto
    @GET("api/v1/transactions") suspend fun history(
        @Query("account_id") accountId: String? = null,
        @Query("limit") limit: Int = 50,
        @Query("cursor") cursor: String? = null,
        @Query("q") query: String? = null,
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
        @Query("category_id") categoryId: String? = null,
        @Query("kind") kind: String? = null,
        @Query("include_deleted") includeDeleted: Boolean = false,
        @Header("Authorization") authorization: String,
        @Query("currency") currency: String? = null,
        @Query("analytics_only") analyticsOnly: Boolean = false,
        @Query("uncategorized") uncategorized: Boolean = false,
        @Query("category_exact") categoryExact: Boolean = false,
    ): TransactionsResponse
    @GET("api/v1/transfers/{id}") suspend fun transfer(@Path("id") id: String,
        @Header("Authorization") authorization: String): TransferDto
    @PUT suspend fun ledgerPut(@Url path: String, @Body body: RequestBody,
        @Header("Idempotency-Key") key: String, @Header("Authorization") authorization: String): JsonObject
    @POST suspend fun ledgerPost(@Url path: String, @Body body: RequestBody,
        @Header("Idempotency-Key") key: String, @Header("Authorization") authorization: String): JsonObject
    @HTTP(method = "DELETE", hasBody = true) suspend fun ledgerDelete(@Url path: String, @Body body: RequestBody,
        @Header("Idempotency-Key") key: String, @Header("Authorization") authorization: String): JsonObject

    @POST("api/v1/auth/register") suspend fun register(@Body credentials: Credentials): AuthResponse
    @POST("api/v1/auth/login") suspend fun login(@Body credentials: Credentials): AuthResponse
    @POST("api/v1/auth/logout") suspend fun logout(@Header("Authorization") authorization: String? = null)
    @GET("api/v1/me") suspend fun me(): User
    @GET("api/v1/accounts") suspend fun accounts(): AccountsResponse
    @POST("api/v1/accounts") suspend fun createAccount(@Body body: CreateAccountRequest,
        @Header("Authorization") authorization: String? = null): AccountDto
    @GET("api/v1/transactions") suspend fun transactions(
        @Query("account_id") accountId: String,
        @Query("limit") limit: Int = 50,
    ): TransactionsResponse
    @POST("api/v1/transactions") suspend fun createTransaction(
        @Header("Idempotency-Key") key: String,
        @Body body: CreateTransactionRequest,
        @Header("Authorization") authorization: String? = null,
    ): TransactionDto
    @GET("api/v1/categories") suspend fun categories(@Header("Authorization") authorization: String): CategoriesResponse
    @POST("api/v1/categories") suspend fun createCategory(
        @Body body: CreateCategoryRequest,
        @Header("Authorization") authorization: String,
    ): CategoryDto
    @PUT("api/v1/transactions/{id}/category") suspend fun setTransactionCategory(
        @Path("id") transactionId: String,
        @Body body: SetTransactionCategoryRequest,
        @Header("Authorization") authorization: String,
    ): TransactionDto
    @GET("api/v1/analytics") suspend fun analytics(
        @Query("month") month: String,
        @Query("currency") currency: String,
        @Query("timezone") timezone: String,
        @Header("Authorization") authorization: String,
    ): MonthlyAnalyticsDto
    @GET("api/v1/currencies") suspend fun currencies(@Header("Authorization") authorization: String): CurrencyCatalogueDto
    @GET("api/v1/preferences") suspend fun preferences(@Header("Authorization") authorization: String): PreferencesDto
    @PUT("api/v1/preferences") suspend fun setPreferences(
        @Body body: PreferencesDto,
        @Header("Authorization") authorization: String,
    ): PreferencesDto
    @GET("api/v1/exchange-rates") suspend fun exchangeRates(
        @Query("base") base: String,
        @Header("Authorization") authorization: String,
    ): ExchangeRatesDto
    @POST("api/v1/convert") suspend fun convert(
        @Body body: ConvertRequest,
        @Header("Authorization") authorization: String,
    ): ConversionDto
    @GET("api/v1/portfolio") suspend fun portfolio(
        @Query("base") base: String,
        @Header("Authorization") authorization: String,
    ): PortfolioDto
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

internal val ACCOUNT_TYPES = setOf("card", "cash", "savings", "credit", "other")
