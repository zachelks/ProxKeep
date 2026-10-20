package io.github.zachelks.proxkeep.core.api

/**
 * A task's ID, of the form `UPID:node:pid:pstart:starttime:type:id:user:`.
 * A Datacenter Manager relays a remote's tasks with the remote's name and
 * a `!` in front, as in `prod!UPID:pve1:…`.
 *
 * The user at the end may itself hold a `!`, as an API token's does
 * (`root@pam!ci`), so only a `!` directly before `UPID:` marks a remote.
 *
 * @property text the ID exactly as the server gave it, which is what task calls take
 * @property remote the Datacenter Manager remote the task ran on, or null
 * @property node the node the task runs on, where its status and log are read
 */
data class Upid(val text: String, val remote: String?, val node: String) {

    override fun toString(): String = text

    companion object {
        private const val PREFIX = "UPID:"

        /** Reads a task ID, or returns null when [text] is not one, such as a volume ID some calls return. */
        fun parse(text: String): Upid? {
            val bang = text.indexOf('!')
            val remote = if (!text.startsWith(PREFIX) && bang > 0 && text.startsWith(PREFIX, bang + 1)) text.substring(0, bang) else null
            val body = if (remote != null) text.substring(bang + 1) else text
            if (!body.startsWith(PREFIX)) return null
            val node = body.split(':').getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null
            return Upid(text, remote, node)
        }
    }
}
