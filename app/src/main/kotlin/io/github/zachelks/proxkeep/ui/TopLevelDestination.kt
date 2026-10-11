package io.github.zachelks.proxkeep.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.github.zachelks.proxkeep.R

/** The four top-level destinations from the product spec's screen map. */
enum class TopLevelDestination(
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
    @StringRes val emptyMessage: Int,
) {
    Overview(
        label = R.string.destination_overview,
        icon = R.drawable.ic_destination_overview,
        emptyMessage = R.string.empty_overview,
    ),
    Guests(
        label = R.string.destination_guests,
        icon = R.drawable.ic_destination_guests,
        emptyMessage = R.string.empty_guests,
    ),
    Infrastructure(
        label = R.string.destination_infrastructure,
        icon = R.drawable.ic_destination_infrastructure,
        emptyMessage = R.string.empty_infrastructure,
    ),
    Tasks(
        label = R.string.destination_tasks,
        icon = R.drawable.ic_destination_tasks,
        emptyMessage = R.string.empty_tasks,
    ),
}
