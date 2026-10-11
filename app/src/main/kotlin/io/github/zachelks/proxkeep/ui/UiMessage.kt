package io.github.zachelks.proxkeep.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.PveApiException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * A message for a person: a fixed, translatable sentence plus an optional
 * detail that comes from the server or the system as-is.
 */
data class UiMessage(
    @StringRes val id: Int,
    val detail: String? = null,
)

@Composable
fun UiMessage.text(): String {
    val base = stringResource(id)
    return if (detail.isNullOrBlank()) base else "$base\n${detail.trim()}"
}

fun Throwable.toUiMessage(): UiMessage = when (this) {
    is PveApiException -> when {
        isAuthenticationFailure -> UiMessage(R.string.error_sign_in_rejected)
        isPermissionDenied -> UiMessage(R.string.error_permission_denied, statusMessage)
        else -> UiMessage(R.string.error_server_response, "$statusCode ${statusMessage.trim()}")
    }
    is UnknownHostException -> UiMessage(R.string.error_unknown_host)
    is SocketTimeoutException, is ConnectException, is NoRouteToHostException ->
        UiMessage(R.string.error_unreachable)
    is SSLException -> UiMessage(R.string.error_secure_connection, message)
    else -> UiMessage(R.string.error_generic, message)
}
