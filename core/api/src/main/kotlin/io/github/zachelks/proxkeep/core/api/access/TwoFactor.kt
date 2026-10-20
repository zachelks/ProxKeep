package io.github.zachelks.proxkeep.core.api.access

import io.github.zachelks.proxkeep.core.api.PveClient
import io.github.zachelks.proxkeep.core.api.encodePathValue
import io.github.zachelks.proxkeep.core.api.json.LenientBooleanSerializer
import io.github.zachelks.proxkeep.core.api.json.LenientLongSerializer
import io.github.zachelks.proxkeep.core.api.json.PveJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder
import java.security.SecureRandom

/**
 * One second factor of a user, from `GET /access/tfa`.
 *
 * @property type `totp`, `webauthn`, `recovery`, `yubico` or `u2f`
 * @property created when it was added, in seconds since the epoch
 */
@Serializable
data class TfaEntry(
    val id: String,
    val type: String,
    val description: String? = null,
    @Serializable(with = LenientLongSerializer::class)
    val created: Long? = null,
    @Serializable(with = LenientBooleanSerializer::class)
    val enable: Boolean = true,
)

/**
 * A user's second factors, and whether too many wrong codes have locked
 * them out of their second factors for now.
 *
 * @property tfaLockedUntil when the lock on every second factor ends, in seconds since the epoch
 * @property totpLocked whether codes from an authenticator app are refused until an administrator unlocks them
 */
@Serializable
data class UserTfa(
    val userid: String,
    val entries: List<TfaEntry> = emptyList(),
    @SerialName("tfa-locked-until")
    @Serializable(with = LenientLongSerializer::class)
    val tfaLockedUntil: Long? = null,
    @SerialName("totp-locked")
    @Serializable(with = LenientBooleanSerializer::class)
    val totpLocked: Boolean = false,
) {
    fun isLocked(nowSeconds: Long): Boolean = totpLocked || (tfaLockedUntil ?: 0) > nowSeconds
}

/** Every user's second factors that the credentials may see. */
suspend fun PveClient.tfaUsers(): List<UserTfa> =
    PveJson.decodeFromJsonElement(ListSerializer(UserTfa.serializer()), read("/access/tfa"))

private fun userPath(userid: String) = "/access/tfa/${encodePathValue(userid)}"

/**
 * The server asks everyone but `root@pam` to confirm a change to second
 * factors with their own password.
 */
private fun MutableMap<String, String>.password(password: String?) {
    if (!password.isNullOrEmpty()) put("password", password)
}

/**
 * Adds an authenticator app, once the person has entered [code] from it,
 * so a mistyped secret never locks them out.
 *
 * @param uri the `otpauth://totp/` URI holding the secret, from [Totp.uri]
 */
suspend fun PveClient.addTotp(userid: String, description: String, uri: String, code: String, password: String?) {
    val form = mutableMapOf("type" to "totp", "totp" to uri, "value" to code)
    if (description.isNotBlank()) form["description"] = description
    form.password(password)
    writeForData("POST", userPath(userid), form)
}

/**
 * Creates a user's recovery keys and returns them. They are shown only
 * this once; a user has at most one set.
 */
suspend fun PveClient.addRecoveryKeys(userid: String, description: String, password: String?): List<String> {
    val form = mutableMapOf("type" to "recovery")
    if (description.isNotBlank()) form["description"] = description
    form.password(password)
    val reply = writeForData("POST", userPath(userid), form) as? JsonObject
    return (reply?.get("recovery") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
}

suspend fun PveClient.setTfaEnabled(userid: String, id: String, enable: Boolean, password: String?) {
    val form = mutableMapOf("enable" to if (enable) "1" else "0")
    form.password(password)
    write("PUT", "${userPath(userid)}/${encodePathValue(id)}", form)
}

suspend fun PveClient.deleteTfa(userid: String, id: String, password: String?) {
    val form = mutableMapOf<String, String>()
    form.password(password)
    write("DELETE", "${userPath(userid)}/${encodePathValue(id)}", form)
}

/** Lifts the lock that too many wrong second-factor codes put on a user. */
suspend fun PveClient.unlockTfa(userid: String) {
    write("PUT", "/access/users/${encodePathValue(userid)}/unlock-tfa", emptyMap())
}

/**
 * A new secret for an authenticator app, as RFC 6238 describes: 30-second
 * steps, six digits, SHA-1, which every authenticator app supports.
 *
 * @property secret the secret in base32, as authenticator apps take it typed in
 */
class Totp(val secret: String) {

    /** The secret in groups of four, easier to type. */
    val grouped: String
        get() = secret.chunked(4).joinToString(" ")

    /**
     * The URI an authenticator app reads. [issuer] names the server in the
     * app's list, beside [account].
     */
    fun uri(issuer: String, account: String): String {
        val label = encode("$issuer:$account")
        return "otpauth://totp/$label?secret=$secret&issuer=${encode(issuer)}&algorithm=SHA1&digits=6&period=30"
    }

    companion object {
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        private const val SECRET_BYTES = 20

        /** A random 160-bit secret, the length RFC 4226 recommends. */
        fun generate(random: SecureRandom = SecureRandom()): Totp = Totp(base32(ByteArray(SECRET_BYTES).also(random::nextBytes)))

        fun base32(bytes: ByteArray): String = buildString {
            var buffer = 0
            var bits = 0
            for (byte in bytes) {
                buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
                bits += 8
                while (bits >= 5) {
                    append(ALPHABET[(buffer shr (bits - 5)) and 0x1F])
                    bits -= 5
                }
            }
            if (bits > 0) append(ALPHABET[(buffer shl (5 - bits)) and 0x1F])
        }

        private fun encode(text: String): String = URLEncoder.encode(text, Charsets.UTF_8).replace("+", "%20")
    }
}
