package io.github.zachelks.proxkeep.core.api.model

import io.github.zachelks.proxkeep.core.api.json.LenientLongSerializer
import kotlinx.serialization.Serializable

/** Response of `GET /nodes/{node}/query-url-metadata`: what a download from a URL would fetch. */
@Serializable
data class UrlMetadata(
    val filename: String? = null,
    @Serializable(with = LenientLongSerializer::class)
    val size: Long? = null,
    val mimetype: String? = null,
)

/**
 * One row of `GET /nodes/{node}/aplinfo`: a container template the node
 * can download from the Proxmox template catalog.
 *
 * @property template the file name to ask for
 * @property section such as `system` or `turnkeylinux`
 */
@Serializable
data class ApplianceTemplate(
    val template: String,
    val headline: String? = null,
    val description: String? = null,
    val os: String? = null,
    val section: String? = null,
    val version: String? = null,
    val `package`: String? = null,
)

/** Checksums a download from a URL can be verified with. */
enum class ChecksumAlgorithm(internal val apiName: String) {
    Md5("md5"),
    Sha1("sha1"),
    Sha224("sha224"),
    Sha256("sha256"),
    Sha384("sha384"),
    Sha512("sha512"),
}

/** How an upload is going: bytes sent of [total]. */
fun interface UploadProgress {
    fun onProgress(sent: Long, total: Long)
}
