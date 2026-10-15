package com.moneyflow.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.moneyflow.app.data.MoneyFlowRepository
import com.moneyflow.app.data.PendingTransaction
import com.moneyflow.app.data.Session
import com.moneyflow.app.data.CachedPortfolio
import com.moneyflow.app.data.domain.Account
import com.moneyflow.app.data.domain.Category
import com.moneyflow.app.data.domain.MonthlyAnalytics
import com.moneyflow.app.data.domain.Money
import com.moneyflow.app.data.domain.MoneyTransaction
import com.moneyflow.app.data.domain.SupportedCurrency
import com.moneyflow.app.data.remote.CreateAccountRequest
import com.moneyflow.app.data.remote.CreateTransactionRequest
import com.moneyflow.app.data.remote.ErrorBody
import com.moneyflow.app.data.remote.ConvertRequest
import com.moneyflow.app.data.remote.ConversionDto
import com.moneyflow.app.data.remote.PortfolioDto
import com.moneyflow.app.data.remote.CreateCategoryRequest
import com.moneyflow.app.data.remote.normalizeCategoryName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class MoneyFlowUiState(
    val starting: Boolean = true,
    val session: Session? = null,
    val accounts: List<Account> = emptyList(),
    val selectedAccountId: String? = null,
    val transactions: List<MoneyTransaction> = emptyList(),
    val pending: PendingTransaction? = null,
    val working: Boolean = false,
    val refreshRequests: Int = 0,
    val error: String? = null,
    val notice: String? = null,
    val lastSyncedAt: Instant? = null,
    val mutationVersion: Int = 0,
    val accountsLoaded: Boolean = false,
    val baseCurrency: String = "KZT",
    val basePreferencePending: Boolean = false,
    val cachedPortfolio: CachedPortfolio? = null,
    val fxRefreshRequests: Int = 0,
    val fxError: String? = null,
    val lastFxConfirmedAt: Instant? = null,
    val fxNow: Instant = Instant.now(),
    val conversion: ConversionDto? = null,
    val conversionWorking: Boolean = false,
    val conversionError: String? = null,
    val categories: List<Category> = emptyList(),
    val categoriesLoading: Boolean = false,
    val categoryWorking: Boolean = false,
    val categoryError: String? = null,
    val categoryMutationVersion: Int = 0,
    val lastCreatedCategoryId: String? = null,
    val transactionCategoryVersion: Int = 0,
    val analytics: MonthlyAnalytics? = null,
    val analyticsMonth: String = YearMonth.now().toString(),
    val analyticsCurrency: String = "KZT",
    val analyticsTimezone: String = ZoneId.systemDefault().id,
    val analyticsLoading: Boolean = false,
    val analyticsError: String? = null,
) {
    val selectedAccount: Account? get() = accounts.firstOrNull { it.id == selectedAccountId }
    val refreshing: Boolean get() = refreshRequests > 0
    val fxRefreshing: Boolean get() = fxRefreshRequests > 0
    val portfolio: PortfolioDto? get() = cachedPortfolio?.portfolio?.takeIf {
        accountsLoaded && it.baseCurrency == baseCurrency && it.matchesAccounts(accounts) && !it.ratesExpired(fxNow)
    }
}

class MoneyFlowViewModel(private val repository: MoneyFlowRepository) : ViewModel() {
    private val _state = MutableStateFlow(MoneyFlowUiState())
    val state = _state.asStateFlow()
    private val selected = MutableStateFlow<String?>(null)
    private val base = MutableStateFlow("KZT")
    private val accountSource = MutableStateFlow<List<Account>?>(null)
    private var conversionJob: Job? = null
    private var conversionGeneration = 0L
    private var analyticsJob: Job? = null
    private var analyticsGeneration = 0L

    init {
        viewModelScope.launch {
            repository.session.collectLatest { session ->
                selected.value = null
                base.value = "KZT"
                accountSource.value = null
                clearConversion()
                analyticsGeneration++
                analyticsJob?.cancel()
                _state.update {
                    it.copy(starting = false, session = session, accounts = emptyList(),
                        working = false,
                        selectedAccountId = null, transactions = emptyList(), lastSyncedAt = null,
                        accountsLoaded = false, baseCurrency = "KZT", basePreferencePending = false,
                        cachedPortfolio = null, fxError = null, lastFxConfirmedAt = null,
                        categories = emptyList(), categoriesLoading = false, categoryWorking = false,
                        categoryError = null, lastCreatedCategoryId = null,
                        analytics = null, analyticsLoading = false, analyticsError = null)
                }
                if (session != null) coroutineScope {
                    launch {
                        repository.observeCategories(session.user.id).collect { categories ->
                            if (repository.session.value?.token == session.token) {
                                _state.update { it.copy(categories = categories) }
                            }
                        }
                    }
                    launch { categoriesRequest(session.token) { repository.refreshCategories() } }
                    refreshAnalytics()
                    launch {
                        repository.observeAccounts(session.user.id).collect { accounts ->
                            val next = selected.value?.takeIf { id -> accounts.any { it.id == id && !it.archived } }
                                ?: accounts.firstOrNull { !it.archived }?.id
                            _state.update { it.copy(accounts = accounts, selectedAccountId = next, accountsLoaded = true) }
                            accountSource.value = accounts
                            selected.value = next
                        }
                    }
                    launch {
                        repository.observePreferences(session.user.id).collect { preferences ->
                            _state.update {
                                val changed = it.baseCurrency != preferences.baseCurrency
                                it.copy(baseCurrency = preferences.baseCurrency,
                                    basePreferencePending = preferences.pendingSync,
                                    cachedPortfolio = if (changed) null else it.cachedPortfolio,
                                    lastFxConfirmedAt = if (changed) null else it.lastFxConfirmedAt)
                            }
                            base.value = preferences.baseCurrency
                        }
                    }
                    launch { fxRequest(session.token) { repository.refreshPreferences() } }
                    launch { fxRequest(session.token) { repository.refreshCurrencies() } }
                    launch {
                        refreshRequest(session) {
                            val user = repository.verifySession()
                            require(user.id == session.user.id) { "Пользователь сессии изменился. Войдите снова" }
                            refreshAccounts()
                        }
                    }
                }
            }
        }
        viewModelScope.launch {
            combine(repository.session, selected) { session, accountId -> session to accountId }
                .collectLatest { (session, accountId) ->
                    _state.update { it.copy(transactions = emptyList()) }
                    if (session != null && accountId != null) coroutineScope {
                        launch {
                            repository.observeTransactions(session.user.id, accountId).collect { transactions ->
                                _state.update { it.copy(transactions = transactions) }
                            }
                        }
                        launch { refreshRequest(session) { repository.refreshTransactions(accountId) } }
                    }
                }
        }
        viewModelScope.launch {
            repository.pending.collect { pending -> _state.update { it.copy(pending = pending) } }
        }
        viewModelScope.launch {
            combine(repository.session, base, accountSource) { session, currency, accounts -> Triple(session, currency, accounts) }
                .collectLatest { (session, currency, accounts) ->
                    if (session == null || accounts == null) return@collectLatest
                    _state.update { it.copy(cachedPortfolio = null, lastFxConfirmedAt = null) }
                    coroutineScope {
                        launch {
                            repository.observePortfolio(session.user.id, currency).collect { cached ->
                                if (repository.session.value?.token == session.token && base.value == currency) {
                                    _state.update { it.copy(cachedPortfolio = cached) }
                                }
                            }
                        }
                        launch { fxRequest(session.token) { fetchPortfolio(currency, session.token) } }
                    }
                }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(60_000)
                _state.update { it.copy(fxNow = Instant.now()) }
            }
        }
    }

    fun authenticate(email: String, password: String, register: Boolean) = action {
        require(email.trim().matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))) { "Введите корректный email" }
        val bytes = password.toByteArray(Charsets.UTF_8).size
        if (register) require(bytes in 12..128) { "Пароль должен содержать от 12 до 128 байт UTF-8" }
        else require(password.isNotEmpty()) { "Введите пароль" }
        repository.authenticate(email, password, register)
    }

    fun selectAccount(accountId: String) {
        if (accountId == selected.value) return
        selected.value = accountId
        _state.update { it.copy(selectedAccountId = accountId, transactions = emptyList(), error = null) }
    }

    fun refresh() {
        val session = repository.session.value ?: return
        if (state.value.refreshing) return
        _state.update { it.copy(error = null, notice = null) }
        viewModelScope.launch {
            refreshRequest(session) {
                refreshAccounts()
                selected.value?.let { repository.refreshTransactions(it) }
            }
        }
        refreshFx()
        refreshCategories()
        refreshAnalytics()
    }

    fun onImportedFinancialDataChanged(accountId: String) {
        selected.value = accountId
        _state.update { it.copy(selectedAccountId = accountId, transactions = emptyList(), cachedPortfolio = null,
            lastFxConfirmedAt = null, analytics = null) }
        refreshAfterWrite()
        refreshAnalytics()
    }

    fun createAccount(name: String, currency: SupportedCurrency, opening: String) = action {
        val actor = requireNotNull(repository.session.value)
        val normalizedName = name.trim()
        require(normalizedName.codePointCount(0, normalizedName.length) in 1..100) { "Название: от 1 до 100 символов" }
        val amount = Money.parseMinor(opening, currency)
        val account = repository.createAccount(CreateAccountRequest(normalizedName, currency.code, amount.toString()), actor)
        selected.value = account.id
        _state.update {
            it.copy(selectedAccountId = account.id, mutationVersion = it.mutationVersion + 1, notice = "Счёт создан",
                cachedPortfolio = null, lastFxConfirmedAt = null)
        }
        refreshAfterWrite()
    }

    fun createTransaction(kind: String, amount: String, note: String, categoryId: String? = null) = action {
        val session = repository.session.value ?: throw IllegalStateException("Войдите в аккаунт")
        val account = state.value.selectedAccount ?: throw IllegalArgumentException("Выберите счёт")
        require(kind == "income" || kind == "expense")
        require(note.codePointCount(0, note.length) <= 500) { "Комментарий: не более 500 символов" }
        val minor = Money.parseMinor(amount, SupportedCurrency.fromCode(account.currency), positive = true)
        val body = CreateTransactionRequest(account.id, kind, minor.toString(), note.trim(),
            Instant.now().truncatedTo(ChronoUnit.MICROS).toString(), categoryId)
        val pending = repository.pending.value
        // A retry from the form uses the original timestamp as well as the original idempotency key.
        val request = if (pending != null && pending.body.copy(occurredAt = body.occurredAt) == body) pending.body else body
        repository.createTransaction(request)
        if (repository.session.value?.token == session.token) transactionSaved()
    }

    fun retryPending() = action {
        val session = repository.session.value ?: throw IllegalStateException("Войдите в аккаунт")
        repository.retryPending()
        if (repository.session.value?.token == session.token) transactionSaved()
    }

    private fun transactionSaved() {
        _state.update { it.copy(mutationVersion = it.mutationVersion + 1, notice = "Операция сохранена на сервере",
            cachedPortfolio = null, lastFxConfirmedAt = null) }
        refreshAfterWrite()
        refreshAnalytics()
    }

    private fun refreshAfterWrite() {
        val session = repository.session.value ?: return
        viewModelScope.launch {
            refreshRequest(session) {
                refreshAccounts()
                selected.value?.let { repository.refreshTransactions(it) }
            }
        }
    }

    fun logout() = action {
        val serverRevoked = repository.logout()
        _state.update {
            it.copy(notice = if (serverRevoked) null else
                "Вы вышли на этом устройстве. Сервер недоступен: серверная сессия завершится по сроку действия.")
        }
    }

    fun clearMessages() { _state.update { it.copy(error = null, notice = null) } }

    fun refreshCategories() {
        val session = repository.session.value ?: return
        if (state.value.categoriesLoading) return
        viewModelScope.launch { categoriesRequest(session.token) { repository.refreshCategories() } }
    }

    fun createCategory(kind: String, name: String, parentId: String? = null) {
        if (state.value.categoryWorking) return
        val session = repository.session.value ?: return
        viewModelScope.launch {
            if (repository.session.value?.token != session.token) return@launch
            _state.update { it.copy(categoryWorking = true, categoryError = null, lastCreatedCategoryId = null) }
            try {
                val category = repository.createCategory(CreateCategoryRequest(kind, normalizeCategoryName(name), parentId))
                if (repository.session.value?.token == session.token) {
                    _state.update { it.copy(categoryMutationVersion = it.categoryMutationVersion + 1,
                        lastCreatedCategoryId = category.id) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (repository.session.value?.token == session.token) {
                    if (error is HttpException && error.code() == 401) handleError(error, session)
                    else _state.update { it.copy(categoryError = categoryMessage(error)) }
                }
            } finally {
                if (repository.session.value?.token == session.token) _state.update { it.copy(categoryWorking = false) }
            }
        }
    }

    fun clearCategoryMessages() { _state.update { it.copy(categoryError = null, lastCreatedCategoryId = null) } }

    fun setTransactionCategory(transactionId: String, categoryId: String?) = action {
        val session = repository.session.value ?: throw IllegalStateException("Войдите в аккаунт")
        val transaction = state.value.transactions.firstOrNull { it.id == transactionId }
            ?: throw IllegalArgumentException("Операция не найдена. Обновите данные")
        repository.setTransactionCategory(transaction, categoryId)
        if (repository.session.value?.token == session.token) {
            _state.update { it.copy(transactionCategoryVersion = it.transactionCategoryVersion + 1,
                notice = "Категория операции обновлена") }
            refreshAnalytics()
        }
    }

    fun setAnalyticsMonth(month: String) {
        if (month == state.value.analyticsMonth) return
        runCatching {
            require(month.matches(Regex("\\d{4}-\\d{2}")))
            YearMonth.parse(month)
        }.getOrElse {
            _state.update { state -> state.copy(analyticsError = "Выберите корректный месяц") }
            return
        }
        _state.update { it.copy(analyticsMonth = month, analytics = null) }
        refreshAnalytics()
    }

    fun setAnalyticsCurrency(currency: String) {
        if (currency == state.value.analyticsCurrency) return
        runCatching { SupportedCurrency.fromCode(currency) }.getOrElse { return }
        _state.update { it.copy(analyticsCurrency = currency, analytics = null) }
        refreshAnalytics()
    }

    fun refreshAnalytics() {
        val session = repository.session.value ?: return
        analyticsGeneration++
        val generation = analyticsGeneration
        analyticsJob?.cancel()
        val requested = state.value
        analyticsJob = viewModelScope.launch {
            if (repository.session.value?.token != session.token) return@launch
            _state.update { it.copy(analyticsLoading = true, analyticsError = null, analytics = null) }
            try {
                val result = repository.fetchAnalytics(requested.analyticsMonth, requested.analyticsCurrency,
                    requested.analyticsTimezone)
                if (generation == analyticsGeneration && repository.session.value?.token == session.token) {
                    _state.update { it.copy(analytics = result) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == analyticsGeneration && repository.session.value?.token == session.token) {
                    if (error is HttpException && error.code() == 401) handleError(error, session)
                    else _state.update { it.copy(analyticsError = analyticsMessage(error)) }
                }
            } finally {
                if (generation == analyticsGeneration && repository.session.value?.token == session.token) {
                    _state.update { it.copy(analyticsLoading = false) }
                }
            }
        }
    }

    private suspend fun categoriesRequest(token: String, block: suspend () -> Unit) {
        val session = repository.session.value?.takeIf { it.token == token } ?: return
        _state.update { it.copy(categoriesLoading = true, categoryError = null) }
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (repository.session.value?.token == token) {
                if (error is HttpException && error.code() == 401) handleError(error, session)
                else _state.update { it.copy(categoryError = categoryMessage(error)) }
            }
        } finally {
            if (repository.session.value?.token == token) _state.update { it.copy(categoriesLoading = false) }
        }
    }

    private fun categoryMessage(error: Exception): String = when (error) {
        is IOException -> "Нет соединения с сервером. Сохранённые категории доступны; создание требует подключения."
        is HttpException -> httpMessage(error)
        is IllegalArgumentException -> error.message ?: "Проверьте категорию"
        else -> "Не удалось обновить категории. Попробуйте ещё раз"
    }

    private fun analyticsMessage(error: Exception): String = when (error) {
        is IOException -> "Нет соединения с сервером. Подключитесь и обновите аналитику."
        is HttpException -> if (error.code() == 409) "Итог за месяц слишком велик для расчёта. Выберите другой период."
            else httpMessage(error)
        else -> "Не удалось получить аналитику. Попробуйте обновить данные"
    }

    fun setBaseCurrency(currency: String) {
        if (currency == state.value.baseCurrency) return
        val session = repository.session.value ?: return
        _state.update { it.copy(fxError = null, cachedPortfolio = null, lastFxConfirmedAt = null) }
        viewModelScope.launch {
            fxRequest(session.token) {
                repository.setBaseCurrency(currency)
                if (repository.session.value?.token == session.token) fetchPortfolio(currency, session.token)
            }
        }
    }

    fun refreshFx() {
        val session = repository.session.value ?: return
        _state.update { it.copy(fxError = null) }
        viewModelScope.launch {
            fxRequest(session.token) {
                repository.refreshPreferences()
                if (repository.session.value?.token == session.token) {
                    fetchPortfolio(repository.preferredBaseCurrency(), session.token)
                }
            }
        }
    }

    fun clearConversion() {
        conversionGeneration++
        conversionJob?.cancel()
        conversionJob = null
        _state.update { it.copy(conversion = null, conversionWorking = false, conversionError = null) }
    }

    fun convert(amount: String, from: SupportedCurrency, to: SupportedCurrency) {
        clearConversion()
        val session = repository.session.value ?: return
        val generation = conversionGeneration
        conversionJob = viewModelScope.launch {
            _state.update { it.copy(conversionWorking = true) }
            try {
                val minor = Money.parseMinor(amount, from)
                val result = repository.convert(ConvertRequest(from.code, to.code, minor.toString()))
                if (repository.session.value?.token == session.token && generation == conversionGeneration) {
                    _state.update { it.copy(conversion = result) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (repository.session.value?.token == session.token && generation == conversionGeneration) {
                    if (error is HttpException && error.code() == 401) handleError(error, session)
                    else _state.update { it.copy(conversionError = fxMessage(error)) }
                }
            } finally {
                if (generation == conversionGeneration) _state.update { it.copy(conversionWorking = false) }
            }
        }
    }

    private suspend fun fetchPortfolio(currency: String, token: String) {
        repository.refreshPortfolio(currency)
        if (repository.session.value?.token == token && base.value == currency) {
            _state.update { it.copy(lastFxConfirmedAt = Instant.now(), fxNow = Instant.now(), fxError = null) }
        }
    }

    private suspend fun fxRequest(token: String, block: suspend () -> Unit) {
        val session = repository.session.value?.takeIf { it.token == token } ?: return
        _state.update { it.copy(fxRefreshRequests = it.fxRefreshRequests + 1) }
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (repository.session.value?.token == token) {
                if (error is HttpException && error.code() == 401) handleError(error, session)
                else _state.update { it.copy(fxError = fxMessage(error)) }
            }
        } finally {
            _state.update { it.copy(fxRefreshRequests = (it.fxRefreshRequests - 1).coerceAtLeast(0)) }
        }
    }

    private fun fxMessage(error: Exception): String = when (error) {
        is IOException -> "Нет связи с сервисом курсов. Счета и записи доступны; оценка из кэша помечена отдельно."
        is HttpException -> when (error.code()) {
            409 -> "Результат превышает допустимый размер суммы. Уменьшите значение."
            503 -> "Курсы Frankfurter сейчас недоступны для этой пары. Попробуйте обновить позже."
            429 -> "Слишком много запросов. Подождите и обновите курсы."
            else -> "Не удалось получить курсы (${error.code()}). Финансовые записи доступны."
        }
        is IllegalArgumentException -> error.message ?: "Некорректный ответ сервиса курсов"
        else -> "Не удалось обновить оценку в основной валюте. Финансовые записи доступны."
    }

    private fun action(block: suspend () -> Unit) {
        if (state.value.working) return
        val actionSession = repository.session.value
        viewModelScope.launch {
            if (repository.session.value?.token != actionSession?.token) return@launch
            _state.update { it.copy(working = true, error = null, notice = null) }
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (repository.session.value?.token == actionSession?.token) handleError(error, actionSession)
            } finally {
                if (actionSession == null || repository.session.value?.token == actionSession.token) {
                    _state.update { it.copy(working = false) }
                }
            }
        }
    }

    private suspend fun refreshRequest(session: Session, block: suspend () -> Unit) {
        if (repository.session.value?.token != session.token) return
        _state.update { it.copy(refreshRequests = it.refreshRequests + 1) }
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (repository.session.value?.token == session.token) handleError(error, session)
        } finally {
            _state.update { it.copy(refreshRequests = (it.refreshRequests - 1).coerceAtLeast(0)) }
        }
    }

    private suspend fun refreshAccounts() {
        val userId = repository.session.value?.user?.id
        repository.refreshAccounts()
        _state.update {
            if (userId != null && it.session?.user?.id == userId) it.copy(lastSyncedAt = Instant.now()) else it
        }
    }

    private suspend fun handleError(error: Exception, expectedSession: Session?) {
        val current = repository.session.value
        if (current?.token != expectedSession?.token || current?.user?.id != expectedSession?.user?.id) return
        if (error is HttpException && error.code() == 401 && expectedSession != null) {
            withContext(NonCancellable) {
                repository.expireSession(expectedSession)
                if (repository.session.value == null) _state.update { it.copy(error = "Сессия завершилась. Войдите снова") }
            }
            return
        }
        val message = when (error) {
            is IOException -> if (repository.pending.value != null)
                "Ответ сервера не получен. Операция могла сохраниться. Повторите её с тем же ключом кнопкой «Повторить»."
                else "Нет соединения с сервером. Проверьте интернет и обновите данные."
            is HttpException -> httpMessage(error)
            is IllegalArgumentException -> error.message ?: "Проверьте введённые данные"
            is IllegalStateException -> error.message ?: "Не удалось выполнить действие"
            else -> "Не удалось обработать ответ сервера. Попробуйте обновить данные"
        }
        _state.update { it.copy(error = message) }
    }

    private fun httpMessage(error: HttpException): String {
        val detail = runCatching {
            Gson().fromJson(error.response()?.errorBody()?.string(), ErrorBody::class.java)?.error
        }.getOrNull()
        return when (error.code()) {
            401 -> "Неверный email или пароль"
            403 -> "Нет доступа к этому счёту"
            404 -> "Счёт не найден. Обновите данные"
            409 -> if (detail?.code?.contains("email") == true) "Этот email уже зарегистрирован. Войдите в аккаунт"
                else detail?.message ?: "Данные изменились. Обновите данные и повторите действие"
            429 -> "Слишком много запросов. Подождите и повторите попытку"
            in 500..599 -> if (repository.pending.value != null)
                "Сервер временно недоступен. Повторите ожидающую операцию с тем же ключом."
                else "Сервер временно недоступен. Попробуйте позже"
            else -> detail?.message ?: "Запрос отклонён сервером (${error.code()})"
        }
    }

    companion object {
        fun factory(repository: MoneyFlowRepository): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(MoneyFlowViewModel::class.java))
                return MoneyFlowViewModel(repository) as T
            }
        }
    }
}
