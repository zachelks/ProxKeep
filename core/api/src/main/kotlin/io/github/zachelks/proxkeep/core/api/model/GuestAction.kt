package io.github.zachelks.proxkeep.core.api.model

/**
 * A power action on a virtual machine or container.
 *
 * @property apiName the last path segment of the action's API call
 * @property vmOnly true for actions containers do not have
 * @property isForceful true when the guest gets no chance to shut down
 *   cleanly, so unsaved data inside it can be lost
 */
enum class GuestAction(
    val apiName: String,
    val vmOnly: Boolean = false,
    val isForceful: Boolean = false,
) {
    Start("start"),
    Shutdown("shutdown"),
    Reboot("reboot"),
    Pause("suspend", vmOnly = true),
    Resume("resume", vmOnly = true),
    Stop("stop", isForceful = true),
    Reset("reset", vmOnly = true, isForceful = true),
    ;

    fun supports(kind: ResourceKind): Boolean =
        kind == ResourceKind.Qemu || (kind == ResourceKind.Lxc && !vmOnly)

    companion object {
        /** The actions that make sense for a guest in the given state, gentlest first. */
        fun available(
            kind: ResourceKind,
            isRunning: Boolean,
            isPaused: Boolean,
            isTemplate: Boolean,
        ): List<GuestAction> {
            val candidates = when {
                isTemplate -> emptyList()
                !isRunning -> listOf(Start)
                isPaused -> listOf(Resume, Stop)
                else -> listOf(Shutdown, Reboot, Pause, Stop, Reset)
            }
            return candidates.filter { it.supports(kind) }
        }
    }
}
