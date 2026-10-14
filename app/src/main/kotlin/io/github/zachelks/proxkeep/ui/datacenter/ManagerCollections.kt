package io.github.zachelks.proxkeep.ui.datacenter

import io.github.zachelks.proxkeep.R

/** The collections of a Proxmox Datacenter Manager. Its server administration shares the Backup Server's. */
internal val managerCollections: List<CollectionSpec> = listOf(
    CollectionSpec(
        id = "pdm-remotes", title = R.string.pdm_remotes, listPath = "/remotes", idKey = "id",
        titleKeys = listOf("id"), detailKeys = listOf("type", "authid", "web-url"),
        itemPath = "/remotes/{id}", itemParam = "id", types = listOf("pve", "pbs"),
    ),
)
