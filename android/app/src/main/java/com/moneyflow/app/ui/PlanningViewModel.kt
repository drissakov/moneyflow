package com.moneyflow.app.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.JsonArray
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

data class PlanningUiState(
    val session: Session? = null,
    val month: String = PlanningCalendar.month(),
    val forecastUntil: String? = null,
    val report: PlanningResponseDto? = null,
    val forecast: ForecastResponseDto? = null,
    val receivedAt: Instant? = null,
    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
    val loading: Boolean = false,
    val busy: Boolean = false,
    val offline: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val pending: PendingLedgerMutation? = null,
    val manualPending: Boolean = false,
    val recoveryUnavailable: Boolean = false,
    val recoveryChecking: Boolean = false,
    val externalBlocked: Boolean = false,
    /** Only an acknowledged write increments this value, never a report refresh. */
    val revision: Long = 0,
) {
    val canWrite: Boolean get() = session != null && report != null && !loading && !busy && !offline &&
        !externalBlocked && pending == null && !manualPending && !recoveryUnavailable && !recoveryChecking &&
        report.today == PlanningCalendar.today().toString()
}

class PlanningViewModel(private val repository: MoneyFlowRepository) : ViewModel() {
    private val _state = MutableStateFlow(PlanningUiState())
    val state: StateFlow<PlanningUiState> = _state.asStateFlow()
    private var reportJob: Job? = null
    private var metadataJob: Job? = null
    private var actionJob: Job? = null
    private var generation = 0L
    private var externalBlocked = false
    private var mutationCallback: ((String) -> Unit)? = null

    fun onMutation(callback: (String) -> Unit) { mutationCallback = callback }
    fun setExternalBlocked(blocked: Boolean) { externalBlocked = blocked; _state.update { it.copy(externalBlocked = blocked) } }
    fun clearMessage() { _state.update { it.copy(error = null, notice = null) } }

    init {
        viewModelScope.launch {
            repository.session.collectLatest { session ->
                generation++; reportJob?.cancel(); metadataJob?.cancel(); actionJob?.cancel()
                _state.value = PlanningUiState(session = session, externalBlocked = externalBlocked, recoveryChecking = session != null)
                if (session != null) coroutineScope {
                    launch { repository.observeAccounts(session.user.id).collect { rows -> if (current(session)) _state.update { it.copy(accounts = rows) } } }
                    launch { repository.observeCategories(session.user.id).collect { rows -> if (current(session)) _state.update { it.copy(categories = rows) } } }
                    launch { repository.ledgerPending.collect { pending -> if (current(session)) _state.update { it.copy(pending = pending) } } }
                    launch { repository.pending.collect { pending -> if (current(session)) _state.update { it.copy(manualPending = pending != null) } } }
                    launch { repository.ledgerRecoveryUnavailable.collect { unavailable -> if (current(session)) _state.update { it.copy(recoveryUnavailable = unavailable, recoveryChecking = false) } } }
                    refresh()
                }
            }
        }
    }

    fun setMonth(month: String) {
        try { PlanningCalendar.validateMonth(month) } catch (error: Exception) { _state.update { it.copy(error = message(error)) }; return }
        if (month == state.value.month) return
        _state.update { it.copy(month = month, report = null, forecast = null, receivedAt = null, offline = false) }
        loadReport()
    }
    fun setForecastUntil(until: String?) {
        try {
            until?.let { require(PlanningCalendar.validateDate(it) in PlanningCalendar.today()..PlanningCalendar.today().plusDays(365)) { "Выберите дату в ближайшие 366 дней" } }
        } catch (error: Exception) { _state.update { it.copy(error = message(error)) }; return }
        if (until == state.value.forecastUntil) return
        _state.update { it.copy(forecastUntil = until, forecast = null, receivedAt = null, offline = false) }
        loadReport()
    }

    fun refresh() {
        val session = repository.session.value ?: return
        if (!current(session)) return
        metadataJob?.cancel()
        metadataJob = viewModelScope.launch {
            if (state.value.recoveryUnavailable) runCatching { repository.recheckLedgerRecovery() }
            val failure = refreshMetadata(session)
            if (current(session) && failure != null && !expireIfUnauthorized(failure, session)) {
                _state.update { it.copy(error = "Не удалось обновить счета или категории. " + message(failure)) }
            }
        }
        loadReport()
    }

    private fun loadReport(preserveError: Boolean = false) {
        val session = repository.session.value ?: return
        if (!current(session)) return
        val month = state.value.month; val until = state.value.forecastUntil
        generation++; val query = generation; reportJob?.cancel()
        _state.update { it.copy(loading = true, error = if (preserveError) it.error else null) }
        reportJob = viewModelScope.launch {
            try {
                val loaded = repository.planning(month, until)
                if (!current(session) || query != generation) return@launch
                _state.update { it.copy(report = loaded.report, forecast = loaded.forecast, receivedAt = loaded.receivedAt,
                    loading = false, offline = false) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (!current(session) || query != generation || expireIfUnauthorized(error, session)) return@launch
                val cached = runCatching { repository.cachedPlanning(month, until) }.getOrNull()
                if (!current(session) || query != generation) return@launch
                _state.update { it.copy(report = cached?.report ?: it.report, forecast = cached?.forecast ?: it.forecast,
                    receivedAt = cached?.receivedAt ?: it.receivedAt, loading = false, offline = true,
                    error = if (preserveError && it.error != null) it.error else message(error),
                    notice = "Сохранённый расчёт доступен только для просмотра. Обновите данные при подключении к серверу.") }
            }
        }
    }

    fun saveBudget(original: BudgetDto?, name: String, categoryId: String, currency: String, limitMinor: Long,
        periodStartDay: Int, archived: Boolean = false) = runMutation {
        val normalized = normalizeCategoryName(name); validateCategoryName(normalized); SupportedCurrency.fromCode(currency)
        positive(limitMinor); require(periodStartDay in 1..31) { "Начало периода: день от 1 до 31" }
        if (!archived) activeCategory(categoryId, "expense") else validateUuid(categoryId)
        repository.mutateLedger(if (original == null) "createbudget" else "editbudget", original?.id,
            version(original?.version).apply {
                addProperty("name", normalized); addProperty("category_id", categoryId); addProperty("currency", currency)
                addProperty("limit_minor", limitMinor.toString()); addProperty("period_start_day", periodStartDay); addProperty("archived", archived)
            })
    }

    fun saveSchedule(original: ScheduleDto?, name: String, accountId: String, categoryId: String?, kind: String,
        amountMinor: Long, note: String, startDate: String, endDate: String?, frequency: String, interval: Int,
        mandatory: Boolean, primaryIncome: Boolean, paused: Boolean = false, archived: Boolean = false) = runMutation {
        val normalized = normalizeCategoryName(name); validateCategoryName(normalized)
        require(kind in setOf("expense", "income")); positive(amountMinor)
        require(note.codePointCount(0, note.length) <= 500) { "Описание: не больше 500 символов" }
        val start = PlanningCalendar.validateDate(startDate); endDate?.let { require(PlanningCalendar.validateDate(it) >= start) { "Окончание не может быть раньше начала" } }
        require(frequency in PLANNING_FREQUENCIES && interval in 1..12) { "Проверьте периодичность" }
        require(!mandatory || kind == "expense"); require(!primaryIncome || kind == "income")
        if (!archived && !paused) { activeAccount(accountId); activeCategory(categoryId, kind) } else { validateUuid(accountId); categoryId?.let(::validateUuid) }
        if (original?.scheduleLocked == true) require(original.startDate == startDate && original.frequency == frequency && original.interval == interval) {
            "У плана уже есть подтверждения. Для другой периодичности создайте новый план."
        }
        repository.mutateLedger(if (original == null) "createschedule" else "editschedule", original?.id,
            version(original?.version).apply {
                addProperty("name", normalized); addProperty("account_id", accountId); nullable("category_id", categoryId); addProperty("kind", kind)
                addProperty("amount_minor", amountMinor.toString()); addProperty("note", note.trim()); addProperty("start_date", startDate)
                nullable("end_date", endDate); addProperty("frequency", frequency); addProperty("interval", interval)
                addProperty("mandatory", mandatory); addProperty("primary_income", primaryIncome); addProperty("paused", paused); addProperty("archived", archived)
            })
    }

    fun postOccurrence(original: OccurrenceDto, amountMinor: Long, occurredAt: String, note: String, categoryId: String?) = runMutation {
        require(original.status == "pending" && original.transactionId == null) { "Это событие уже обработано" }
        require(original.blockedReason == null) { "Сначала включите план, счёт и категорию" }
        activeAccount(original.accountId); activeCategory(categoryId, original.kind); positive(amountMinor); Instant.parse(occurredAt)
        require(note.codePointCount(0, note.length) <= 500) { "Описание: не больше 500 символов" }
        repository.mutateLedger("postoccurrence", original.scheduleId, occurrenceVersion(original).apply {
            addProperty("amount_minor", amountMinor.toString()); addProperty("occurred_at", occurredAt); addProperty("note", note.trim()); nullable("category_id", categoryId)
        }, original.dueDate)
    }
    fun skipOccurrence(original: OccurrenceDto) = runMutation {
        require(original.status == "pending" && original.transactionId == null) { "Это событие уже обработано" }
        repository.mutateLedger("skipoccurrence", original.scheduleId, occurrenceVersion(original), original.dueDate)
    }
    fun unskipOccurrence(original: OccurrenceDto) = runMutation {
        require(original.status == "skipped" && original.transactionId == null) { "Это событие нельзя вернуть в ожидающие" }
        repository.mutateLedger("unskipoccurrence", original.scheduleId, occurrenceVersion(original), original.dueDate)
    }
    fun saveSettings(original: PlanningSettingsDto, reserveMinor: Long, accountIds: List<String>, baseCurrency: String) = runMutation {
        require(reserveMinor in 0..Money.MAX_INPUT_MINOR) { "Проверьте сумму резерва" }; SupportedCurrency.fromCode(baseCurrency)
        require(accountIds.distinct().size == accountIds.size && accountIds.size <= 200)
        accountIds.forEach { id ->
            val account = activeAccount(id)
            require(account.type in setOf("card", "cash")) { "Выберите доступные карточные или наличные счета" }
        }
        repository.mutateLedger("editplanningsettings", null, version(original.version).apply {
            addProperty("reserve_minor", reserveMinor.toString()); addProperty("base_currency", baseCurrency)
            add("account_ids", JsonArray().apply { accountIds.forEach(::add) })
        })
    }

    fun retryPending() {
        val snapshot = state.value
        if (snapshot.busy || snapshot.externalBlocked || snapshot.recoveryUnavailable || snapshot.recoveryChecking) return
        runMutation(retry = true) {
            when {
                snapshot.pending != null -> repository.retryLedgerMutation()
                snapshot.manualPending -> repository.retryPending()
                else -> throw IllegalStateException("Нет ожидающего подтверждения")
            }
        }
    }

    private fun runMutation(retry: Boolean = false, action: suspend () -> Any) {
        if (!retry && !state.value.canWrite) return
        val session = repository.session.value ?: return
        if (!current(session)) return
        _state.update { it.copy(busy = true, error = null, notice = null) }
        actionJob = viewModelScope.launch {
            try {
                action()
                if (!current(session)) return@launch
                val metadataFailure = refreshMetadata(session)
                if (metadataFailure != null && expireIfUnauthorized(metadataFailure, session)) return@launch
                if (!current(session)) return@launch
                _state.update { it.copy(revision = it.revision + 1, report = null, forecast = null, receivedAt = null,
                    notice = "Изменения сохранены", error = metadataFailure?.let(::message)) }
                mutationCallback?.invoke("")
                loadReport(preserveError = metadataFailure != null)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (current(session) && !expireIfUnauthorized(error, session)) {
                    if (error is HttpException && error.code() == 409) {
                        metadataJob?.cancel(); metadataJob = launch { refreshMetadata(session)?.let { expireIfUnauthorized(it, session) } }
                        loadReport(preserveError = true)
                    }
                    _state.update { it.copy(error = message(error)) }
                }
            } finally { if (current(session)) _state.update { it.copy(busy = false) } }
        }
    }

    private suspend fun refreshMetadata(session: Session): Exception? = supervisorScope {
        if (!current(session)) return@supervisorScope null
        val accounts = async { runCatching { repository.refreshAccounts() } }
        val categories = async { runCatching { repository.refreshCategories() } }
        val failures = listOf(accounts.await(), categories.await()).mapNotNull { it.exceptionOrNull() }
        failures.filterIsInstance<HttpException>().firstOrNull { it.code() == 401 } ?: failures.filterIsInstance<Exception>().firstOrNull { it !is CancellationException }
    }
    private suspend fun expireIfUnauthorized(error: Exception, session: Session): Boolean {
        if (error !is HttpException || error.code() != 401) return false
        if (current(session)) repository.expireSession(session)
        return true
    }
    private fun current(session: Session): Boolean = repository.session.value?.let { it.token == session.token && it.user.id == session.user.id } == true && state.value.session?.token == session.token
    private fun activeAccount(id: String): Account = requireNotNull(state.value.accounts.firstOrNull { it.id == id && !it.archived }) { "Выберите действующий счёт" }
    private fun activeCategory(id: String?, kind: String) { id?.let { require(state.value.categories.any { c -> c.id == it && c.kind == kind && !c.archived }) { "Выберите действующую категорию" } } }
    private fun positive(value: Long) { require(value in 1..Money.MAX_INPUT_MINOR) { "Сумма должна быть больше нуля и не превышать допустимый размер" } }
    private fun version(value: Long?) = JsonObject().apply { if (value != null) { require(value >= 0); addProperty("expected_version", value) } }
    private fun occurrenceVersion(value: OccurrenceDto) = JsonObject().apply {
        value.validated(); addProperty("expected_template_version", value.scheduleVersion); addProperty("expected_occurrence_version", value.version)
    }
    private fun JsonObject.nullable(key: String, value: String?) { if (value == null) add(key, JsonNull.INSTANCE) else addProperty(key, value) }
    private fun message(error: Exception): String = when (error) {
        is IOException -> "Нет связи с сервером. Сохранённое подтверждение можно повторить с тем же запросом."
        is HttpException -> when (error.code()) {
            401 -> "Сессия истекла. Войдите снова для повтора сохранённого подтверждения."
            409 -> when (runCatching { Gson().fromJson(error.response()?.errorBody()?.source()?.peek()?.readUtf8(), ErrorBody::class.java)?.error?.code }.getOrNull()) {
                "budget_overlap" -> "Для этой категории или её подкатегории уже есть активный бюджет в этой валюте."
                "schedule_anchor_locked" -> "У плана есть подтверждения. Для другой периодичности создайте новый план."
                "occurrence_posted" -> "Событие уже записано в историю. Повторная запись не создана."
                "archived_account", "archived_category", "archived_schedule", "paused" -> "Счёт, категория или план отключены. Обновите данные и включите их перед записью."
                else -> "Данные изменились. План обновлён; откройте форму заново. Если подтверждение ожидает ответа, нажмите «Повторить»."
            }
            404 -> "План или событие больше недоступны. Обновите данные."
            else -> "Сервер отклонил изменение (${error.code()}). Проверьте данные."
        }
        else -> error.message ?: "Не удалось обновить план"
    }
    class Factory(@Suppress("UNUSED_PARAMETER") context: Context, private val repository: MoneyFlowRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = PlanningViewModel(repository) as T
    }
}
