package io.github.zachelks.proxkeep.ui.addserver

import androidx.annotation.StringRes
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.Product

/** The product's name, as the person picks it when adding a server. */
@get:StringRes
val Product.label: Int
    get() = when (this) {
        Product.Pve -> R.string.product_pve
        Product.Pbs -> R.string.product_pbs
        Product.Pdm -> R.string.product_pdm
    }
