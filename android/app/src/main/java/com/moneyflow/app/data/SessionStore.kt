package com.moneyflow.app.data

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.gson.Gson
import com.moneyflow.app.data.domain.User
import com.moneyflow.app.data.remote.CreateTransactionRequest
import com.moneyflow.app.data.remote.validateUuid
import java.security.KeyStore
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Session(val token: String, val expiresAt: String, val user: User) {
    internal fun validate(now: Instant = Instant.now()) {
        require(token.isNotBlank()) { "Некорректная сессия в ответе сервера" }
        validateUuid(user.id)
        require(user.email.isNotBlank())
        require(Instant.parse(expiresAt).isAfter(now)) { "Сессия истекла" }
    }
}
data class PendingTransaction(val userId: String, val key: String, val body: CreateTransactionRequest)

/** AES-GCM encrypted values in app-private preferences; encryption key stays in Android Keystore. */
@SuppressLint("ApplySharedPref") // Checked synchronous commits durably persist retry keys and sign-out before returning.
class SessionStore(context: Context) {
    private val preferences = context.getSharedPreferences("moneyflow_private", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val alias = "moneyflow_session_aes_v1"

    @Synchronized
    fun readSession(): Session? {
        val session = read("session", Session::class.java)
        val valid = runCatching {
            requireNotNull(session).validate()
            true
        }.getOrDefault(false)
        if (!valid) {
            clearSession()
            return null
        }
        return session
    }

    @Synchronized
    fun writeSession(session: Session) {
        session.validate()
        write("session", session)
    }

    @Synchronized
    fun clearSession() {
        check(preferences.edit().remove("session").commit()) { "Не удалось удалить сессию" }
    }

    @Synchronized
    fun readPending(userId: String): PendingTransaction? {
        val name = pendingName(userId)
        val pending = read(name, PendingTransaction::class.java) ?: return null
        val valid = runCatching {
            require(pending.userId == userId)
            validateUuid(pending.key)
            pending.body.validate()
            true
        }.getOrDefault(false)
        if (!valid) {
            clearPending(userId)
            return null
        }
        return pending
    }

    @Synchronized
    fun writePending(pending: PendingTransaction) {
        validateUuid(pending.userId)
        validateUuid(pending.key)
        pending.body.validate()
        write(pendingName(pending.userId), pending)
    }

    @Synchronized
    fun clearPending(userId: String) {
        check(preferences.edit().remove(pendingName(userId)).commit()) { "Не удалось удалить ожидающую операцию" }
    }

    private fun pendingName(userId: String): String {
        validateUuid(userId)
        return "pending_$userId"
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
        }.generateKey()
    }

    private fun write(name: String, value: Any) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(gson.toJson(value).toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        check(preferences.edit().putString(name, encoded).commit()) { "Не удалось сохранить данные на устройстве" }
    }

    private fun <T> read(name: String, type: Class<T>): T? {
        val encoded = preferences.getString(name, null) ?: return null
        return try {
            val parts = encoded.split(':')
            require(parts.size == 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
            val decrypted = cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP))
            gson.fromJson(String(decrypted, Charsets.UTF_8), type)
        } catch (_: Exception) {
            preferences.edit().remove(name).commit()
            null
        }
    }
}
