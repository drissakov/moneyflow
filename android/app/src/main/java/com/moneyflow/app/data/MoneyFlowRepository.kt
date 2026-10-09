package com.moneyflow.app.data

import androidx.room.withTransaction
import com.google.gson.Gson
import com.moneyflow.app.data.domain.Account
import com.moneyflow.app.data.domain.MoneyTransaction
import com.moneyflow.app.data.domain.User
import com.moneyflow.app.data.local.AccountEntity
import com.moneyflow.app.data.local.MoneyFlowDatabase
import com.moneyflow.app.data.local.TransactionEntity
import com.moneyflow.app.data.remote.AuthResponse
import com.moneyflow.app.data.remote.CreateAccountRequest
import com.moneyflow.app.data.remote.CreateTransactionRequest
import com.moneyflow.app.data.remote.Credentials
import com.moneyflow.app.data.remote.MoneyFlowApi
import com.moneyflow.app.data.remote.ErrorBody
import com.moneyflow.app.data.remote.validateUuid
import com.moneyflow.app.data.remote.isDefinitiveTransactionFailure
import com.moneyflow.app.data.remote.matchesTransactionRequest
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
import retrofit2.HttpException
import java.util.Locale
import java.util.UUID

class MoneyFlowRepository(
    private val database: MoneyFlowDatabase,
    private val store: SessionStore,
    apiProvider: (() -> String?) -> MoneyFlowApi,
) {
    private val mutex = Mutex()
    private val _session = MutableStateFlow(store.readSession())
    val session: StateFlow<Session?> = _session.asStateFlow()
    private val _pending = MutableStateFlow(_session.value?.user?.id?.let(store::readPending))
    val pending: StateFlow<PendingTransaction?> = _pending.asStateFlow()
    private val api = apiProvider { _session.value?.token }
    private val dao = database.dao()

    fun observeAccounts(userId: String): Flow<List<Account>> =
        dao.observeAccounts(userId).map { rows -> rows.map(AccountEntity::toDomain) }

    fun observeTransactions(userId: String, accountId: String): Flow<List<MoneyTransaction>> =
        dao.observeTransactions(userId, accountId).map { rows -> rows.map(TransactionEntity::toDomain) }

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
        _session.value = current
    }

    suspend fun verifySession(): User = api.me().also {
        validateUuid(it.id)
        require(it.email.isNotBlank())
    }

    /** Sign-in expiry retains a pending request for the same user to recover safely after re-authentication. */
    suspend fun expireSession() = withContext(Dispatchers.IO) {
        clearLocalSession()
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

    private suspend fun clearLocalSession() = withContext(NonCancellable + Dispatchers.IO) {
        mutex.withLock {
            val userId = _session.value?.user?.id
            store.clearSession()
            _session.value = null
            _pending.value = null
            if (userId != null) database.withTransaction {
                dao.deleteAccounts(userId)
                dao.deleteUserTransactions(userId)
            }
        }
    }

    suspend fun refreshAccounts() {
        val userId = requireUserId()
        val accounts = api.accounts().accounts.map { AccountEntity.from(userId, it.toDomain()) }
        mutex.withLock {
            if (_session.value?.user?.id != userId) return
            database.withTransaction {
                dao.deleteAccounts(userId)
                dao.putAccounts(accounts)
            }
        }
    }

    suspend fun refreshTransactions(accountId: String) {
        val userId = requireUserId()
        val transactions = api.transactions(accountId).transactions.map {
            require(it.accountId == accountId) { "Сервер вернул операции другого счёта" }
            TransactionEntity.from(userId, it.toDomain())
        }
        mutex.withLock {
            if (_session.value?.user?.id != userId) return
            database.withTransaction {
                dao.deleteTransactions(userId, accountId)
                dao.putTransactions(transactions)
            }
        }
    }

    suspend fun createAccount(body: CreateAccountRequest): Account {
        val userId = requireUserId()
        val account = api.createAccount(body).toDomain()
        require(account.name == body.name && account.currency == body.currency &&
            account.openingBalanceMinor.toString() == body.openingBalanceMinor) { "Ответ сервера не соответствует создаваемому счёту" }
        mutex.withLock {
            if (_session.value?.user?.id == userId) dao.putAccounts(listOf(AccountEntity.from(userId, account)))
        }
        return account
    }

    suspend fun createTransaction(body: CreateTransactionRequest): MoneyTransaction {
        val userId = requireUserId()
        val pending = withContext(Dispatchers.IO) {
            mutex.withLock {
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
        return submitPending(pending)
    }

    suspend fun retryPending(): MoneyTransaction {
        val request = _pending.value ?: throw IllegalStateException("Нет ожидающей операции")
        require(request.userId == requireUserId())
        return submitPending(request)
    }

    private suspend fun submitPending(request: PendingTransaction): MoneyTransaction {
        val transaction = try {
            api.createTransaction(request.key, request.body).toDomain().also {
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
                    store.clearPending(request.userId)
                    _pending.value = null
                }
            }
            throw error
        }
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (_session.value?.user?.id == request.userId) {
                    dao.putTransactions(listOf(TransactionEntity.from(request.userId, transaction)))
                    store.clearPending(request.userId)
                    _pending.value = null
                }
            }
        }
        return transaction
    }

    private fun requireUserId(): String = _session.value?.user?.id
        ?: throw IllegalStateException("Войдите в аккаунт")
}
