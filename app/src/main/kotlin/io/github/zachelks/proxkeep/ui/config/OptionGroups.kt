package io.github.zachelks.proxkeep.ui.config

import io.github.zachelks.proxkeep.core.api.model.ResourceKind
import io.github.zachelks.proxkeep.ui.schema.FormField
import io.github.zachelks.proxkeep.ui.schema.SchemaForm

/**
 * The options tab's fields: cloud-init settings when the guest has a
 * cloud-init drive, then the other options.
 *
 * @property hidden how many settings are left out until the person asks for all of them
 */
data class OptionGroups(val cloudInit: List<FormField>, val options: List<FormField>, val hidden: Int)

/** Cloud-init settings, by key or numbered key's base name. */
val CLOUD_INIT_KEYS = setOf("ciuser", "cipassword", "citype", "ciupgrade", "cicustom", "ipconfig", "nameserver", "searchdomain", "sshkeys")

private val COMMON_QEMU = listOf(
    "name", "onboot", "startup", "ostype", "boot", "agent", "protection", "tags", "description", "hotplug", "tablet",
    "bios", "machine", "vga", "scsihw", "numa", "localtime",
)

private val COMMON_LXC = listOf(
    "hostname", "onboot", "startup", "protection", "features", "nameserver", "searchdomain", "tags", "description",
    "console", "tty", "cmode", "timezone",
)

/**
 * Groups and orders the options: the common ones first in a fixed order,
 * then the rest by key. Unless [showAll] is set, only common options and
 * those that have or are given a value are shown.
 */
fun optionGroups(settings: GuestSettings, form: SchemaForm, showAll: Boolean): OptionGroups {
    val qemu = settings.kind == ResourceKind.Qemu
    val common = if (qemu) COMMON_QEMU else COMMON_LXC
    val isCloudInit = { field: FormField -> qemu && (field.key in CLOUD_INIT_KEYS || field.schema.baseName in CLOUD_INIT_KEYS) }
    val cloudInit = if (settings.hasCloudInit) form.fields.filter(isCloudInit) else emptyList()
    val rest = form.fields.filterNot { it in cloudInit || (qemu && !settings.hasCloudInit && isCloudInit(it) && form.value(it.key).isEmpty()) }
    val ordered = rest.sortedWith(compareBy({ common.indexOf(it.key).let { index -> if (index < 0) Int.MAX_VALUE else index } }, { it.key }))
    val visible = if (showAll) ordered else ordered.filter { it.key in common || form.initial[it.key].orEmpty().isNotEmpty() || form.isChanged(it.key) }
    return OptionGroups(cloudInit, visible, ordered.size - visible.size)
}
