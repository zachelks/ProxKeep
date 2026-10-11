package io.github.zachelks.proxkeep.core.api.auth

import io.github.zachelks.proxkeep.core.api.json.PveJson
import io.github.zachelks.proxkeep.core.api.json.parseLenientBoolean
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLDecoder

/** A second factor that can be answered by typing a code. */
enum class SecondFactorKind(internal val apiName: String) {
    Totp("totp"),
    Recovery("recovery"),
    Yubico("yubico"),
}

/**
 * The server's request for a second factor.
 *
 * When an account has two-factor authentication, the first login step returns
 * a ticket of the form `PVE:!tfa!<url-encoded JSON>:<time>::<signature>`
 * instead of a usable ticket. The JSON names the factors the account has.
 *
 * @property ticket the challenge ticket, sent back with the second factor
 */
data class TfaChallenge(
    val ticket: String,
    val totp: Boolean,
    val recovery: Boolean,
    val yubico: Boolean,
    val webauthn: Boolean,
) {
    /** Factors this app can answer natively, in the order to offer them. */
    val codeKinds: List<SecondFactorKind>
        get() = buildList {
            if (totp) add(SecondFactorKind.Totp)
            if (yubico) add(SecondFactorKind.Yubico)
            if (recovery) add(SecondFactorKind.Recovery)
        }

    override fun toString(): String =
        "TfaChallenge(totp=$totp, recovery=$recovery, yubico=$yubico, webauthn=$webauthn)"

    companion object {
        private const val PREFIX = "PVE:!tfa!"

        /** Returns the challenge carried by [ticket], or null if it is an ordinary ticket. */
        fun fromTicket(ticket: String): TfaChallenge? {
            if (!ticket.startsWith(PREFIX)) return null
            val encoded = ticket.substring(PREFIX.length).substringBefore(':')
            val fields = decode(encoded)
            return TfaChallenge(
                ticket = ticket,
                totp = fields.isOffered("totp"),
                recovery = fields.isOffered("recovery"),
                yubico = fields.isOffered("yubico"),
                webauthn = fields.isOffered("webauthn"),
            )
        }

        private fun decode(encoded: String): JsonObject? = try {
            // URLDecoder treats '+' as a space; keep a literal plus intact.
            val json = URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8")
            PveJson.parseToJsonElement(json) as? JsonObject
        } catch (ignored: SerializationException) {
            null
        } catch (ignored: IllegalArgumentException) {
            null
        }

        private fun JsonObject?.isOffered(key: String): Boolean {
            val value: JsonElement = this?.get(key) ?: return false
            return when (value) {
                is JsonNull -> false
                is JsonPrimitive -> parseLenientBoolean(value.content) ?: value.content.isNotEmpty()
                is JsonArray -> value.isNotEmpty()
                is JsonObject -> true
            }
        }
    }
}

/** Outcome of a password login. */
sealed interface LoginResult {
    data class Success(
        val credentials: TicketCredentials,
        val clusterName: String?,
    ) : LoginResult

    /** The password was accepted and a second factor is needed to finish. */
    data class SecondFactorRequired(
        val username: String,
        val challenge: TfaChallenge,
    ) : LoginResult
}
