package com.moneyflow.app.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.moneyflow.app.data.MoneyFlowRepository
import com.moneyflow.app.data.Session
import com.moneyflow.app.data.domain.*
import com.moneyflow.app.data.remote.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import retrofit2.HttpException
import java.io.IOException
import java.time.Instant

data class LedgerUiState(
    val session: Session? = null,
    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
    val transactions: List<MoneyTransaction> = emptyList(),
    val nextCursor: String? = null,
    val filter: LedgerFilter = LedgerFilter(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val busy: Boolean = false,
    val offline: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val pending: PendingLedgerMutation? = null,
    val manualPending: Boolean = false,
    val recoveryUnavailable: Boolean = false,
    val recoveryChecking: Boolean = false,
    val externalBlocked: Boolean = false,
    /** Changes only when a mutation has been acknowledged, never when a page loads. */
    val revision: Long = 0,
) {
    val writesBlocked: Boolean get() = busy || externalBlocked || pending != null || manualPending || recoveryUnavailable || recoveryChecking
    val canWrite: Boolean get() = !writesBlocked && session != null
}

class LedgerViewModel(private val repository: MoneyFlowRepository) : ViewModel() {
    private val _state = MutableStateFlow(LedgerUiState())
    val state: StateFlow<LedgerUiState> = _state.asStateFlow()
    private var queryJob: Job? = null
    private var metadataJob: Job? = null
    private var actionJob: Job? = null
    private var queryGeneration = 0L
    private var mutationCallback: ((String) -> Unit)? = null
    private var externalBlocked = false

    fun onMutation(callback: (String) -> Unit) { mutationCallback = callback }
    fun setExternalBlocked(blocked: Boolean) { externalBlocked = blocked; _state.update { it.copy(externalBlocked = blocked) } }
    fun clearMessage() { _state.update { it.copy(error = null, notice = null) } }

    init {
        viewModelScope.launch {
            repository.session.collectLatest { session ->
                queryGeneration++; queryJob?.cancel(); metadataJob?.cancel(); actionJob?.cancel()
                _state.value = LedgerUiState(session = session, externalBlocked = externalBlocked, recoveryChecking = session != null)
                if (session != null) coroutineScope {
                    launch { repository.observeAccounts(session.user.id).collect { rows -> if (current(session)) _state.update { it.copy(accounts = rows) } } }
                    launch { repository.observeCategories(session.user.id).collect { rows -> if (current(session)) _state.update { it.copy(categories = rows) } } }
                    launch { repository.ledgerPending.collect { pending -> if (current(session)) _state.update { it.copy(pending = pending) } } }
                    launch { repository.ledgerRecoveryUnavailable.collect { unavailable -> if (current(session)) _state.update { it.copy(recoveryUnavailable = unavailable, recoveryChecking = false) } } }
                    launch { repository.pending.collect { pending -> if (current(session)) _state.update { it.copy(manualPending = pending != null) } } }
                    refresh()
                }
            }
        }
    }

    fun setFilter(filter: LedgerFilter) {
        try { filter.validate() } catch (error: Exception) { _state.update { it.copy(error = error.message) }; return }
        if (filter == state.value.filter) return
        queryGeneration++; queryJob?.cancel()
        _state.update { it.copy(filter = filter, transactions = emptyList(), nextCursor = null, offline = false, loadingMore = false) }
        loadPage(false)
    }

    /** Explicit refresh includes current account/category names, balances and optimistic versions. */
    fun refresh() {
        val session = repository.session.value ?: return
        if (!current(session)) return
        metadataJob?.cancel()
        metadataJob = viewModelScope.launch {
            if (state.value.recoveryUnavailable) runCatching { repository.recheckLedgerRecovery() }
            supervisorScope {
                val accounts = async { if (current(session)) runCatching { repository.refreshAccounts() } else Result.success(Unit) }
                val categories = async { if (current(session)) runCatching { repository.refreshCategories() } else Result.success(Unit) }
                val failures = listOf(accounts.await(), categories.await()).mapNotNull { it.exceptionOrNull() }
                val unauthorized = failures.filterIsInstance<HttpException>().firstOrNull { it.code() == 401 }
                if (unauthorized != null && expireIfUnauthorized(unauthorized, session)) return@supervisorScope
                if (current(session)) failures.firstOrNull { it !is CancellationException }?.let { error ->
                    _state.update { it.copy(error = "Не удалось обновить счета или категории. Показаны сохранённые данные. " +
                        message(error as? Exception ?: IllegalStateException("Сбой обновления"))) }
                }
            }
        }
        loadPage(false)
    }
    fun loadMore() = loadPage(true)

    private fun loadPage(more: Boolean) {
        val session = repository.session.value ?: return
        if (!current(session) || (more && (state.value.nextCursor == null || state.value.loading || state.value.loadingMore || state.value.offline))) return
        val filter = state.value.filter; val cursor = if (more) state.value.nextCursor else null
        queryGeneration++; val generation = queryGeneration; queryJob?.cancel()
        _state.update { it.copy(loading = !more, loadingMore = more, error = null) }
        queryJob = viewModelScope.launch {
            try {
                val page = repository.history(filter, cursor)
                if (!current(session) || generation != queryGeneration) return@launch
                val rows = page.transactions.map(TransactionDto::toDomain)
                _state.update { old -> old.copy(transactions = if (more) (old.transactions + rows).distinctBy { it.id } else rows,
                    nextCursor = page.nextCursor, offline = false, loading = false, loadingMore = false) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (!current(session) || generation != queryGeneration) return@launch
                if (expireIfUnauthorized(error, session)) return@launch
                if (!more) {
                    val cached = runCatching { repository.cachedHistory(filter) }.getOrNull()
                    if (!current(session) || generation != queryGeneration) return@launch
                    _state.update { it.copy(transactions = cached ?: it.transactions, nextCursor = null, offline = true,
                        loading = false, loadingMore = false, error = message(error),
                        notice = "Показаны только ранее загруженные операции. Полная история доступна после подключения.") }
                } else _state.update { it.copy(loadingMore = false, error = message(error)) }
            }
        }
    }

    fun retryPending() {
        val s = state.value
        if (s.busy || s.externalBlocked || s.recoveryUnavailable || s.recoveryChecking) return
        runMutation(retry = true) {
            if (s.pending != null) repository.retryLedgerMutation()
            else if (s.manualPending) repository.retryPending()
            else throw IllegalStateException("Нет ожидающего изменения")
        }
    }

    fun createTransaction(accountId: String, kind: String, amountMinor: Long, note: String, occurredAt: String,
        categoryId: String?, excludeFromAnalytics: Boolean = false) = runMutation {
        require(!excludeFromAnalytics) { "Сначала создайте обычную операцию; исключение доступно при редактировании" }
        val request = CreateTransactionRequest(accountId, kind, amountMinor.toString(), note.trim(), occurredAt, categoryId)
        request.validate(); activeAccount(accountId); activeCategory(categoryId, kind)
        repository.createTransaction(request)
    }

    fun updateTransaction(original: MoneyTransaction, accountId: String, kind: String, amountMinor: Long,
        note: String, occurredAt: String, categoryId: String?, excludeFromAnalytics: Boolean = false) = runMutation {
        require(original.transferId == null && original.deletedAt == null) { "Измените перевод целиком" }
        require(original.source != "import" || accountId == original.accountId) { "У импортированной операции нельзя менять счёт" }
        CreateTransactionRequest(accountId, kind, amountMinor.toString(), note.trim(), occurredAt, categoryId).validate()
        activeAccount(accountId)
        // An archived historical assignment may remain while other fields change.
        // Choosing another category or changing kind is a new assignment and must be active.
        if (categoryId != original.categoryId || kind != original.kind) activeCategory(categoryId, kind)
        val body = version(original.version).apply {
            addProperty("account_id", accountId); addProperty("kind", kind); addProperty("amount_minor", amountMinor.toString())
            addProperty("note", note.trim()); addProperty("occurred_at", occurredAt); nullable("category_id", categoryId)
            addProperty("exclude_from_analytics", excludeFromAnalytics)
        }
        repository.mutateLedger("edittransaction", original.id, body)
    }
    fun deleteTransaction(original: MoneyTransaction) = runMutation {
        require(original.transferId == null && original.deletedAt == null) { "Удалите перевод целиком" }
        repository.mutateLedger("deletetransaction", original.id, version(original.version))
    }
    fun restoreTransaction(original: MoneyTransaction) = runMutation {
        require(original.transferId == null && original.deletedAt != null) { "Восстановите перевод целиком" }
        repository.mutateLedger("restoretransaction", original.id, version(original.version))
    }

    fun createAccount(name: String, currency: String, openingBalanceMinor: Long, type: String = "card") = runMutation {
        val normalized = normalizeCategoryName(name)
        require(normalized.codePointCount(0, normalized.length) in 1..100); require(type in ACCOUNT_TYPES)
        SupportedCurrency.fromCode(currency); bounded(openingBalanceMinor)
        repository.mutateLedger("createaccount", null, JsonObject().apply {
            addProperty("name", normalized); addProperty("currency", currency); addProperty("opening_balance_minor", openingBalanceMinor.toString()); addProperty("type", type)
        })
    }
    fun saveAccount(original: Account, name: String, type: String, openingBalanceMinor: Long,
        archived: Boolean, includeInBalance: Boolean, sortOrder: Int) = runMutation {
        val normalized = normalizeCategoryName(name)
        require(normalized.codePointCount(0, normalized.length) in 1..100); require(type in ACCOUNT_TYPES); bounded(openingBalanceMinor)
        repository.mutateLedger("editaccount", original.id, version(original.version).apply {
            addProperty("name", normalized); addProperty("type", type); addProperty("opening_balance_minor", openingBalanceMinor.toString())
            addProperty("archived", archived); addProperty("include_in_balance", includeInBalance); addProperty("sort_order", sortOrder)
        })
    }

    fun createCategory(kind: String, name: String, parentId: String? = null) = runMutation {
        val request = CreateCategoryRequest(kind, normalizeCategoryName(name), parentId); request.validate()
        parentId?.let { id -> require(state.value.categories.any { it.id == id && it.kind == "expense" && it.parentId == null && !it.archived }) }
        repository.mutateLedger("createcategory", null, JsonObject().apply {
            addProperty("kind", kind); addProperty("name", request.name); nullable("parent_id", parentId)
        })
    }
    fun saveCategory(original: Category, name: String, archived: Boolean, sortOrder: Int) = runMutation {
        val normalized = normalizeCategoryName(name); validateCategoryName(normalized)
        repository.mutateLedger("editcategory", original.id, version(original.version).apply {
            addProperty("name", normalized); addProperty("archived", archived); addProperty("sort_order", sortOrder)
        })
    }
    fun mergeCategory(source: Category, target: Category) = runMutation {
        require(source.id != target.id && source.kind == target.kind && !target.archived)
        repository.mutateLedger("mergecategory", source.id, version(source.version).apply { addProperty("target_category_id", target.id) })
    }

    fun fetchTransfer(id: String, onLoaded: (MoneyTransfer) -> Unit) {
        if (state.value.busy) return
        val session = repository.session.value ?: return
        _state.update { it.copy(busy = true, error = null) }
        actionJob = viewModelScope.launch {
            try { val transfer = repository.fetchTransfer(id); if (current(session)) onLoaded(transfer) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (current(session) && !expireIfUnauthorized(error, session)) _state.update { it.copy(error = message(error)) }
            }
            finally { if (current(session)) _state.update { it.copy(busy = false) } }
        }
    }
    fun saveTransfer(original: MoneyTransfer? = null, fromAccountId: String, toAccountId: String,
        fromAmountMinor: Long, toAmountMinor: Long, feeAmountMinor: Long = 0,
        feeAccountId: String? = null, note: String, occurredAt: String) = runMutation {
        require(fromAccountId != toAccountId) { "Выберите разные счета" }
        val from = activeAccount(fromAccountId); val to = activeAccount(toAccountId)
        bounded(fromAmountMinor); bounded(toAmountMinor); bounded(feeAmountMinor)
        require(fromAmountMinor > 0 && toAmountMinor > 0 && feeAmountMinor >= 0) { "Проверьте суммы перевода" }
        require(from.currency != to.currency || fromAmountMinor == toAmountMinor) { "В одной валюте суммы списания и зачисления должны совпадать" }
        if (feeAmountMinor > 0) activeAccount(requireNotNull(feeAccountId) { "Выберите счёт комиссии" })
        Instant.parse(occurredAt); require(note.codePointCount(0, note.length) <= 500)
        val body = (original?.let { version(it.version) } ?: JsonObject()).apply {
            addProperty("from_account_id", fromAccountId); addProperty("to_account_id", toAccountId)
            addProperty("from_amount_minor", fromAmountMinor.toString()); addProperty("to_amount_minor", toAmountMinor.toString())
            addProperty("fee_amount_minor", feeAmountMinor.toString()); nullable("fee_account_id", feeAccountId.takeIf { feeAmountMinor > 0 })
            addProperty("note", note.trim()); addProperty("occurred_at", occurredAt)
        }
        repository.mutateLedger(if (original == null) "createtransfer" else "edittransfer", original?.id, body)
    }
    fun deleteTransfer(original: MoneyTransfer) = runMutation {
        require(original.deletedAt == null)
        repository.mutateLedger("deletetransfer", original.id, version(original.version))
    }
    fun restoreTransfer(original: MoneyTransfer) = runMutation {
        require(original.deletedAt != null)
        repository.mutateLedger("restoretransfer", original.id, version(original.version))
    }

    private fun runMutation(retry: Boolean = false, action: suspend () -> Any) {
        if (!retry && !state.value.canWrite) return
        val session = repository.session.value ?: return
        _state.update { it.copy(busy = true, error = null, notice = null) }
        actionJob = viewModelScope.launch {
            try {
                action()
                if (!current(session)) return@launch
                // Pull current versions; idempotent replay responses may describe an older state.
                supervisorScope {
                    launch { runCatching { repository.refreshAccounts() } }
                    launch { runCatching { repository.refreshCategories() } }
                }
                if (!current(session)) return@launch
                _state.update { it.copy(revision = it.revision + 1, notice = "Изменения сохранены") }
                mutationCallback?.invoke("")
                loadPage(false)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (current(session)) {
                    if (expireIfUnauthorized(error, session)) return@launch
                    _state.update { it.copy(error = message(error)) }
                    if (error is HttpException && error.code() == 409) {
                        launch { runCatching { repository.refreshAccounts() } }
                        launch { runCatching { repository.refreshCategories() } }
                        loadPage(false)
                    }
                }
            } finally { if (current(session)) _state.update { it.copy(busy = false) } }
        }
    }
    private suspend fun expireIfUnauthorized(error: Exception, session: Session): Boolean {
        if (error !is HttpException || error.code() != 401) return false
        // The repository repeats the expected-session comparison under its mutex:
        // a delayed 401 must never sign out a replacement session.
        if (current(session)) repository.expireSession(session)
        return true
    }
    private fun activeAccount(id: String): Account = requireNotNull(state.value.accounts.firstOrNull { it.id == id && !it.archived }) { "Выберите действующий счёт" }
    private fun activeCategory(id: String?, kind: String) { id?.let { require(state.value.categories.any { c -> c.id == it && c.kind == kind && !c.archived }) { "Выберите действующую категорию" } } }
    private fun bounded(amount: Long) { require(amount in -Money.MAX_INPUT_MINOR..Money.MAX_INPUT_MINOR) { "Сумма превышает допустимый размер" } }
    private fun current(session: Session): Boolean = repository.session.value?.let { it.token == session.token && it.user.id == session.user.id } == true && state.value.session?.token == session.token
    private fun version(value: Long) = JsonObject().apply { require(value >= 1); addProperty("expected_version", value) }
    private fun JsonObject.nullable(key: String, value: String?) { if (value == null) add(key, JsonNull.INSTANCE) else addProperty(key, value) }
    private fun message(error: Exception): String = when (error) {
        is IOException -> "Нет связи с сервером. Если подтверждение сохранено, повторите его с тем же запросом."
        is HttpException -> when (error.code()) {
            401 -> "Сессия истекла. Войдите снова, чтобы продолжить сохранённое подтверждение."
            409 -> "Данные изменились. Журнал обновлён; откройте операцию заново. Если подтверждение ожидает ответа, нажмите «Повторить»."
            404 -> "Запись больше недоступна. Обновите журнал."
            else -> "Сервер отклонил изменение (${error.code()}). Проверьте данные и повторите."
        }
        else -> error.message ?: "Не удалось выполнить действие"
    }
    class Factory(@Suppress("UNUSED_PARAMETER") context: Context, private val repository: MoneyFlowRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = LedgerViewModel(repository) as T
    }
}
