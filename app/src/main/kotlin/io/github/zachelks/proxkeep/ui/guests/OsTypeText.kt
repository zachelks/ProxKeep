package io.github.zachelks.proxkeep.ui.guests

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.zachelks.proxkeep.R

/**
 * Names for the server's `ostype` codes. Virtual machines use version
 * families such as `win10`; containers use distribution names.
 */
private val osTypeNames = mapOf(
    "other" to R.string.os_other,
    "wxp" to R.string.os_wxp,
    "w2k" to R.string.os_w2k,
    "w2k3" to R.string.os_w2k3,
    "w2k8" to R.string.os_w2k8,
    "wvista" to R.string.os_wvista,
    "win7" to R.string.os_win7,
    "win8" to R.string.os_win8,
    "win10" to R.string.os_win10,
    "win11" to R.string.os_win11,
    "l24" to R.string.os_l24,
    "l26" to R.string.os_l26,
    "solaris" to R.string.os_solaris,
    "unmanaged" to R.string.os_unmanaged,
    "debian" to R.string.os_debian,
    "devuan" to R.string.os_devuan,
    "ubuntu" to R.string.os_ubuntu,
    "centos" to R.string.os_centos,
    "fedora" to R.string.os_fedora,
    "opensuse" to R.string.os_opensuse,
    "archlinux" to R.string.os_archlinux,
    "alpine" to R.string.os_alpine,
    "gentoo" to R.string.os_gentoo,
    "nixos" to R.string.os_nixos,
)

/** A readable name for an `ostype` code, or the code itself if it is not a known one. */
@Composable
fun osTypeLabel(code: String): String {
    val name = osTypeNames[code]
    return if (name != null) stringResource(name) else code
}
