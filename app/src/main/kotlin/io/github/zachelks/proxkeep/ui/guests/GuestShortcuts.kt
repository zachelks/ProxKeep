package io.github.zachelks.proxkeep.ui.guests

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import io.github.zachelks.proxkeep.MainActivity
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.model.ClusterResource

/**
 * Offers a guest the person opened as a launcher shortcut, so recent
 * guests are a long press on the app icon away. Android keeps the most
 * recent few and drops the rest.
 */
fun pushGuestShortcut(context: Context, profileId: String, serverName: String, guest: ClusterResource) {
    val name = guest.name ?: guest.vmid?.toString() ?: guest.id
    val intent = Intent(context, MainActivity::class.java)
        .setAction(MainActivity.ACTION_OPEN_GUEST)
        .putExtra(MainActivity.EXTRA_PROFILE_ID, profileId)
        .putExtra(MainActivity.EXTRA_RESOURCE_ID, guest.id)
    val shortcut = ShortcutInfoCompat.Builder(context, "guest:$profileId:${guest.id}")
        .setShortLabel(name)
        .setLongLabel(context.getString(R.string.shortcut_guest_long, name, serverName))
        .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_guest))
        .setIntent(intent)
        .build()
    ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
}

/** Removes the shortcuts to a server's guests, for example when the server is removed. */
fun removeGuestShortcuts(context: Context, profileId: String) {
    val ids = ShortcutManagerCompat.getDynamicShortcuts(context).map { it.id }.filter { it.startsWith("guest:$profileId:") }
    if (ids.isNotEmpty()) ShortcutManagerCompat.removeDynamicShortcuts(context, ids)
}
