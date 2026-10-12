package io.github.zachelks.proxkeep.data

import java.io.IOException
import java.security.GeneralSecurityException

// What ServerRepository needs from the device. The Android implementations
// are ProfileStore and SecretBox; tests use in-memory ones.

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
