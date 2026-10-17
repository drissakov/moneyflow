package com.moneyflow.app.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.moneyflow.app.data.MoneyFlowRepository
import com.moneyflow.app.data.Session
import com.moneyflow.app.data.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import retrofit2.HttpException
import java.io.IOException
import java.time.Instant
import java.time.YearMonth

data class AdvancedAnalyticsUiState(
    val session: Session? = null,
    val month: String = PlanningCalendar.month(),
    val currency: String = "KZT",
    val months: Int = 6,
    val accountId: String? = null,
    val mode: String = "original",
    val valuation: String = "historical",
    val accounts: List<Account> = emptyList(),
    val report: AdvancedAnalyticsReport? = null,
    val loading: Boolean = false,
    val offline: Boolean = false,
    val receivedAt: Instant? = null,
    val error: String? = null,
) {
    val filter: AdvancedAnalyticsFilter get() = AdvancedAnalyticsFilter(month, currency, months, accountId, mode, valuation)
}

/** Read-only server reports. A filter or session change invalidates every older in-flight response. */
class AdvancedAnalyticsViewModel(private val repository: MoneyFlowRepository) : ViewModel() {
    private val _state = MutableStateFlow(AdvancedAnalyticsUiState())
    val state: StateFlow<AdvancedAnalyticsUiState> = _state.asStateFlow()
    private var reportJob: Job? = null
    private var metadataJob: Job? = null
    private var generation = 0L

    init {
        viewModelScope.launch {
            repository.session.collectLatest { session ->
                generation++; reportJob?.cancel(); metadataJob?.cancel()
                _state.value = AdvancedAnalyticsUiState(session = session)
                if (session != null) coroutineScope {
                    launch { repository.observeAccounts(session.user.id).collect { accounts ->
                        if (current(session)) _state.update { it.copy(accounts = accounts) }
                    } }
                    refresh()
                }
            }
        }
    }

    fun clearMessage() { _state.update { it.copy(error = null) } }
    fun setMonth(month: String) = changeFilter(state.value.filter.copy(month = month))
    fun setMonths(months: Int) = changeFilter(state.value.filter.copy(months = months))
    fun setMode(mode: String) = changeFilter(state.value.filter.let {
        if (mode == "consolidated") it.copy(mode = mode, currency = "KZT", accountId = null) else it.copy(mode = mode)
    }.normalizeHistoricalMonth())
    fun setValuation(valuation: String) = changeFilter(state.value.filter.copy(valuation = valuation).normalizeHistoricalMonth())
    fun setCurrency(currency: String) {
        val selected = state.value.accountId?.let { id -> state.value.accounts.firstOrNull { it.id == id } }
        changeFilter(state.value.filter.copy(currency = currency, accountId = selected?.takeIf { it.currency == currency }?.id))
    }
    fun setAccount(accountId: String?) {
        if (accountId != null && state.value.accounts.none { it.id == accountId && it.currency == state.value.currency }) {
            _state.update { it.copy(error = "Выберите счёт в валюте отчёта") }; return
        }
        changeFilter(state.value.filter.copy(accountId = accountId))
    }
    private fun changeFilter(filter: AdvancedAnalyticsFilter) {
        try { filter.validate() } catch (error: Exception) { _state.update { it.copy(error = message(error)) }; return }
        if (filter.mode == "consolidated" && filter.valuation == "historical" && YearMonth.parse(filter.month) > YearMonth.parse(PlanningCalendar.month())) {
            _state.update { it.copy(error = "Исторические курсы доступны до текущего месяца") }; return
        }
        if (filter == state.value.filter) return
        _state.update { it.copy(month = filter.month, currency = filter.currency, months = filter.months, accountId = filter.accountId, mode = filter.mode, valuation = filter.valuation,
            report = null, receivedAt = null, offline = false, error = null) }
        load()
    }

    fun refresh() {
        val session = repository.session.value ?: return
        if (!current(session)) return
        metadataJob?.cancel()
        metadataJob = viewModelScope.launch {
            try { repository.refreshAccounts() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (current(session) && !expireIfUnauthorized(error, session)) _state.update {
                    it.copy(error = "Не удалось обновить список счетов. " + message(error))
                }
            }
        }
        load()
    }

    private fun load() {
        val session = repository.session.value ?: return
        if (!current(session)) return
        val filter = state.value.filter
        generation++; val requestGeneration = generation; reportJob?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        reportJob = viewModelScope.launch {
            try {
                val result = repository.advancedAnalytics(filter)
                if (!current(session) || generation != requestGeneration) return@launch
                _state.update { it.copy(report = result.report, receivedAt = result.receivedAt, loading = false, offline = false) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (!current(session) || generation != requestGeneration || expireIfUnauthorized(error, session)) return@launch
                val cache = runCatching { repository.cachedAdvancedAnalytics(filter) }.getOrNull()
                if (!current(session) || generation != requestGeneration) return@launch
                _state.update { it.copy(report = cache?.report ?: it.report, receivedAt = cache?.receivedAt ?: it.receivedAt,
                    loading = false, offline = true, error = message(error)) }
            }
        }
    }
    private suspend fun expireIfUnauthorized(error: Exception, session: Session): Boolean {
        if (error !is HttpException || error.code() != 401) return false
        if (current(session)) repository.expireSession(session)
        return true
    }
    private fun current(session: Session): Boolean = repository.session.value?.let { it.user.id == session.user.id && it.token == session.token } == true &&
        state.value.session?.let { it.user.id == session.user.id && it.token == session.token } == true
    private fun message(error: Exception): String = when (error) {
        is IOException -> "Нет связи с сервером. Сохранённый отчёт доступен только для просмотра и может отличаться от текущих данных."
        is HttpException -> when (error.code()) {
            401 -> "Сессия истекла. Войдите снова, чтобы загрузить отчёт."
            409 -> "Сумма отчёта превышает допустимый размер. Выберите другой период или отдельный счёт."
            404 -> "Выбранный счёт недоступен. Обновите список счетов."
            503 -> "Курсы валют временно недоступны. Обновите отчёт позже. Сохранённый отчёт, если есть, доступен только для просмотра."
            else -> "Не удалось загрузить отчёт (${error.code()}). Обновите данные."
        }
        else -> error.message ?: "Не удалось загрузить отчёт"
    }
    class Factory(@Suppress("UNUSED_PARAMETER") context: Context, private val repository: MoneyFlowRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = AdvancedAnalyticsViewModel(repository) as T
    }
}

internal fun AdvancedAnalyticsFilter.normalizeHistoricalMonth(currentMonth: String = PlanningCalendar.month()): AdvancedAnalyticsFilter =
    if (mode == "consolidated" && valuation == "historical" && YearMonth.parse(month) > YearMonth.parse(currentMonth)) copy(month = currentMonth) else this
