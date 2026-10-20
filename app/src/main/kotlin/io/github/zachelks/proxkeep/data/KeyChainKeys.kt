package io.github.zachelks.proxkeep.data

import android.content.Context
import android.security.KeyChain
import android.security.KeyChainException
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509KeyManager

/**
 * Client certificates from Android's KeyChain. The person picks one in the
 * system's own chooser, which also grants the app access to it; the private
 * key never leaves the system.
 */
class KeyChainKeys(context: Context) : ClientKeys {
    private val appContext = context.applicationContext

    override fun keyManager(alias: String): X509KeyManager? = try {
        val key = KeyChain.getPrivateKey(appContext, alias)
        val chain = KeyChain.getCertificateChain(appContext, alias)
        if (key == null || chain.isNullOrEmpty()) null else SingleKeyManager(alias, key, chain)
    } catch (e: KeyChainException) {
        null
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    }

    /** Presents one certificate to every server that asks. */
    private class SingleKeyManager(
        private val alias: String,
        private val key: PrivateKey,
        private val chain: Array<X509Certificate>,
    ) : X509ExtendedKeyManager() {
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = alias

        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = alias

        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(alias)

        override fun getCertificateChain(alias: String?): Array<X509Certificate>? = chain.takeIf { alias == this.alias }

        override fun getPrivateKey(alias: String?): PrivateKey? = key.takeIf { alias == this.alias }

        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    }
}
