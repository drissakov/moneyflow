package com.moneyflow.app.data

import androidx.room.withTransaction
import com.google.gson.Gson
import com.moneyflow.app.data.domain.Account
import com.moneyflow.app.data.domain.MoneyTransaction
import com.moneyflow.app.data.domain.User
import com.moneyflow.app.data.domain.Category
import com.moneyflow.app.data.domain.MonthlyAnalytics
import com.moneyflow.app.data.local.AccountEntity
import com.moneyflow.app.data.local.MoneyFlowDatabase
import com.moneyflow.app.data.local.TransactionEntity
import com.moneyflow.app.data.local.FxPreferencesEntity
import com.moneyflow.app.data.local.PortfolioSnapshotEntity
import com.moneyflow.app.data.local.LedgerSnapshotEntity
import com.moneyflow.app.data.local.CategoryEntity
import com.moneyflow.app.data.local.PlanningSnapshotEntity
import com.moneyflow.app.data.domain.PlanningCalendar
import com.moneyflow.app.data.domain.AdvancedAnalyticsFilter
import com.moneyflow.app.data.domain.AdvancedAnalyticsReport
import com.moneyflow.app.data.local.AnalyticsSnapshotEntity
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import com.moneyflow.app.data.remote.*
import com.moneyflow.app.data.domain.MoneyTransfer
import com.moneyflow.app.data.remote.AuthResponse
import com.moneyflow.app.data.remote.CreateAccountRequest
import com.moneyflow.app.data.remote.CreateTransactionRequest
import com.moneyflow.app.data.remote.Credentials
import com.moneyflow.app.data.remote.MoneyFlowApi
import com.moneyflow.app.data.remote.ErrorBody
import com.moneyflow.app.data.remote.validateUuid
import com.moneyflow.app.data.remote.isDefinitiveTransactionFailure
import com.moneyflow.app.data.remote.matchesTransactionRequest
import com.moneyflow.app.data.remote.ConversionDto
import com.moneyflow.app.data.remote.ConvertRequest
import com.moneyflow.app.data.remote.CurrencyCatalogueDto
import com.moneyflow.app.data.remote.PortfolioDto
import com.moneyflow.app.data.remote.PreferencesDto
import com.moneyflow.app.data.remote.CreateCategoryRequest
import com.moneyflow.app.data.remote.SetTransactionCategoryRequest
import com.moneyflow.app.data.remote.normalizeCategoryName
import com.moneyflow.app.data.remote.ImportsApi
import com.moneyflow.app.data.remote.ImportPreviewRequest
import com.moneyflow.app.data.remote.ImportPreviewDto
import com.moneyflow.app.data.remote.ImportReceiptDto
import com.moneyflow.app.data.remote.PendingImportAction
import com.moneyflow.app.data.domain.SupportedCurrency
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import retrofit2.HttpException
import java.util.Locale
import java.util.UUID
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

data class CachedPortfolio(val portfolio: PortfolioDto, val receivedAt: Instant)
data class CachedPlanning(val report: PlanningResponseDto, val forecast: ForecastResponseDto, val receivedAt: Instant)
data class CachedAdvancedAnalytics(val report: AdvancedAnalyticsReport, val receivedAt: Instant)
private data class PlanningCachePayload(val report: PlanningResponseDto, val forecast: ForecastResponseDto)

class MoneyFlowRepository(
    private val database: MoneyFlowDatabase,
    private val store: SessionStore,
    private val ledgerRecovery: LedgerRecoveryStorage? = null,
    private val importBlocked: (String) -> Boolean = { false },
    importsApiProvider: ((() -> String?) -> ImportsApi)? = null,
    apiProvider: (() -> String?) -> MoneyFlowApi,
) {
    private val mutex = Mutex()
    private val preferenceSyncMutex = Mutex()
    private val categorySyncMutex = Mutex()
    private var portfolioRevision = 0L
    private var ledgerRevision = 0L
    private var planningRevision = 0L
    private var analyticsRevision = 0L
    private val ledgerMutationMutex = Mutex()
    private val _session = MutableStateFlow(store.readSession())
    val session: StateFlow<Session?> = _session.asStateFlow()
    private val _pending = MutableStateFlow(_session.value?.user?.id?.let(store::readPending))
    val pending: StateFlow<PendingTransaction?> = _pending.asStateFlow()
    private val _ledgerPending = MutableStateFlow<PendingLedgerMutation?>(null)
    val ledgerPending: StateFlow<PendingLedgerMutation?> = _ledgerPending.asStateFlow()
    private val _ledgerRecoveryUnavailable = MutableStateFlow(false)
    val ledgerRecoveryUnavailable: StateFlow<Boolean> = _ledgerRecoveryUnavailable.asStateFlow()
    init { restoreLedgerPending(_session.value?.user?.id) }

    private fun restoreLedgerPending(userId: String?) {
        _ledgerRecoveryUnavailable.value = false
        _ledgerPending.value = try { userId?.let { ledgerRecovery?.read(it) } }
        catch (_: LedgerRecoveryUnavailable) { _ledgerRecoveryUnavailable.value = true; null }
    }

    private val api = apiProvider { _session.value?.token }
    private val importsApi = importsApiProvider?.invoke { _session.value?.token }
    private val dao = database.dao()

    fun observeAccounts(userId: String): Flow<List<Account>> =
        dao.observeAccounts(userId).map { rows -> rows.map(AccountEntity::toDomain) }

    fun observeTransactions(userId: String, accountId: String): Flow<List<MoneyTransaction>> =
        dao.observeTransactions(userId, accountId).map { rows -> rows.map(TransactionEntity::toDomain) }

    fun observeCategories(userId: String): Flow<List<Category>> =
        dao.observeCategories(userId).map { rows -> rows.map(CategoryEntity::toDomain) }

    suspend fun previewImport(request: ImportPreviewRequest, expectedSession: Session? = null): ImportPreviewDto {
        request.validate()
        val current = requireSession()
        require(expectedSession == null || (expectedSession.token == current.token && expectedSession.user.id == current.user.id)) {
            "Сессия изменилась"
        }
        val result = requireNotNull(importsApi).preview(request, "Bearer ${current.token}").validated(request)
        check(isCurrentSession(current)) { "Сессия изменилась" }
        return result
    }

    suspend fun importsHistory(): List<ImportReceiptDto> {
        val current = requireSession()
        val result = requireNotNull(importsApi).history(20, "Bearer ${current.token}").validated().imports
        check(isCurrentSession(current)) { "Сессия изменилась" }
        return result
    }

    suspend fun executeImport(action: PendingImportAction): ImportReceiptDto = ledgerMutationMutex.withLock importMutation@ {
        action.validate()
        val current = requireSession()
        require(action.userId == current.user.id) { "Войдите в аккаунт, создавший подтверждение" }
        checkLedgerAllowsLegacyWrite(current.user.id)
        val remote = requireNotNull(importsApi)
        val result = if (action.operation == "commit") remote.commit(action.draftId,
            requireNotNull(action.selection), action.key, "Bearer ${current.token}")
        else remote.undo(action.draftId, emptyMap(), action.key, "Bearer ${current.token}")
        result.validated()
        require(result.id == action.draftId && result.accountId == action.accountId &&
            result.status == if (action.operation == "commit") "committed" else "undone") {
            "Ответ сервера не соответствует подтверждению импорта"
        }
        if (action.operation == "commit") require(result.importedCount == action.selection?.rows?.size &&
            result.skippedCount == action.totalRows - result.importedCount) { "Сервер вернул другое число операций" }
        else require(result.importedCount == action.originalImportedCount)
        mutex.withLock {
            check(isCurrentSession(current)) { "Сессия изменилась" }
            database.withTransaction {
                portfolioRevision++
                ledgerRevision++
                planningRevision++
                dao.deletePlanningSnapshots(current.user.id)
                invalidateAdvancedAnalytics(current.user.id)
                dao.deleteLedgerSnapshots(current.user.id)
                dao.deletePortfolios(current.user.id)
                // Imported or removed entries may be outside the cached latest window.
                dao.deleteTransactions(current.user.id, action.accountId)
            }
        }
        return@importMutation result
    }

    suspend fun refreshCategories() {
        val current = requireSession()
        categorySyncMutex.withLock {
            check(isCurrentSession(current)) { "Сессия изменилась" }
            val revision = mutex.withLock { ledgerRevision }
            val categories = api.categories("Bearer ${current.token}").toDomain()
            mutex.withLock {
                check(isCurrentSession(current)) { "Сессия изменилась" }
                if (revision != ledgerRevision) return@withLock
                database.withTransaction {
                    dao.deleteCategories(current.user.id)
                    dao.putCategories(categories.map { CategoryEntity.from(current.user.id, it) })
                }
            }
        }
    }

    suspend fun createCategory(request: CreateCategoryRequest): Category = ledgerMutationMutex.withLock categoryMutation@ {
        val normalized = request.copy(name = normalizeCategoryName(request.name))
        normalized.validate()
        val current = requireSession()
        checkLedgerAllowsLegacyWrite(current.user.id)
        return@categoryMutation categorySyncMutex.withLock {
            check(isCurrentSession(current)) { "Сессия изменилась" }
            normalized.parentId?.let { parentId ->
                val parent = mutex.withLock {
                    check(isCurrentSession(current)) { "Сессия изменилась" }
                    dao.category(current.user.id, parentId)
                }
                require(parent?.kind == "expense" && parent.parentId == null) { "Выберите основную категорию расходов" }
            }
            val category = api.createCategory(normalized, "Bearer ${current.token}").toDomain()
            require(category.kind == normalized.kind && category.name == normalized.name && category.parentId == normalized.parentId &&
                !category.isDefault) { "Ответ сервера не соответствует создаваемой категории" }
            mutex.withLock {
                check(isCurrentSession(current)) { "Сессия изменилась" }
                database.withTransaction {
                    dao.putCategories(listOf(CategoryEntity.from(current.user.id, category)))
                    planningRevision++
                    dao.deletePlanningSnapshots(current.user.id)
                    invalidateAdvancedAnalytics(current.user.id)
                }
            }
            category
        }
    }

    suspend fun setTransactionCategory(transaction: MoneyTransaction, categoryId: String?): MoneyTransaction = ledgerMutationMutex.withLock categoryMutation@ {
        validateUuid(transaction.id)
        val current = requireSession()
        checkLedgerAllowsLegacyWrite(current.user.id)
        validateTransactionCategory(current, transaction.kind, categoryId)
        val result = api.setTransactionCategory(transaction.id, SetTransactionCategoryRequest(categoryId),
            "Bearer ${current.token}").toDomain()
        require(result.categoryId == categoryId && result.copy(categoryId = transaction.categoryId, version = transaction.version) == transaction) {
            "Ответ сервера изменил финансовые данные операции"
        }
        mutex.withLock {
            check(isCurrentSession(current)) { "Сессия изменилась" }
            database.withTransaction {
                ledgerRevision++
                planningRevision++
                dao.deletePlanningSnapshots(current.user.id)
                invalidateAdvancedAnalytics(current.user.id)
                dao.deleteLedgerSnapshots(current.user.id)
                dao.putTransactions(listOf(TransactionEntity.from(current.user.id, result)))
            }
        }
        return@categoryMutation result
    }

    suspend fun fetchAnalytics(month: String, currency: String, timezone: String): MonthlyAnalytics {
        require(month.matches(Regex("\\d{4}-\\d{2}"))) { "Выберите месяц" }
        YearMonth.parse(month)
        SupportedCurrency.fromCode(currency)
        ZoneId.of(timezone)
        val current = requireSession()
        val result = api.analytics(month, currency, timezone, "Bearer ${current.token}")
            .toDomain(month, currency, timezone)
        check(isCurrentSession(current)) { "Сессия изменилась" }
        return result
    }

    private suspend fun validateTransactionCategory(current: Session, kind: String, categoryId: String?) {
        categoryId?.let { id ->
            validateUuid(id)
            val category = mutex.withLock {
                check(isCurrentSession(current)) { "Сессия изменилась" }
                dao.category(current.user.id, id)
            }
            require(category?.kind == kind && !category.archived) { "Выберите категорию подходящего типа" }
        }
    }

    fun observePreferences(userId: String): Flow<FxPreferencesEntity> = dao.observePreferences(userId).map {
        it?.takeIf { row -> runCatching { SupportedCurrency.fromCode(row.baseCurrency) }.isSuccess }
            ?: FxPreferencesEntity(userId, "KZT", false)
    }

    fun observePortfolio(userId: String, base: String): Flow<CachedPortfolio?> = dao.observePortfolio(userId, base).map { row ->
        row?.let {
            runCatching {
                val portfolio = Gson().fromJson(it.payload, PortfolioDto::class.java).validated()
                require(portfolio.baseCurrency == base && it.userId == userId)
                CachedPortfolio(portfolio, Instant.parse(it.receivedAt))
            }.getOrNull()
        }
    }

    suspend fun refreshCurrencies(): CurrencyCatalogueDto {
        val current = requireSession()
        return api.currencies("Bearer ${current.token}").validated()
    }

    /** Local choice is durable immediately; an offline change is synchronized before the next read. */
    suspend fun setBaseCurrency(base: String) {
        SupportedCurrency.fromCode(base)
        val current = requireSession()
        mutex.withLock {
            if (!isCurrentSession(current)) return
            database.withTransaction {
                if (dao.preferences(current.user.id)?.baseCurrency != base) {
                    planningRevision++
                    dao.deletePlanningSnapshots(current.user.id)
                }
                dao.putPreferences(FxPreferencesEntity(current.user.id, base, true))
            }
        }
        refreshPreferences()
    }

    suspend fun refreshPreferences() = preferenceSyncMutex.withLock {
        val current = requireSession()
        val before = mutex.withLock { dao.preferences(current.user.id) }
        val remote = if (before?.pendingSync == true) {
            api.setPreferences(PreferencesDto(before.baseCurrency), "Bearer ${current.token}").validated().also {
                require(it.baseCurrency == before.baseCurrency) { "Сервер вернул другую основную валюту" }
            }
        } else api.preferences("Bearer ${current.token}").validated()
        mutex.withLock {
            if (!isCurrentSession(current) || dao.preferences(current.user.id) != before) return@withLock
            database.withTransaction {
                // Until planning settings are explicitly saved, their default follows the app base.
                // Once settings exist this refresh only discards a harmless read-only snapshot.
                if ((before != null && before.baseCurrency != remote.baseCurrency) || before?.pendingSync == true) {
                    planningRevision++
                    dao.deletePlanningSnapshots(current.user.id)
                }
                dao.putPreferences(FxPreferencesEntity(current.user.id, remote.baseCurrency, false))
            }
        }
    }

    suspend fun preferredBaseCurrency(): String {
        val current = requireSession()
        return dao.preferences(current.user.id)?.baseCurrency ?: "KZT"
    }

    suspend fun refreshPortfolio(base: String): PortfolioDto {
        SupportedCurrency.fromCode(base)
        val current = requireSession()
        val revision = mutex.withLock { portfolioRevision }
        val portfolio = api.portfolio(base, "Bearer ${current.token}").validated()
        require(portfolio.baseCurrency == base) { "Сервер вернул обзор в другой валюте" }
        mutex.withLock {
            val selectedBase = dao.preferences(current.user.id)?.baseCurrency ?: "KZT"
            if (isCurrentSession(current) && selectedBase == base && revision == portfolioRevision) {
                dao.putPortfolio(PortfolioSnapshotEntity(current.user.id, base, Gson().toJson(portfolio), Instant.now().toString()))
            }
        }
        return portfolio
    }

    suspend fun convert(request: ConvertRequest): ConversionDto {
        val current = requireSession()
        val result = api.convert(request, "Bearer ${current.token}").validated(request)
        check(isCurrentSession(current)) { "Сессия изменилась" }
        return result
    }

    suspend fun authenticate(email: String, password: String, register: Boolean) = withContext(Dispatchers.IO) {
        val credentials = Credentials(email.trim().lowercase(Locale.ROOT), password)
        val response = if (register) api.register(credentials) else api.login(credentials)
        saveSession(response)
    }

    private suspend fun saveSession(response: AuthResponse) = mutex.withLock {
        val current = Session(response.accessToken, response.expiresAt, response.user)
        current.validate()
        store.writeSession(current)
        _pending.value = store.readPending(current.user.id)
        restoreLedgerPending(current.user.id)
        _session.value = current
    }

    suspend fun verifySession(): User = api.me().also {
        validateUuid(it.id)
        require(it.email.isNotBlank())
    }

    /** Sign-in expiry retains a pending request for the same user to recover safely after re-authentication. */
    suspend fun expireSession(expectedSession: Session? = null) = withContext(Dispatchers.IO) {
        clearLocalSession(expectedSession)
    }

    suspend fun logout(): Boolean = withContext(Dispatchers.IO) {
        val token = _session.value?.token
        // Persist local sign-out before attempting network revocation, including if the process is killed.
        clearLocalSession()
        var serverRevoked = false
        try {
            api.logout(token?.let { "Bearer $it" })
            serverRevoked = true
        } catch (error: HttpException) {
            serverRevoked = error.code() == 401
        } catch (_: java.io.IOException) {
            // Local sign-out still removes the token and the user's cached financial data.
        }
        serverRevoked
    }

    private suspend fun clearLocalSession(expectedSession: Session? = null) = withContext(NonCancellable + Dispatchers.IO) {
        mutex.withLock {
            if (expectedSession != null && !isCurrentSession(expectedSession)) return@withLock
            val userId = _session.value?.user?.id
            store.clearSession()
            _session.value = null
            _pending.value = null
            _ledgerPending.value = null
            _ledgerRecoveryUnavailable.value = false
            ledgerRevision++
            planningRevision++
            analyticsRevision++
            if (userId != null) database.withTransaction {
                dao.deleteAccounts(userId)
                dao.deleteUserTransactions(userId)
                dao.deleteLedgerSnapshots(userId)
                dao.deletePortfolios(userId)
                dao.deleteCategories(userId)
                dao.deletePlanningSnapshots(userId)
                dao.deleteAnalyticsSnapshots(userId)
            }
        }
    }

    suspend fun refreshAccounts() {
        val current = requireSession()
        val userId = current.user.id
        val revision = mutex.withLock { ledgerRevision }
        val accounts = api.accounts().accounts.map { AccountEntity.from(userId, it.toDomain()) }
        mutex.withLock {
            if (!isCurrentSession(current) || revision != ledgerRevision) return
            database.withTransaction {
                dao.deleteAccounts(userId)
                dao.putAccounts(accounts)
            }
        }
    }

    suspend fun refreshTransactions(accountId: String) {
        val current = requireSession()
        val userId = current.user.id
        val revision = mutex.withLock { ledgerRevision }
        val transactions = api.transactions(accountId).transactions.map {
            require(it.accountId == accountId) { "Сервер вернул операции другого счёта" }
            TransactionEntity.from(userId, it.toDomain())
        }
        mutex.withLock {
            if (!isCurrentSession(current) || revision != ledgerRevision) return
            database.withTransaction {
                dao.deleteTransactions(userId, accountId)
                dao.putTransactions(transactions)
            }
        }
    }

    suspend fun createAccount(body: CreateAccountRequest, expectedSession: Session? = null): Account = ledgerMutationMutex.withLock accountMutation@ {
        val current = requireSession()
        require(expectedSession == null || (expectedSession.token == current.token && expectedSession.user.id == current.user.id)) {
            "Сессия изменилась"
        }
        val userId = current.user.id
        checkLedgerAllowsLegacyWrite(userId)
        val account = api.createAccount(body, "Bearer ${current.token}").toDomain()
        require(account.name == body.name && account.currency == body.currency &&
            account.openingBalanceMinor.toString() == body.openingBalanceMinor) { "Ответ сервера не соответствует создаваемому счёту" }
        mutex.withLock {
            check(isCurrentSession(current)) { "Сессия изменилась" }
            database.withTransaction {
                dao.putAccounts(listOf(AccountEntity.from(userId, account)))
                portfolioRevision++
                planningRevision++
                dao.deletePlanningSnapshots(userId)
                invalidateAdvancedAnalytics(userId)
                dao.deletePortfolios(userId)
            }
        }
        return@accountMutation account
    }

    suspend fun createTransaction(body: CreateTransactionRequest): MoneyTransaction = ledgerMutationMutex.withLock transactionMutation@ {
        body.validate()
        val current = requireSession()
        val userId = current.user.id
        validateTransactionCategory(current, body.kind, body.categoryId)
        val pending = withContext(Dispatchers.IO) {
            mutex.withLock {
                check(isCurrentSession(current)) { "Сессия изменилась" }
                checkLedgerAllowsLegacyWrite(userId)
                require(!importBlocked(userId)) { "Сначала подтвердите ожидающий импорт" }
                val existing = store.readPending(userId)
                require(existing == null || existing.body == body) {
                    "Сначала подтвердите предыдущую операцию кнопкой «Повторить»"
                }
                val request = existing ?: PendingTransaction(userId, UUID.randomUUID().toString(), body)
                store.writePending(request) // Persist the key and the exact body before the network request.
                _pending.value = request
                request
            }
        }
        return@transactionMutation submitPending(pending)
    }

    suspend fun retryPending(): MoneyTransaction = ledgerMutationMutex.withLock transactionRetry@ {
        val request = _pending.value ?: throw IllegalStateException("Нет ожидающей операции")
        require(request.userId == requireUserId())
        return@transactionRetry submitPending(request)
    }

    private suspend fun submitPending(request: PendingTransaction): MoneyTransaction {
        val current = requireSession()
        require(request.userId == current.user.id) { "Ожидающая операция принадлежит другому пользователю" }
        checkLedgerAllowsLegacyWrite(current.user.id)
        val transaction = try {
            api.createTransaction(request.key, request.body, "Bearer ${current.token}").toDomain().also {
                require(matchesTransactionRequest(it, request.body)) {
                    "Ответ сервера не соответствует ожидающей операции. Повторите отправку"
                }
            }
        } catch (error: HttpException) {
            // Definitive validation/authorization failures did not commit a financial write.
            // 401 retains the request for re-authentication; uncertain outcomes retain the key too.
            val errorCode = runCatching {
                val body = error.response()?.errorBody()?.source()?.peek()?.readUtf8()
                Gson().fromJson(body, ErrorBody::class.java)?.error?.code
            }.getOrNull()
            val definitiveFailure = isDefinitiveTransactionFailure(error.code(), errorCode)
            if (definitiveFailure) withContext(Dispatchers.IO) {
                mutex.withLock {
                    if (store.readPending(request.userId)?.key == request.key) store.clearPending(request.userId)
                    if (_pending.value?.userId == request.userId && _pending.value?.key == request.key) {
                        _pending.value = null
                    }
                }
            }
            throw error
        }
        withContext(Dispatchers.IO) {
            mutex.withLock {
                check(isCurrentSession(current)) { "Сессия изменилась" }
                database.withTransaction {
                    // Idempotency replay may describe a transaction edited or deleted on another device.
                    // Refresh instead of re-inserting the saved response into current cached history.
                    dao.deleteTransactions(request.userId, transaction.accountId)
                    dao.deleteLedgerSnapshots(request.userId)
                    ledgerRevision++
                    planningRevision++
                    dao.deletePlanningSnapshots(request.userId)
                    invalidateAdvancedAnalytics(request.userId)
                    portfolioRevision++
                    dao.deletePortfolios(request.userId)
                }
                if (store.readPending(request.userId)?.key == request.key) store.clearPending(request.userId)
                if (_pending.value?.userId == request.userId && _pending.value?.key == request.key) {
                    _pending.value = null
                }
            }
        }
        return transaction
    }

    suspend fun recheckLedgerRecovery() = withContext(Dispatchers.IO) {
        val current = requireSession()
        mutex.withLock {
            check(isCurrentSession(current)) { "Сессия изменилась" }
            restoreLedgerPending(current.user.id)
        }
    }

    private fun checkLedgerAllowsLegacyWrite(userId: String) {
        check(!_ledgerRecoveryUnavailable.value) { "Сохранённое подтверждение недоступно; изменения заблокированы" }
        require(_ledgerPending.value?.userId != userId) { "Сначала повторите ожидающее изменение в журнале" }
    }

    suspend fun cachedHistory(filter: LedgerFilter): List<MoneyTransaction>? {
        filter.validate(); val current = requireSession()
        val row = dao.ledgerSnapshot(current.user.id, Gson().toJson(filter)) ?: return null
        check(isCurrentSession(current)) { "Сессия изменилась" }
        return runCatching {
            Gson().fromJson(row.payload, TransactionsResponse::class.java).transactions.map(TransactionDto::toDomain)
                .also { require(it.size <= 50) }
        }.getOrNull()
    }

    suspend fun history(filter: LedgerFilter, cursor: String? = null): TransactionsResponse {
        filter.validate(); require(cursor == null || cursor.length in 1..4096)
        val current = requireSession(); val revision = mutex.withLock { ledgerRevision }
        val result = api.history(filter.accountId, 50, cursor, filter.query.trim().takeIf { it.isNotEmpty() },
            filter.from, filter.to, filter.categoryId, filter.kind, filter.showDeleted, "Bearer ${current.token}",
            filter.currency, filter.analyticsOnly, filter.uncategorized, filter.categoryExact)
        val rows = result.transactions.map(TransactionDto::toDomain)
        require(rows.size <= 50 && rows.map { it.id }.distinct().size == rows.size)
        rows.forEach {
            require(filter.accountId == null || it.accountId == filter.accountId)
            require(filter.kind == null || it.kind == filter.kind)
            require(filter.showDeleted || it.deletedAt == null)
            require(filter.currency == null || filter.currency == it.currency)
            require(!filter.analyticsOnly || !it.excludeFromAnalytics)
            require(!filter.uncategorized || it.categoryId == null)
            require(!filter.categoryExact || it.categoryId == filter.categoryId)
            filter.from?.let { from -> require(Instant.parse(it.occurredAt) >= Instant.parse(from)) }
            filter.to?.let { to -> require(Instant.parse(it.occurredAt) < Instant.parse(to)) }
        }
        result.nextCursor?.let { require(it.length in 1..4096 && it != cursor) }
        mutex.withLock {
            check(isCurrentSession(current) && revision == ledgerRevision) { "История изменилась; обновите журнал" }
            if (cursor == null) database.withTransaction {
                dao.putLedgerSnapshot(LedgerSnapshotEntity(current.user.id, Gson().toJson(filter), Gson().toJson(result), Instant.now().toString()))
                dao.pruneLedgerSnapshots(current.user.id)
            }
        }
        return result
    }

    suspend fun fetchTransfer(id: String): MoneyTransfer {
        validateUuid(id); val current = requireSession()
        val result = api.transfer(id, "Bearer ${current.token}").toDomain()
        require(result.id == id); check(isCurrentSession(current)) { "Сессия изменилась" }
        return result
    }

    suspend fun advancedAnalytics(filter: AdvancedAnalyticsFilter): CachedAdvancedAnalytics {
        filter.validate()
        val current = requireSession(); val revision = mutex.withLock { analyticsRevision }
        val response = api.analyticsDashboard(filter.month, filter.currency, filter.months, filter.accountId, "Bearer ${current.token}")
        val result = CachedAdvancedAnalytics(response.toDomain(filter), Instant.now())
        mutex.withLock {
            check(isCurrentSession(current) && analyticsRevision == revision) { "Данные изменились; обновите отчёт" }
            database.withTransaction {
                dao.putAnalyticsSnapshot(AnalyticsSnapshotEntity(current.user.id, Gson().toJson(filter), Gson().toJson(response), result.receivedAt.toString()))
                dao.pruneAnalyticsSnapshots(current.user.id)
            }
        }
        return result
    }

    suspend fun cachedAdvancedAnalytics(filter: AdvancedAnalyticsFilter): CachedAdvancedAnalytics? {
        filter.validate(); val current = requireSession()
        val row = dao.analyticsSnapshot(current.user.id, Gson().toJson(filter)) ?: return null
        check(isCurrentSession(current)) { "Сессия изменилась" }
        return runCatching {
            val report = Gson().fromJson(row.payload, AdvancedAnalyticsReportDto::class.java).toDomain(filter)
            CachedAdvancedAnalytics(report, Instant.parse(row.receivedAt))
        }.getOrNull()
    }

    /** Call under the repository mutex and the same transaction as the acknowledged financial write. */
    private suspend fun invalidateAdvancedAnalytics(userId: String) {
        analyticsRevision++
        dao.deleteAnalyticsSnapshots(userId)
    }

    /** The server owns every total; the device only validates and caches the matching read. */
    suspend fun planning(month: String, forecastUntil: String? = null): CachedPlanning = coroutineScope {
        val selected = PlanningCalendar.validateMonth(month)
        forecastUntil?.let(PlanningCalendar::validateDate)
        val from = selected.atDay(1).toString(); val to = selected.atEndOfMonth().toString()
        val current = requireSession(); val revision = mutex.withLock { planningRevision }
        val reportRequest = async { api.planning(from, to, "Bearer ${current.token}").validated(from, to) }
        val forecastRequest = async { api.forecast(forecastUntil, "Bearer ${current.token}").validated(forecastUntil) }
        val report = reportRequest.await(); val forecast = forecastRequest.await()
        require(report.settings.baseCurrency == forecast.baseCurrency && report.settings.reserveMinor == forecast.reserveMinor) {
            "Настройки расчёта изменились; обновите план"
        }
        require(report.today == forecast.asOf) { "Начался новый день; обновите план" }
        val result = CachedPlanning(report, forecast, Instant.now())
        mutex.withLock {
            check(isCurrentSession(current) && planningRevision == revision) { "Данные изменились; обновите план" }
            database.withTransaction {
                dao.putPlanningSnapshot(PlanningSnapshotEntity(current.user.id, month, forecastUntil.orEmpty(),
                    Gson().toJson(PlanningCachePayload(report, forecast)), result.receivedAt.toString()))
                dao.prunePlanningSnapshots(current.user.id)
            }
        }
        result
    }

    suspend fun cachedPlanning(month: String, forecastUntil: String? = null): CachedPlanning? {
        val selected = PlanningCalendar.validateMonth(month); forecastUntil?.let(PlanningCalendar::validateDate)
        val current = requireSession()
        val row = dao.planningSnapshot(current.user.id, month, forecastUntil.orEmpty()) ?: return null
        check(isCurrentSession(current)) { "Сессия изменилась" }
        return runCatching {
            val value = Gson().fromJson(row.payload, PlanningCachePayload::class.java)
            value.report.validated(selected.atDay(1).toString(), selected.atEndOfMonth().toString())
            value.forecast.validated(forecastUntil)
            require(value.report.settings.baseCurrency == value.forecast.baseCurrency && value.report.settings.reserveMinor == value.forecast.reserveMinor)
            require(value.report.today == value.forecast.asOf)
            CachedPlanning(value.report, value.forecast, Instant.parse(row.receivedAt))
        }.getOrNull()
    }

    /** Generic reminders use a fresh authenticated count, never cached financial amounts. */
    suspend fun planningReminderDueCount(expectedSession: Session): Int? {
        if (!isCurrentSession(expectedSession)) return null
        val today = PlanningCalendar.today().toString()
        val report = try {
            api.planning(today, today, "Bearer ${expectedSession.token}").validated(today, today)
        } catch (error: HttpException) {
            if (error.code() == 401) expireSession(expectedSession)
            throw error
        }
        if (!isCurrentSession(expectedSession) || report.today != today) return null
        return Math.addExact(report.overdueCount,
            report.occurrences.count { it.status == "pending" && it.dueDate == today })
    }

    suspend fun mutateLedger(operation: String, entityId: String?, body: JsonObject, occurrenceDate: String? = null): JsonObject = ledgerMutationMutex.withLock {
        val current = requireSession()
        val request = withContext(Dispatchers.IO) { mutex.withLock {
            check(isCurrentSession(current)) { "Сессия изменилась" }
            check(!_ledgerRecoveryUnavailable.value) { "Сохранённое подтверждение недоступно; изменения заблокированы" }
            require(_pending.value == null) { "Сначала повторите ожидающую ручную операцию" }
            require(!importBlocked(current.user.id)) { "Сначала подтвердите ожидающий импорт" }
            val existing = ledgerRecovery?.read(current.user.id) ?: _ledgerPending.value
            val serialized = body.toString()
            require(existing == null || (existing.operation == operation && existing.entityId == entityId && existing.body == serialized && existing.occurrenceDate == occurrenceDate)) {
                "Сначала повторите сохранённое изменение"
            }
            val pending = existing ?: PendingLedgerMutation(current.user.id, UUID.randomUUID().toString(), operation, entityId, serialized, occurrenceDate)
            pending.validate()
            requireNotNull(ledgerRecovery) { "Хранилище подтверждений недоступно" }.write(pending)
            _ledgerPending.value = pending
            pending
        } }
        submitLedger(request, current)
    }

    suspend fun retryLedgerMutation(): JsonObject = ledgerMutationMutex.withLock {
        val current = requireSession()
        val pending = _ledgerPending.value ?: throw IllegalStateException("Нет ожидающего изменения")
        require(pending.userId == current.user.id)
        submitLedger(pending, current)
    }

    private suspend fun submitLedger(request: PendingLedgerMutation, current: Session): JsonObject {
        request.validate()
        val result = try {
            val body = request.body.toRequestBody("application/json; charset=utf-8".toMediaType())
            val response = when (request.method) {
                "PUT" -> api.ledgerPut(request.path, body, request.key, "Bearer ${current.token}")
                "DELETE" -> api.ledgerDelete(request.path, body, request.key, "Bearer ${current.token}")
                "POST" -> api.ledgerPost(request.path, body, request.key, "Bearer ${current.token}")
                else -> throw IllegalArgumentException("Неизвестный метод подтверждения")
            }
            validateLedgerAcknowledgement(request, response)
            response
        } catch (error: HttpException) {
            val code = runCatching { Gson().fromJson(error.response()?.errorBody()?.source()?.peek()?.readUtf8(), ErrorBody::class.java)?.error?.code }.getOrNull()
            val definitive = error.code() in setOf(400, 403, 404, 410, 413, 415, 422) ||
                (error.code() == 409 && code in setOf("version_conflict", "balance_overflow", "archived_account", "archived_category",
                    "transaction_deleted", "transaction_active", "transfer_managed", "imported_account_immutable", "import_undone", "category_conflict", "category_exists", "transfer_deleted", "transfer_active",
                    "budget_overlap", "schedule_anchor_locked", "planning_limit", "archived_schedule", "paused", "occurrence_posted", "occurrence_state", "conversion_overflow"))
            if (definitive) clearLedgerPending(request)
            throw error
        }
        withContext(NonCancellable + Dispatchers.IO) { mutex.withLock {
            check(isCurrentSession(current)) { "Сессия изменилась" }
            database.withTransaction {
                ledgerRevision++; portfolioRevision++
                planningRevision++
                dao.deletePlanningSnapshots(current.user.id)
                invalidateAdvancedAnalytics(current.user.id)
                dao.deleteUserTransactions(current.user.id)
                dao.deleteLedgerSnapshots(current.user.id)
                dao.deletePortfolios(current.user.id)
            }
            // Never upsert the saved replay result: the server's current version may already be newer.
            ledgerRecovery?.clear(request.userId, request.key)
            if (_ledgerPending.value?.key == request.key) _ledgerPending.value = null
        } }
        return result
    }

    private suspend fun clearLedgerPending(request: PendingLedgerMutation) = withContext(NonCancellable + Dispatchers.IO) {
        mutex.withLock {
            ledgerRecovery?.clear(request.userId, request.key)
            if (_ledgerPending.value?.userId == request.userId && _ledgerPending.value?.key == request.key) _ledgerPending.value = null
        }
    }

    private fun validateLedgerAcknowledgement(request: PendingLedgerMutation, response: JsonObject) {
        val gson = Gson(); val body = request.json()
        if (request.operation in PendingLedgerMutation.PLANNING_OPERATIONS) {
            validatePlanningAcknowledgement(request, response)
            return
        }
        if (request.operation == "mergecategory") {
            gson.fromJson(response, CategoriesResponse::class.java).toDomain()
            return
        }
        val id: String; val version: Long; val deleted: Boolean
        when {
            request.operation.endsWith("account") -> gson.fromJson(response, AccountDto::class.java).toDomain().let {
                id = it.id; version = it.version; deleted = false
                require(it.name == body["name"].asString && it.openingBalanceMinor.toString() == body["opening_balance_minor"].asString)
                if (request.operation == "createaccount") require(it.currency == body["currency"].asString)
            }
            request.operation.endsWith("category") -> gson.fromJson(response, CategoryDto::class.java).toDomain().let {
                id = it.id; version = it.version; deleted = false
                require(it.name == body["name"].asString)
            }
            request.operation.endsWith("transaction") -> gson.fromJson(response, TransactionDto::class.java).toDomain().let {
                id = it.id; version = it.version; deleted = it.deletedAt != null
                if (request.operation == "edittransaction") {
                    require(it.accountId == body["account_id"].asString && it.kind == body["kind"].asString &&
                        it.amountMinor.toString() == body["amount_minor"].asString && it.note == body["note"].asString)
                }
            }
            request.operation in setOf("createtransfer", "edittransfer", "deletetransfer", "restoretransfer") -> gson.fromJson(response, TransferDto::class.java).toDomain().let {
                id = it.id; version = it.version; deleted = it.deletedAt != null
                if (request.operation in setOf("createtransfer", "edittransfer")) require(
                    it.fromAccountId == body["from_account_id"].asString && it.toAccountId == body["to_account_id"].asString &&
                        it.fromAmountMinor.toString() == body["from_amount_minor"].asString && it.toAmountMinor.toString() == body["to_amount_minor"].asString &&
                        it.feeAmountMinor.toString() == body["fee_amount_minor"].asString && it.note == body["note"].asString)
            }
            else -> throw IllegalArgumentException("Неизвестный ответ подтверждения")
        }
        require(request.entityId == null || id == request.entityId)
        if (body.has("expected_version")) require(version == Math.addExact(body["expected_version"].asLong, 1))
        require(deleted == request.operation.startsWith("delete"))
    }

    private fun requireUserId(): String = _session.value?.user?.id
        ?: throw IllegalStateException("Войдите в аккаунт")

    private fun requireSession(): Session = _session.value ?: throw IllegalStateException("Войдите в аккаунт")
    private fun isCurrentSession(session: Session): Boolean =
        _session.value?.token == session.token && _session.value?.user?.id == session.user.id
}
