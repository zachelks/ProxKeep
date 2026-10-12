package io.github.zachelks.proxkeep.data

import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/** Reads and writes the saved servers as one JSON file in app-private storage. */
class ProfileStore(private val file: File) : ProfileStorage {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun read(): StoredServers = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext StoredServers()
        try {
            val text = AtomicFile(file).readFully().toString(Charsets.UTF_8)
            json.decodeFromString(StoredServers.serializer(), text)
        } catch (e: IOException) {
            StoredServers()
        } catch (e: SerializationException) {
            StoredServers()
        }
    }

    /** Replaces the file in one step, so a crash cannot leave it half written. */
    @Throws(IOException::class)
    override suspend fun write(state: StoredServers): Unit = withContext(Dispatchers.IO) {
        val atomicFile = AtomicFile(file)
        val stream = atomicFile.startWrite()
        try {
            stream.write(json.encodeToString(StoredServers.serializer(), state).toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(stream)
        } catch (e: IOException) {
            atomicFile.failWrite(stream)
            throw e
        }
    }
}
