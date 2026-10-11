package io.github.zachelks.proxkeep.core.api

import io.github.zachelks.proxkeep.core.api.model.Realm
import io.github.zachelks.proxkeep.core.api.tls.CertificateInfo
import io.github.zachelks.proxkeep.core.api.tls.ServerTrust
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.io.IOException

/** What a first contact with a server found. */
sealed interface ProbeResult {
    /**
     * The server answered over a connection that passed certificate checks.
     *
     * @property certificate the certificate it presented, when known
     */
    data class Reachable(
        val realms: List<Realm>,
        val certificate: CertificateInfo?,
    ) : ProbeResult

    /**
     * The server presented a certificate that is neither trusted by the
     * platform nor pinned. Nothing was sent to it. The person can compare
     * [certificate] with the server's own and choose to pin it.
     */
    data class UntrustedCertificate(
        val certificate: CertificateInfo,
    ) : ProbeResult

    /** No usable answer: wrong address, network down, or not a Proxmox VE server. */
    data class Failed(
        val cause: IOException,
    ) : ProbeResult
}

/**
 * Contacts a server for the first time, or re-checks one with a pinned
 * certificate, by asking for its realm list, the one call that needs no
 * credentials.
 */
suspend fun probeServer(
    baseUrl: HttpUrl,
    http: OkHttpClient,
    pinnedSha256: String? = null,
): ProbeResult {
    val trust = ServerTrust(pinnedSha256)
    val client = PveClient(baseUrl, trust.applyTo(http))
    return try {
        val realms = client.realms()
        ProbeResult.Reachable(realms, trust.lastServerCertificate?.let(CertificateInfo::from))
    } catch (e: IOException) {
        val rejected = trust.rejectedCertificate
        if (rejected != null) ProbeResult.UntrustedCertificate(CertificateInfo.from(rejected)) else ProbeResult.Failed(e)
    }
}
