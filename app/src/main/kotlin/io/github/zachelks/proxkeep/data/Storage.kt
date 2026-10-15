package io.github.zachelks.proxkeep.data

import java.io.IOException
import java.security.GeneralSecurityException
import javax.net.ssl.X509KeyManager

// What ServerRepository needs from the device. The Android implementations
// are ProfileStore, SecretBox and KeyChainKeys; tests use in-memory ones.

/** Where the saved servers are kept. */
interface ProfileStorage {
    /** Returns what is saved, or an empty state if nothing readable is there. */
    suspend fun read(): StoredServers

    @Throws(IOException::class)
    suspend fun write(state: StoredServers)
}

/** Encrypts small secrets so they can be stored. */
interface SecretCipher {
    /** Returns text that only [decrypt] on this device can turn back into [plainText]. */
    @Throws(GeneralSecurityException::class)
    fun encrypt(plainText: String): String

    /** Returns the secret, or null if [encoded] cannot be decrypted on this device. */
    fun decrypt(encoded: String): String?
}

/** Client certificates installed on the device, for servers behind a reverse proxy that asks for one. */
fun interface ClientKeys {
    /** Returns a key manager presenting the certificate under [alias], or null if it is gone or not allowed. Blocks. */
    fun keyManager(alias: String): X509KeyManager?

    companion object {
        /** For devices, and tests, without client certificates. */
        val None = ClientKeys { null }
    }
}
