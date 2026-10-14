package io.github.zachelks.proxkeep.ui.addserver

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.SslCertificate
import android.net.http.SslError
import android.os.Build
import android.security.KeyChain
import android.security.KeyChainException
import android.webkit.ClientCertRequest
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import io.github.zachelks.proxkeep.core.api.auth.TicketCredentials
import io.github.zachelks.proxkeep.core.api.tls.Fingerprints
import kotlinx.coroutines.delay
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlin.concurrent.thread

/**
 * The server's own login page, for sign-in methods the app cannot do
 * natively: security keys and OpenID Connect realms.
 *
 * The page runs in a WebView with no cookies from before. As soon as it
 * holds a ticket, [onTicket] receives it, and the WebView's cookies and
 * storage are cleared again, so the session lives only in the app.
 *
 * @param pinnedFingerprint the certificate the server may present when the platform does not trust it
 * @param clientCertAlias the KeyChain certificate to present when the server or its proxy asks for one
 * @param onPageLost called if the page stops working, for example because its process ran out of memory
 * @param cookieName the cookie the product's login page keeps its ticket in
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebSignInPage(
    url: String,
    pinnedFingerprint: String?,
    clientCertAlias: String?,
    onTicket: (String) -> Unit,
    onPageLost: () -> Unit,
    modifier: Modifier = Modifier,
    cookieName: String = TicketCredentials.COOKIE_NAME,
) {
    val deliver by rememberUpdatedState(onTicket)
    val lost by rememberUpdatedState(onPageLost)
    LaunchedEffect(url) {
        while (true) {
            delay(COOKIE_POLL_MS)
            val ticket = ticketFromCookies(CookieManager.getInstance().getCookie(url), cookieName)
            if (ticket != null) {
                forgetWebSession()
                deliver(ticket)
                break
            }
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            forgetWebSession()
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // Security keys work in the page only where the server's domain vouches for the app.
                if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION)) {
                    WebSettingsCompat.setWebAuthenticationSupport(settings, WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_APP)
                }
                webViewClient = SignInClient(context.applicationContext, pinnedFingerprint, clientCertAlias) { lost() }
                loadUrl(url)
            }
        },
        onRelease = { webView ->
            webView.destroy()
            forgetWebSession()
        },
    )
}

// Lint reports this class although it overrides onRenderProcessGone below.
@SuppressLint("MissingOnRenderProcessGone")
private class SignInClient(
    private val context: Context,
    private val pinnedFingerprint: String?,
    private val clientCertAlias: String?,
    private val onPageLost: () -> Unit,
) : WebViewClient() {

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        // The WebView cannot be used again. Leaving the step removes and destroys it.
        onPageLost()
        return true
    }

    // Only the exact certificate the person pinned is accepted beyond what the platform trusts.
    @SuppressLint("WebViewClientOnReceivedSslError")
    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        if (matchesPin(error.certificate, pinnedFingerprint)) handler.proceed() else handler.cancel()
    }

    override fun onReceivedClientCertRequest(view: WebView, request: ClientCertRequest) {
        val alias = clientCertAlias ?: return request.ignore()
        // KeyChain blocks, so it is read off the main thread.
        thread(name = "client-certificate") {
            try {
                val key = KeyChain.getPrivateKey(context, alias)
                val chain = KeyChain.getCertificateChain(context, alias)
                if (key != null && chain != null) request.proceed(key, chain) else request.ignore()
            } catch (e: KeyChainException) {
                request.ignore()
            } catch (e: InterruptedException) {
                request.ignore()
            }
        }
    }
}

/** Removes every cookie and stored page, so no session outlives the sign-in. */
private fun forgetWebSession() {
    CookieManager.getInstance().removeAllCookies(null)
    CookieManager.getInstance().flush()
    WebStorage.getInstance().deleteAllData()
}

/** Whether [certificate] is the one whose fingerprint is [pinnedFingerprint]. */
internal fun matchesPin(certificate: SslCertificate?, pinnedFingerprint: String?): Boolean {
    val pin = pinnedFingerprint?.let(Fingerprints::normalize) ?: return false
    val x509 = certificate?.toX509() ?: return false
    return Fingerprints.sha256(x509) == pin
}

private fun SslCertificate.toX509(): X509Certificate? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return x509Certificate
    // Older versions only expose the encoded certificate through the saved state.
    val bytes = SslCertificate.saveState(this).getByteArray("x509-certificate") ?: return null
    return try {
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(bytes)) as? X509Certificate
    } catch (e: CertificateException) {
        null
    }
}

/** The usable ticket in a `Cookie` header value, or null if the page has not signed in yet. */
internal fun ticketFromCookies(cookies: String?, cookieName: String = TicketCredentials.COOKIE_NAME): String? = cookies
    ?.split(';')
    ?.map { it.trim() }
    ?.firstOrNull { it.startsWith("$cookieName=") }
    ?.substringAfter('=')
    ?.let { URLDecoder.decode(it, "UTF-8") }
    ?.takeIf { TicketCredentials.usernameFromTicket(it) != null }

private const val COOKIE_POLL_MS = 500L
