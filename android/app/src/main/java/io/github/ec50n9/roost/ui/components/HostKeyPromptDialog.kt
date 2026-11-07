package io.github.ec50n9.roost.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import io.github.ec50n9.roost.R
import io.github.ec50n9.roost.service.terminal.HostKeyPrompt
import io.github.ec50n9.roost.ui.theme.ChuTypography

/**
 * The one host-key decision dialog. Both the server list (a link probe) and the terminal (a
 * session connect) hit the same verification prompt, so it lives here instead of in either screen.
 */
@Composable
fun HostKeyPromptDialog(
    prompt: HostKeyPrompt?,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    if (prompt == null) return
    val context = LocalContext.current
    val typography = ChuTypography.current
    ChuDialog(
        onDismiss = onReject,
        title = stringResource(R.string.terminal_hostkey_title),
        confirmLabel = stringResource(R.string.action_accept),
        dismissLabel = stringResource(R.string.action_reject),
        onConfirm = onAccept,
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        val message = buildString {
            append(
                context.getString(
                    R.string.terminal_hostkey_host,
                    prompt.host,
                    prompt.port.toString(),
                )
            )
            append('\n')
            append(context.getString(R.string.terminal_hostkey_algorithm, prompt.algorithm))
            append('\n')
            prompt.previousFingerprint?.let { previous ->
                append(context.getString(R.string.terminal_hostkey_changed_warning))
                append('\n')
                append(context.getString(R.string.terminal_hostkey_old, previous))
                append('\n')
            }
            append(context.getString(R.string.terminal_hostkey_new, prompt.fingerprint))
        }
        ChuText(message, style = typography.body, modifier = Modifier.fillMaxWidth())
    }
}
