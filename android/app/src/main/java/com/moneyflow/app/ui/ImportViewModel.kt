package com.moneyflow.app.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.moneyflow.app.data.EncryptedImportRecoveryStore
import com.moneyflow.app.data.ImportRecoveryStorage
import com.moneyflow.app.data.ImportRecoveryUnavailable
import com.moneyflow.app.data.MoneyFlowRepository
import com.moneyflow.app.data.Session
import com.moneyflow.app.data.domain.Account
import com.moneyflow.app.data.domain.Category
import com.moneyflow.app.data.domain.Money
import com.moneyflow.app.data.domain.SupportedCurrency
import com.moneyflow.app.data.imports.ParsedPdfStatement
import com.moneyflow.app.data.imports.StatementPdfReader
import com.moneyflow.app.data.imports.StatementImportException
import com.moneyflow.app.data.remote.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import retrofit2.HttpException
import java.io.IOException
import java.math.BigInteger
import java.time.Instant
import java.time.ZoneId

data class ImportUiState(
    val session: Session? = null,
    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
    val accountId: String? = null,
    val timezone: String = "Asia/Qyzylorda",
    val parsed: ParsedPdfStatement? = null,
    val draft: ImportPreviewDto? = null,
    val choices: Map<Int, ImportRowChoice> = emptyMap(),
    val pending: PendingImportAction? = null,
    val history: List<ImportReceiptDto> = emptyList(),
    val busy: Boolean = false,
    val parsing: Boolean = false,
    val historyLoading: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val receipt: ImportReceiptDto? = null,
    val balanceDifferenceAccepted: Boolean = false,
    val accountCreationUncertain: Boolean = false,
    val recoveryUnavailable: Boolean = false,
    val recoveryChecking: Boolean = false,
    val historyVerified: Boolean = false,
) {
    val account: Account? get() = accounts.firstOrNull { it.id == accountId }
    val selectedCount: Int get() = choices.values.count { it.selected }
    val projectedBalance: BigInteger? get() = draft?.let { preview ->
        preview.rows.filter { choices[it.rowId]?.selected == true }.fold(BigInteger(preview.balanceBeforeMinor)) { sum, row ->
            val amount = BigInteger(row.amountMinor)
            if (row.kind == "income") sum + amount else sum - amount
        }
    }
    val differsFromStatement: Boolean get() = parsed?.statement?.closingBalanceMinor?.let { closing ->
        projectedBalance?.let { it != BigInteger.valueOf(closing) }
    } ?: false
    val needsTransferReview: Boolean get() = parsed?.statement?.rows?.any {
        it.possibleTransfer && choices[it.rowId]?.let { c -> c.selected && !c.transferReviewed } == true
    } == true
    val canCommit: Boolean get() = draft != null && pending == null && !busy && !recoveryUnavailable && !recoveryChecking && selectedCount > 0 && !needsTransferReview &&
        (!differsFromStatement || balanceDifferenceAccepted) && projectedBalance?.let {
            it >= BigInteger.valueOf(Long.MIN_VALUE) && it <= BigInteger.valueOf(Long.MAX_VALUE)
        } == true
}

class ImportViewModel(
    private val context: Context,
    private val repository: MoneyFlowRepository,
    private val recovery: ImportRecoveryCoordinator,
) : ViewModel() {
    private val _state = MutableStateFlow(ImportUiState())
    val state = _state.asStateFlow()
    private var actionJob: Job? = null
    private var generation = 0L
    private var mutationCallback: ((String) -> Unit)? = null

    fun onMutation(callback: (String) -> Unit) { mutationCallback = callback }

    init {
        viewModelScope.launch {
            repository.session.collectLatest { session ->
                generation++; actionJob?.cancel()
                _state.value = ImportUiState(session = session, recoveryChecking = session != null)
                val pending = try { session?.let { withContext(Dispatchers.IO) { recovery.pending(it.user.id) } } }
                catch (error: CancellationException) { throw error }
                catch (_: ImportRecoveryUnavailable) {
                    _state.update { it.copy(recoveryUnavailable = true,
                        error = "Сохранённое подтверждение недоступно. Оно не удалено; сначала проверьте историю импортов.") }
                    null
                }
                _state.update { it.copy(pending = pending, accountId = pending?.accountId, recoveryChecking = false) }
                if (session != null) coroutineScope {
                    launch {
                        repository.observeAccounts(session.user.id).collect { accounts ->
                            if (current(session)) _state.update {
                                val suitable = accounts.filter { a -> a.currency == "KZT" }
                                val selected = it.accountId?.takeIf { id -> suitable.any { a -> a.id == id } } ?: suitable.firstOrNull()?.id
                                it.copy(accounts = suitable, accountId = selected)
                            }
                        }
                    }
                    launch { repository.observeCategories(session.user.id).collect { if (current(session)) _state.update { s -> s.copy(categories = it) } } }
                    launch { runCatching { repository.refreshCategories() } }
                    launch { loadHistory(session) }
                }
            }
        }
    }

    fun chooseAccount(id: String) {
        if (state.value.pending != null || state.value.recoveryUnavailable || state.value.recoveryChecking || id == state.value.accountId) return
        generation++; actionJob?.cancel()
        _state.update { it.copy(accountId = id, draft = null, choices = emptyMap(), busy = false, parsing = false,
            error = null, receipt = null, balanceDifferenceAccepted = false) }
    }
    fun setTimezone(value: String) {
        if (state.value.pending != null || state.value.recoveryUnavailable || state.value.recoveryChecking || state.value.busy) return
        _state.update { it.copy(timezone = value, draft = null, choices = emptyMap(), balanceDifferenceAccepted = false) }
    }
    fun choosePdf(uri: Uri) {
        if (state.value.pending != null || state.value.recoveryUnavailable || state.value.recoveryChecking) return
        val session = repository.session.value ?: return
        if (state.value.session?.token != session.token) return
        generation++; actionJob?.cancel(); val version = generation; val accountId = state.value.accountId
        _state.update { it.copy(parsed = null, draft = null, choices = emptyMap(), parsing = true, busy = true, error = null,
            notice = null, receipt = null, balanceDifferenceAccepted = false) }
        actionJob = viewModelScope.launch {
            try {
                val parsed = runInterruptible(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openInputStream(uri)) { "Не удалось открыть PDF" }.use {
                        StatementPdfReader(context).read(it)
                    }
                }
                if (current(session, version)) _state.update {
                    if (it.accountId == accountId) it.copy(parsed = parsed)
                    else it.copy(error = "Счёт изменился во время разбора. Выберите PDF снова.")
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (current(session, version)) _state.update { it.copy(error = if (error is StatementImportException) error.message else
                    "Не удалось разобрать PDF. Нужна текстовая выписка Kaspi или Halyk без пароля; сканы не поддерживаются.") }
            } finally { if (current(session, version)) _state.update { it.copy(parsing = false, busy = false) } }
        }
    }
    fun cancelParsing() {
        if (!state.value.parsing) return
        generation++; actionJob?.cancel()
        _state.update { it.copy(parsing = false, busy = false, parsed = null, error = null) }
    }

    fun createAccount(name: String, opening: String, accepted: Boolean) = action {
        val session = requireNotNull(repository.session.value); val version = generation
        require(state.value.pending == null && accepted) { "Подтвердите начальный остаток" }
        require(!state.value.recoveryUnavailable && !state.value.recoveryChecking) { "Сначала проверьте сохранённое подтверждение" }
        require(!state.value.accountCreationUncertain) { "Сначала обновите и проверьте список счетов" }
        val amount = Money.parseMinor(opening, SupportedCurrency.KZT)
        val clean = name.trim(); require(clean.codePointCount(0, clean.length) in 1..100) { "Название: от 1 до 100 символов" }
        val account = try { repository.createAccount(CreateAccountRequest(clean, "KZT", amount.toString()), session) }
        catch (error: Exception) {
            if (current(session, version) && (error is IOException || (error is HttpException && error.code() >= 500))) {
                _state.update { it.copy(accountCreationUncertain = true) }
                runCatching { repository.refreshAccounts() }
            }
            throw error
        }
        if (!current(session, version)) return@action
        _state.update { it.copy(accountId = account.id, draft = null, choices = emptyMap(), notice = "Счёт создан. Проверьте выписку перед импортом.") }
        repository.refreshAccounts()
        mutationCallback?.invoke(account.id)
    }
    fun refreshAccountInventory() = action { repository.refreshAccounts() }
    fun acknowledgeAccountInventory() { if (!state.value.busy) _state.update { it.copy(accountCreationUncertain = false, error = null) } }

    fun preview() = action {
        val session = requireNotNull(repository.session.value); val version = generation
        val state = state.value
        require(state.pending == null) { "Сначала завершите ожидающее подтверждение" }
        require(!state.recoveryUnavailable && !state.recoveryChecking) { "Сначала проверьте сохранённое подтверждение" }
        val parsed = requireNotNull(state.parsed) { "Выберите PDF" }
        require(parsed.statement.errors.isEmpty()) { "Ошибки разбора блокируют импорт" }
        val account = requireNotNull(state.account) { "Выберите счёт в KZT" }
        val zone = ZoneId.of(state.timezone)
        val request = ImportPreviewRequest(account.id, parsed.statement.bank, "KZT", parsed.fileSha256, zone.id,
            parsed.statement.openingBalanceMinor?.toString(), parsed.statement.closingBalanceMinor?.toString(),
            parsed.statement.rows.map { row -> ImportSourceRow(row.rowId, row.date.atStartOfDay(zone).toInstant().toString(),
                row.kind, row.amountMinor.toString(), normalizeBankNote(row.note)) })
        val preview = repository.previewImport(request, session)
        if (!current(session, version) || state.accountId != this.state.value.accountId) return@action
        _state.update { it.copy(draft = preview, choices = preview.rows.associate { row ->
            row.rowId to ImportRowChoice(selected = row.duplicate == "new")
        }, receipt = null, balanceDifferenceAccepted = false) }
    }

    fun selectRow(id: Int, selected: Boolean) = updateChoice(id) { it.copy(selected = selected,
        allowPossibleDuplicate = if (selected) it.allowPossibleDuplicate else false) }
    fun allowDuplicate(id: Int, allowed: Boolean) = updateChoice(id) { it.copy(allowPossibleDuplicate = allowed, selected = allowed) }
    fun movement(id: Int, excluded: Boolean) = updateChoice(id) { it.copy(excludeFromAnalytics = excluded, transferReviewed = true) }
    fun category(id: Int, categoryId: String?) {
        val row = state.value.draft?.rows?.firstOrNull { it.rowId == id } ?: return
        if (categoryId != null && state.value.categories.none { it.id == categoryId && it.kind == row.kind }) return
        updateChoice(id) { it.copy(categoryId = categoryId) }
    }
    fun acceptBalanceDifference(accepted: Boolean) { if (state.value.pending == null) _state.update { it.copy(balanceDifferenceAccepted = accepted) } }

    private fun updateChoice(id: Int, edit: (ImportRowChoice) -> ImportRowChoice) {
        if (state.value.pending != null || state.value.recoveryUnavailable || state.value.recoveryChecking || state.value.busy) return
        val row = state.value.draft?.rows?.firstOrNull { it.rowId == id } ?: return
        if (row.duplicate == "exact") return
        _state.update { state ->
            val previous = state.choices[id] ?: return@update state
            state.copy(choices = state.choices + (id to edit(previous)), balanceDifferenceAccepted = false)
        }
    }

    fun commit() = action {
        val state = state.value; require(state.copy(busy = false).canCommit) { "Проверьте выбранные записи, переводы и расхождение остатков" }
        val session = requireNotNull(repository.session.value)
        require(Instant.parse(requireNotNull(state.draft).expiresAt).isAfter(Instant.now())) { "Проверка устарела. Запросите новый предпросмотр" }
        val pending = withContext(Dispatchers.IO) { recovery.prepareCommit(session.user.id, state.draft, state.choices) }
        _state.update { it.copy(pending = pending) }
        execute(pending)
    }
    fun retry() = action { execute(requireNotNull(state.value.pending)) }
    fun undo(receipt: ImportReceiptDto) = action {
        require(!state.value.recoveryUnavailable && !state.value.recoveryChecking)
        val session = requireNotNull(repository.session.value)
        val pending = withContext(Dispatchers.IO) { recovery.prepareUndo(session.user.id, receipt) }
        _state.update { it.copy(pending = pending) }
        execute(pending)
    }

    private suspend fun execute(pending: PendingImportAction) {
        val session = requireNotNull(repository.session.value); val version = generation
        val receipt = repository.executeImport(pending)
        withContext(Dispatchers.IO) { recovery.resolved(pending) }
        if (!current(session, version)) return
        _state.update { it.copy(pending = null, draft = null, parsed = null, choices = emptyMap(), receipt = receipt,
            notice = if (receipt.status == "undone") "Импорт отменён. Удалены только созданные им операции." else "Выбранные операции сохранены.") }
        mutationCallback?.invoke(receipt.accountId)
        loadHistory(requireNotNull(repository.session.value))
    }
    fun refreshHistory() { repository.session.value?.let { session -> viewModelScope.launch { loadHistory(session) } } }
    fun retryRecoveryRead() = action {
        val session = requireNotNull(repository.session.value)
        require(state.value.recoveryUnavailable)
        val pending = withContext(Dispatchers.IO) { requireNotNull(recovery.pending(session.user.id)) {
            "Подтверждение не найдено. Сначала проверьте историю, затем подтвердите сброс."
        } }
        if (current(session)) _state.update { it.copy(pending = pending, recoveryUnavailable = false, error = null,
            notice = "Сохранённый ключ восстановлен. Можно повторить исходное подтверждение.") }
    }
    fun forgetUnavailableRecovery(reviewed: Boolean) = action {
        val session = requireNotNull(repository.session.value)
        require(state.value.recoveryUnavailable && state.value.historyVerified && reviewed) { "Сначала загрузите и проверьте историю" }
        withContext(Dispatchers.IO) { recovery.forgetAfterHistoryReview(session.user.id, true) }
        if (current(session)) _state.update { it.copy(recoveryUnavailable = false, pending = null, parsed = null, draft = null,
            choices = emptyMap(), notice = "Локальное подтверждение удалено после вашей проверки. Серверные операции не изменены.") }
    }
    private suspend fun loadHistory(session: Session) {
        if (!current(session)) return
        _state.update { it.copy(historyLoading = true, historyVerified = false) }
        try {
            val history = repository.importsHistory()
            if (current(session)) _state.update { it.copy(history = history, historyVerified = true) }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { if (current(session)) _state.update { it.copy(error = "Не удалось обновить историю импортов") } }
        finally { if (current(session)) _state.update { it.copy(historyLoading = false) } }
    }

    private fun action(block: suspend () -> Unit) {
        if (state.value.busy) return
        val session = repository.session.value ?: return
        if (state.value.session?.token != session.token) return
        val version = generation
        actionJob = viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, notice = null) }
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (current(session, version)) handleError(error, session) }
            finally { if (current(session, version)) _state.update { it.copy(busy = false) } }
        }
    }
    private suspend fun handleError(error: Exception, session: Session) {
        if (error is ImportRecoveryUnavailable) {
            _state.update { it.copy(recoveryUnavailable = true, historyVerified = false, error =
                "Сохранённое подтверждение недоступно. Оно не удалено; новые импорты заблокированы. Обновите и проверьте историю.") }
            return
        }
        if (error is HttpException && error.code() == 401) { repository.expireSession(); return }
        val code = if (error is HttpException) runCatching {
            Gson().fromJson(error.response()?.errorBody()?.source()?.peek()?.readUtf8(), ErrorBody::class.java)?.error?.code
        }.getOrNull() else null
        val pending = state.value.pending
        if (pending != null && pending.userId == session.user.id && error is HttpException &&
            authoritativeUndoneImport(error.code(), code)) {
            withContext(Dispatchers.IO) { recovery.resolved(pending) }
            if (!current(session)) return
            _state.update { it.copy(pending = null, draft = null, parsed = null, choices = emptyMap(), error = null,
                notice = "Импорт уже отменён на сервере. Исходное подтверждение не создаст операции заново.") }
            mutationCallback?.invoke(pending.accountId)
            loadHistory(session)
            return
        }
        if (pending != null && code in setOf("import_already_committed", "import_undone", "idempotency_conflict")) {
            val observed = runCatching { repository.importsHistory() }.getOrNull()?.firstOrNull {
                it.id == pending.draftId && it.accountId == pending.accountId &&
                    (it.status == "undone" || (pending.operation == "commit" && it.status == "committed"))
            }
            if (observed != null && current(session)) {
                withContext(Dispatchers.IO) { recovery.resolved(pending) }
                _state.update { it.copy(pending = null, draft = null, parsed = null, choices = emptyMap(), receipt = observed,
                    notice = "Состояние импорта уже изменено на сервере. Проверьте историю; исходный запрос не создаст записи повторно.") }
                mutationCallback?.invoke(observed.accountId)
                loadHistory(session)
                return
            }
        }
        if (pending != null && error is HttpException && definitiveImportFailure(error.code(), code)) {
            withContext(Dispatchers.IO) { recovery.resolved(pending) }
            _state.update { it.copy(pending = null, draft = null, choices = emptyMap()) }
        }
        _state.update { it.copy(error = when {
            pending != null && (error is IOException || error !is HttpException || error.code() >= 500) ->
                "Ответ не подтверждён. Операции могли сохраниться. Повторите подтверждение с тем же ключом; новую проверку пока не создавайте."
            code == "import_preview_changed" -> "После проверки появились совпадающие записи. Запросите новый предпросмотр."
            code == "import_duplicate_exact" || code == "import_duplicate_possible" -> "Выбранные повторы требуют новой проверки. Ничего не сохранено этим запросом."
            code == "import_expired" -> "Проверка истекла. Создайте новый предпросмотр."
            code == "balance_overflow" -> "Импорт превышает допустимый баланс. Выберите меньше записей."
            pending != null -> "Подтверждение не завершено. Проверьте историю и повторите исходный запрос."
            state.value.accountCreationUncertain -> "Счёт мог создаться. Обновите и проверьте список счетов перед повтором; можно выбрать уже появившийся счёт."
            error is IllegalArgumentException -> error.message ?: "Проверьте параметры импорта"
            else -> "Не удалось выполнить действие. Проверьте соединение и повторите попытку."
        }) }
    }
    private fun current(session: Session, version: Long? = null): Boolean = repository.session.value?.token == session.token &&
        repository.session.value?.user?.id == session.user.id && (version == null || version == generation)

    companion object {
        fun factory(context: Context, repository: MoneyFlowRepository): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(ImportViewModel::class.java))
                return ImportViewModel(context.applicationContext, repository,
                    ImportRecoveryCoordinator(EncryptedImportRecoveryStore(context.applicationContext))) as T
            }
        }
    }
}

internal fun normalizeBankNote(note: String): String = note.trim {
    (it.isWhitespace() && it !in '\u001c'..'\u001f') || it == '\u0085'
}
