package com.hongguo.watch.data.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores the current session in an app-private file excluded from Android backup. */
internal class SyncSessionStore(context: Context) {
    private val sessionFile = EncryptedSessionFile(context, "account-sync-session", "hongguo_watch_account_sync")

    fun load(origin: String?): SyncSession? {
        if (origin == null) return null
        val json = sessionFile.read() ?: return null
        if (json.getString("origin") != origin) return null
        return SyncSession(origin, json.getString("token"), json.getString("accountId"), json.getString("accountName"))
    }

    fun save(session: SyncSession) {
        sessionFile.write(
            JSONObject()
                .put("origin", session.origin)
                .put("token", session.token)
                .put("accountId", session.accountId)
                .put("accountName", session.accountName),
        )
    }

    fun clear() = sessionFile.clear()
}

internal class EncryptedSessionFile(context: Context, filename: String, private val keyAlias: String) {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, filename))

    @Synchronized
    fun read(): JSONObject? {
        return try {
            val blob = readFile() ?: return null
            if (blob.size < HEADER_SIZE + TAG_SIZE || blob[0] != VERSION) {
                throw IOException("Invalid account session file")
            }
            val key = readKey(keyStore()) ?: throw IOException("Account session key missing")
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_SIZE * Byte.SIZE_BITS, blob, 1, IV_SIZE))
            cipher.updateAAD(byteArrayOf(VERSION))
            JSONObject(cipher.doFinal(blob, HEADER_SIZE, blob.size - HEADER_SIZE).decodeToString())
        } catch (_: Exception) {
            throw IOException("Unable to read account session")
        }
    }

    @Synchronized
    fun write(json: JSONObject) {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, readKey(keyStore()) ?: generateKey())
            val iv = cipher.iv
            if (iv.size != IV_SIZE) throw IOException("Invalid account session nonce")
            cipher.updateAAD(byteArrayOf(VERSION))
            val blob = byteArrayOf(VERSION) + iv + cipher.doFinal(json.toString().encodeToByteArray())
            if (blob.size > MAX_FILE_SIZE) throw IOException("Account session file exceeds size limit")
            val stream = file.startWrite()
            try {
                stream.write(blob)
                stream.fd.sync()
                file.finishWrite(stream)
            } catch (error: Exception) {
                file.failWrite(stream)
                throw error
            }
            // AtomicFile can log a failed rename, so confirm which bytes were committed.
            if (readFile()?.contentEquals(blob) != true) throw IOException("Account session was not saved")
        } catch (_: Exception) {
            throw IOException("Unable to save account session")
        }
    }

    @Synchronized
    fun clear() {
        try {
            file.delete()
            if (hasFile()) throw IOException("Account session file still exists")
        } catch (_: Exception) {
            throw IOException("Unable to clear account session")
        }
    }

    private fun readFile(): ByteArray? {
        val stream = try {
            file.openRead()
        } catch (error: FileNotFoundException) {
            if (hasFile()) throw error
            return null
        }
        return stream.use {
            if (it.channel.size() > MAX_FILE_SIZE) throw IOException("Account session file exceeds size limit")
            it.readBytes()
        }
    }

    private fun hasFile(): Boolean = file.baseFile.exists() ||
        File(file.baseFile.path + ".bak").exists() || File(file.baseFile.path + ".new").exists()

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun readKey(store: KeyStore): SecretKey? = store.getKey(keyAlias, null) as? SecretKey

    private fun generateKey(): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run {
        init(
                KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        generateKey()
    }

    private companion object {
        const val VERSION: Byte = 1
        const val IV_SIZE = 12
        const val HEADER_SIZE = 1 + IV_SIZE
        const val TAG_SIZE = 16
        const val MAX_FILE_SIZE = 64 * 1024
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
