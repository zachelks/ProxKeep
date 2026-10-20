package io.github.zachelks.proxkeep.data

import io.github.zachelks.proxkeep.core.api.schema.ApiSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * The signed-in server's API schema, which configuration forms are built
 * from.
 *
 * The schema is several megabytes, so it is downloaded once per server and
 * version and kept in [cacheDir]. A server upgrade changes the version and so
 * fetches the new schema.
 */
class SchemaRepository(
    private val servers: ServerRepository,
    private val cacheDir: File,
) {
    private val mutex = Mutex()
    private var loaded: Pair<String, ApiSchema>? = null

    /**
     * @throws io.github.zachelks.proxkeep.core.api.SchemaUnavailableException when the server does not publish it
     * @throws IOException when the server cannot be reached or sends something unreadable
     * @throws NotSignedInException when no server is signed in
     */
    suspend fun schema(): ApiSchema = mutex.withLock {
        val profileId = (servers.session.value as? Session.Active)?.profile?.id ?: throw NotSignedInException()
        val version = servers.request { it.version() }
        val key = "$profileId/${version.version}/${version.repoid.orEmpty()}"
        loaded?.takeIf { it.first == key }?.let { return it.second }

        val file = File(cacheDir, "${hash(profileId)}-${hash(key)}.js")
        val schema = withContext(Dispatchers.IO) {
            file.takeIf { it.isFile }?.let { cached ->
                try {
                    ApiSchema.parse(cached.readText())
                } catch (e: IllegalArgumentException) {
                    cached.delete()
                    null
                }
            }
        } ?: download(profileId, file)
        loaded = key to schema
        schema
    }

    private suspend fun download(profileId: String, file: File): ApiSchema {
        val text = servers.request { it.apiSchemaText() }
        return withContext(Dispatchers.Default) {
            val schema = try {
                ApiSchema.parse(text)
            } catch (e: IllegalArgumentException) {
                throw IOException("The server's API schema could not be read", e)
            }
            withContext(Dispatchers.IO) {
                // Older versions of this server's schema are no longer needed.
                val prefix = "${hash(profileId)}-"
                cacheDir.listFiles { candidate -> candidate.name.startsWith(prefix) }?.forEach { it.delete() }
                cacheDir.mkdirs()
                file.writeText(text)
            }
            schema
        }
    }

    private companion object {
        /**
         * Files are named `<hash of profile>-<hash of profile and version>.js`,
         * so a server's older files can be found and replaced.
         */
        fun hash(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
    }
}
