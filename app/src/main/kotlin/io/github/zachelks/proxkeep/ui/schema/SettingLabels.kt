package io.github.zachelks.proxkeep.ui.schema

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.schema.ParamSchema

/**
 * A readable name for a setting. The common ones have names of their own;
 * the rest show their key, which is what the server's documentation uses.
 */
@Composable
fun settingLabel(key: String, schema: ParamSchema? = null): String {
    LABELS[key]?.let { return stringResource(it) }
    val base = schema?.takeIf { it.isIndexed }?.baseName
    if (base != null) {
        val index = key.removePrefix(base).toIntOrNull()
        val name = LABELS["$base[n]"]
        if (index != null && name != null) return stringResource(R.string.setting_numbered, stringResource(name), index)
    }
    return key
}

private val LABELS: Map<String, Int> = mapOf(
    "name" to R.string.setting_name,
    "hostname" to R.string.setting_hostname,
    "description" to R.string.setting_description,
    "tags" to R.string.setting_tags,
    "onboot" to R.string.setting_onboot,
    "startup" to R.string.setting_startup,
    "ostype" to R.string.setting_ostype,
    "boot" to R.string.setting_boot,
    "protection" to R.string.setting_protection,
    "agent" to R.string.setting_agent,
    "hotplug" to R.string.setting_hotplug,
    "tablet" to R.string.setting_tablet,
    "acpi" to R.string.setting_acpi,
    "kvm" to R.string.setting_kvm,
    "localtime" to R.string.setting_localtime,
    "keyboard" to R.string.setting_keyboard,
    "numa" to R.string.setting_numa,
    "unprivileged" to R.string.setting_unprivileged,
    "features" to R.string.setting_features,
    "nameserver" to R.string.setting_nameserver,
    "searchdomain" to R.string.setting_searchdomain,
    "timezone" to R.string.setting_timezone,
    "console" to R.string.setting_console,
    "tty" to R.string.setting_tty,
    "cmode" to R.string.setting_cmode,
    "arch" to R.string.setting_arch,
    "ciuser" to R.string.setting_ciuser,
    "cipassword" to R.string.setting_cipassword,
    "citype" to R.string.setting_citype,
    "ciupgrade" to R.string.setting_ciupgrade,
    "sshkeys" to R.string.setting_sshkeys,
    "ipconfig[n]" to R.string.setting_ipconfig,
    "serial[n]" to R.string.setting_serial,
    "usb[n]" to R.string.setting_usb,
    "hostpci[n]" to R.string.setting_hostpci,
    "parallel[n]" to R.string.setting_parallel,
    "unused[n]" to R.string.setting_unused,
    "vga" to R.string.setting_vga,
    "machine" to R.string.setting_machine,
    "bios" to R.string.setting_bios,
    "scsihw" to R.string.setting_scsihw,
)
