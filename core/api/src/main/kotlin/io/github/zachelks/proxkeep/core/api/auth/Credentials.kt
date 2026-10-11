package io.github.zachelks.proxkeep.core.api.auth

/** What a request presents to prove who is asking. */
sealed interface Credentials

/**
 * A login ticket. Tickets expire two hours after they are issued and can be
 * renewed before then.
 *
 * @property username the full user ID, for example `root@pam`
 * @property csrfToken sent with every request that changes something
 */
data class TicketCredentials(
    val username: String,
    val ticket: String,
    val csrfToken: String?,
) : Credentials {
    override fun toString(): String = "TicketCredentials(username=$username)"
}

/**
 * An API token.
 *
 * @property tokenId the full token ID, for example `root@pam!phone`
 */
data class ApiTokenCredentials(
    val tokenId: String,
    val secret: String,
) : Credentials {
    /** The user the token belongs to, for example `root@pam`. */
    val username: String
        get() = tokenId.substringBefore('!')

    override fun toString(): String = "ApiTokenCredentials(tokenId=$tokenId)"

    companion object {
        /** Whether [text] has the `user@realm!name` shape of a token ID. */
        fun isValidTokenId(text: String): Boolean {
            val user = text.substringBefore('!', missingDelimiterValue = "")
            val name = text.substringAfter('!', missingDelimiterValue = "")
            return user.contains('@') && !user.startsWith('@') && !user.endsWith('@') && name.isNotBlank()
        }
    }
}
