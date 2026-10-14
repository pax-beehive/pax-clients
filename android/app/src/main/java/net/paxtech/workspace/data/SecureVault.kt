package net.paxtech.workspace.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

interface Vault {
    suspend fun read(): VaultData

    suspend fun update(change: (VaultData) -> VaultData): VaultData
}

@Singleton
class SecureVault @Inject constructor(@ApplicationContext context: Context) : Vault {
    private val file = AtomicFile(File(context.noBackupFilesDir, "account.v1"))
    private val mutex = Mutex()
    private val alias = "pax-workspace-vault-v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let {
            return it
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                            alias,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
            }
            .generateKey()
    }

    private fun load(): VaultData {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists())
            return VaultData()
        val bytes = file.readFully()
        require(bytes.size >= 29 && bytes[0] == 1.toByte()) { "Invalid secure account storage" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        cipher.updateAAD(alias.toByteArray())
        return wireJson.decodeFromString(
            String(cipher.doFinal(bytes.copyOfRange(13, bytes.size)), Charsets.UTF_8)
        )
    }

    override suspend fun read() = withContext(Dispatchers.IO) { mutex.withLock { load() } }

    override suspend fun update(change: (VaultData) -> VaultData) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val next = change(load())
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key())
                cipher.updateAAD(alias.toByteArray())
                val encrypted =
                    byteArrayOf(1) +
                        cipher.iv +
                        cipher.doFinal(wireJson.encodeToString(next).toByteArray())
                val stream = file.startWrite()
                try {
                    stream.write(encrypted)
                    file.finishWrite(stream)
                } catch (e: Exception) {
                    file.failWrite(stream)
                    throw e
                }
                next
            }
        }
}
