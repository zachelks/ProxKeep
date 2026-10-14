package io.github.zachelks.proxkeep.core.api

/**
 * The Proxmox products the app administers. They share one API style, but
 * name their ticket cookie and API token header differently, listen on
 * different ports, and publish their API schema at different paths.
 *
 * @property ticketPrefix how the product's tickets start, such as `PVE` in `PVE:root@pam:…`
 * @property tokenSeparator what separates an API token's ID from its secret in the header
 * @property schemaPath where the product's API viewer keeps its schema, relative to `/api2/json/`
 */
enum class Product(
    val ticketPrefix: String,
    val cookieName: String,
    val tokenHeader: String,
    val tokenSeparator: Char,
    val defaultPort: Int,
    val schemaPath: String,
) {
    /** Proxmox Virtual Environment. */
    Pve("PVE", "PVEAuthCookie", "PVEAPIToken", '=', 8006, "../../pve-docs/api-viewer/apidata.js"),

    /** Proxmox Backup Server. */
    Pbs("PBS", "PBSAuthCookie", "PBSAPIToken", ':', 8007, "../../docs/api-viewer/apidata.js"),

    /** Proxmox Datacenter Manager. */
    Pdm("PDM", "PDMAuthCookie", "PDMAPIToken", ':', 8443, "../../docs/api-viewer/apidata.js"),
    ;

    /** The `Authorization` header value for an API token. */
    fun tokenAuthorization(tokenId: String, secret: String): String = "$tokenHeader=$tokenId$tokenSeparator$secret"

    companion object {
        /** The product that usually listens on [port], or Proxmox VE. */
        fun forPort(port: Int): Product = entries.firstOrNull { it.defaultPort == port } ?: Pve
    }
}
