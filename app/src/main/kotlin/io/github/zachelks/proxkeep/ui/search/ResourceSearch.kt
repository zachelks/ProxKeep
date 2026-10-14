package io.github.zachelks.proxkeep.ui.search

import io.github.zachelks.proxkeep.core.api.model.ClusterResource
import io.github.zachelks.proxkeep.core.api.model.ResourceKind

/** The name a resource is listed under. */
val ClusterResource.title: String
    get() = when (kind) {
        ResourceKind.Node -> node ?: id
        ResourceKind.Storage -> storage ?: id
        else -> name ?: vmid?.toString() ?: id
    }

/**
 * Guests, nodes and storage matching [query], best matches first.
 *
 * A match on what the resource is called or its guest ID ranks above a match
 * on where it lives, its pool or its tags. Within the same rank, guests come
 * before nodes and nodes before storage.
 */
fun searchResources(resources: List<ClusterResource>, query: String): List<ClusterResource> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return emptyList()
    return resources
        .mapNotNull { resource -> resource.matchRank(needle)?.let { rank -> resource to rank } }
        .sortedWith(compareBy({ it.second }, { it.first.kindOrder }, { it.first.title.lowercase() }, { it.first.node }))
        .map { it.first }
}

private val ClusterResource.kindOrder: Int?
    get() = when (kind) {
        ResourceKind.Qemu, ResourceKind.Lxc -> 0
        ResourceKind.Node -> 1
        ResourceKind.Storage -> 2
        else -> null
    }

private fun ClusterResource.matchRank(needle: String): Int? {
    if (kindOrder == null) return null
    val own = listOfNotNull(title, vmid?.toString()).map { it.lowercase() }
    val related = (listOfNotNull(node, pool, plugintype) + tagList).map { it.lowercase() }
    return when {
        own.any { it == needle } -> 0
        own.any { it.startsWith(needle) } -> 1
        own.any { needle in it } -> 2
        related.any { needle in it } -> 3
        else -> null
    }
}
