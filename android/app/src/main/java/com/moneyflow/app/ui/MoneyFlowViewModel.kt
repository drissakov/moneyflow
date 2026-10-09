package com.moneyflow.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.moneyflow.app.data.MoneyFlowRepository
import com.moneyflow.app.data.PendingTransaction
import com.moneyflow.app.data.Session
import com.moneyflow.app.data.domain.Account
import com.moneyflow.app.data.domain.Money
import com.moneyflow.app.data.domain.MoneyTransaction
import com.moneyflow.app.data.domain.SupportedCurrency
import com.moneyflow.app.data.remote.CreateAccountRequest
import com.moneyflow.app.data.remote.CreateTransactionRequest
import com.moneyflow.app.data.remote.ErrorBody
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
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
) {
    val selectedAccount: Account? get() = accounts.firstOrNull { it.id == selectedAccountId }
    val refreshing: Boolean get() = refreshRequests > 0
}

class MoneyFlowViewModel(private val repository: MoneyFlowRepository) : ViewModel() {
    private val _state = MutableStateFlow(MoneyFlowUiState())
    val state = _state.asStateFlow()
    private val selected = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            repository.session.collectLatest { session ->
                selected.value = null
                _state.update {
                    it.copy(starting = false, session = session, accounts = emptyList(),
                        selectedAccountId = null, transactions = emptyList(), lastSyncedAt = null)
                }
                if (session != null) coroutineScope {
                    launch {
                        repository.observeAccounts(session.user.id).collect { accounts ->
                            val next = selected.value?.takeIf { id -> accounts.any { it.id == id } }
                                ?: accounts.firstOrNull()?.id
                            _state.update { it.copy(accounts = accounts, selectedAccountId = next) }
                            selected.value = next
                        }
                    }
                    launch {
                        refreshRequest {
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
                        launch { refreshRequest { repository.refreshTransactions(accountId) } }
                    }
                }
        }
        viewModelScope.launch {
            repository.pending.collect { pending -> _state.update { it.copy(pending = pending) } }
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
        if (state.value.refreshing) return
        _state.update { it.copy(error = null, notice = null) }
        viewModelScope.launch {
            refreshRequest {
                refreshAccounts()
                selected.value?.let { repository.refreshTransactions(it) }
            }
        }
    }

    fun createAccount(name: String, currency: SupportedCurrency, opening: String) = action {
        val normalizedName = name.trim()
        require(normalizedName.codePointCount(0, normalizedName.length) in 1..100) { "Название: от 1 до 100 символов" }
        val amount = Money.parseMinor(opening, currency)
        val account = repository.createAccount(CreateAccountRequest(normalizedName, currency.code, amount.toString()))
        selected.value = account.id
        _state.update {
            it.copy(selectedAccountId = account.id, mutationVersion = it.mutationVersion + 1, notice = "Счёт создан")
        }
        refreshAfterWrite()
    }

    fun createTransaction(kind: String, amount: String, note: String) = action {
        val account = state.value.selectedAccount ?: throw IllegalArgumentException("Выберите счёт")
        require(kind == "income" || kind == "expense")
        require(note.codePointCount(0, note.length) <= 500) { "Комментарий: не более 500 символов" }
        val minor = Money.parseMinor(amount, SupportedCurrency.fromCode(account.currency), positive = true)
        val body = CreateTransactionRequest(account.id, kind, minor.toString(), note.trim(),
            Instant.now().truncatedTo(ChronoUnit.MICROS).toString())
        val pending = repository.pending.value
        // A retry from the form uses the original timestamp as well as the original idempotency key.
        val request = if (pending != null && pending.body.copy(occurredAt = body.occurredAt) == body) pending.body else body
        repository.createTransaction(request)
        transactionSaved()
    }

    fun retryPending() = action {
        repository.retryPending()
        transactionSaved()
    }

    private fun transactionSaved() {
        _state.update { it.copy(mutationVersion = it.mutationVersion + 1, notice = "Операция сохранена на сервере") }
        refreshAfterWrite()
    }

    private fun refreshAfterWrite() {
        viewModelScope.launch {
            refreshRequest {
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

    private fun action(block: suspend () -> Unit) {
        if (state.value.working) return
        viewModelScope.launch {
            _state.update { it.copy(working = true, error = null, notice = null) }
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                handleError(error)
            } finally {
                _state.update { it.copy(working = false) }
            }
        }
    }

    private suspend fun refreshRequest(block: suspend () -> Unit) {
        _state.update { it.copy(refreshRequests = it.refreshRequests + 1) }
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            handleError(error)
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

    private suspend fun handleError(error: Exception) {
        if (error is HttpException && error.code() == 401 && repository.session.value != null) {
            withContext(NonCancellable) {
                repository.expireSession()
                _state.update { it.copy(error = "Сессия завершилась. Войдите снова") }
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
