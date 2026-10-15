package com.moneyflow.app.data

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.gson.Gson
import com.moneyflow.app.data.remote.PendingLedgerMutation
import com.moneyflow.app.data.remote.validateUuid
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface LedgerRecoveryStorage {
    fun read(userId: String): PendingLedgerMutation?
    fun write(action: PendingLedgerMutation)
    fun clear(userId: String, expectedKey: String)
}

class LedgerRecoveryUnavailable(cause: Throwable? = null) : IllegalStateException(
    "Не удалось прочитать сохранённое подтверждение. Оно сохранено; новые изменения заблокированы до проверки истории.", cause)

/** AES-GCM encrypted immutable request body and UUID. Corrupt confirmations are retained and block writes. */
@SuppressLint("ApplySharedPref") // Confirmation key must be durable before a financial request leaves the device.
class EncryptedLedgerRecoveryStore(context: Context,
    private val decryptOverride: ((String, String) -> String)? = null,
) : LedgerRecoveryStorage {
    private val preferences = context.getSharedPreferences("moneyflow_ledger_recovery", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val alias = "moneyflow_ledger_recovery_aes_v1"

    @Synchronized override fun read(userId: String): PendingLedgerMutation? {
        val name = name(userId)
        val encoded = try { preferences.getString(name, null) } catch (error: Exception) {
            throw LedgerRecoveryUnavailable(error)
        } ?: return null
        return runCatching {
            val json = decryptOverride?.invoke(name, encoded) ?: decrypt(name, encoded)
            gson.fromJson(json, PendingLedgerMutation::class.java).also { it.validate(); require(it.userId == userId) }
        }.getOrElse { throw LedgerRecoveryUnavailable(it) }
    }

    private fun decrypt(name: String, encoded: String): String {
            val parts = encoded.split(':'); require(parts.size == 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
            return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }

    @Synchronized override fun write(action: PendingLedgerMutation) {
        action.validate()
        val existing = read(action.userId)
        require(existing == null || existing == action) { "Сначала подтвердите ожидающее изменение" }
        val name = name(action.userId)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key()); cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(gson.toJson(action).toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)
        check(preferences.edit().putString(name, encoded).commit()) { "Не удалось сохранить подтверждение изменения" }
    }

    @Synchronized override fun clear(userId: String, expectedKey: String) {
        if (read(userId)?.key == expectedKey) check(preferences.edit().remove(name(userId)).commit()) {
            "Не удалось удалить подтверждение изменения"
        }
    }

    private fun name(userId: String): String { validateUuid(userId); return "pending_$userId" }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
}
