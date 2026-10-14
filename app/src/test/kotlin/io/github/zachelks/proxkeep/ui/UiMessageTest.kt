package io.github.zachelks.proxkeep.ui

import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.PveApiException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals

class UiMessageTest {

    @Test
    fun serverAnswersAreExplained() {
        assertEquals(UiMessage(R.string.error_sign_in_rejected), PveApiException(401, "authentication failure").toUiMessage())
        assertEquals(
            UiMessage(R.string.error_permission_denied, "Permission check failed (/vms/100, VM.PowerMgmt)"),
            PveApiException(403, "Permission check failed (/vms/100, VM.PowerMgmt)").toUiMessage(),
        )
        assertEquals(UiMessage(R.string.error_server_response, "500 Internal error"), PveApiException(500, "Internal error ").toUiMessage())
    }

    @Test
    fun networkFailuresAreExplained() {
        assertEquals(UiMessage(R.string.error_unknown_host), UnknownHostException("pve.example").toUiMessage())
        assertEquals(UiMessage(R.string.error_unreachable), SocketTimeoutException().toUiMessage())
        assertEquals(UiMessage(R.string.error_unreachable), ConnectException("refused").toUiMessage())
        assertEquals(UiMessage(R.string.error_unreachable), NoRouteToHostException().toUiMessage())
        assertEquals(UiMessage(R.string.error_secure_connection, "bad certificate"), SSLHandshakeException("bad certificate").toUiMessage())
    }

    @Test
    fun anythingElseKeepsItsOwnMessage() {
        assertEquals(UiMessage(R.string.error_generic, "disk full"), IOException("disk full").toUiMessage())
    }
}
