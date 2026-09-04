package com.luckycatpaw.luckyfilestv.ui.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.data.source.smb.SmbCredentials
import com.luckycatpaw.luckyfilestv.data.source.smb.SmbShare
import com.luckycatpaw.luckyfilestv.ui.common.DialogCard
import com.luckycatpaw.luckyfilestv.ui.common.RequestInitialFocus
import com.luckycatpaw.luckyfilestv.ui.common.TvDialogButton
import com.luckycatpaw.luckyfilestv.ui.common.TvModalDialog
import com.luckycatpaw.luckyfilestv.ui.common.TvTextInput
import java.util.UUID

/** What the editor was opened for. */
internal sealed interface ShareEditorTarget {

    data object New : ShareEditorTarget

    data class Existing(val share: SmbShare) : ShareEditorTarget
}

/**
 * Form for a share, used for adding and for editing.
 *
 * Both cases show the same fields — only the title and the starting values differ, so
 * splitting them into two screens would mean maintaining the same form twice.
 *
 * Everything that is not needed to reach a server sits behind "advanced". Typing on a remote
 * control is slow enough that four fields already feel like work, and display name and
 * domain are empty for almost everyone.
 */
@Composable
internal fun ShareEditorOverlay(
    target: ShareEditorTarget,
    onSave: (SmbShare) -> Unit,
    onTest: (SmbShare, (Boolean, String) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    val existing = (target as? ShareEditorTarget.Existing)?.share
    val existingPassword = (existing?.credentials as? SmbCredentials.Password)?.password

    var host by remember(existing) { mutableStateOf(existing?.host.orEmpty()) }
    var shareName by remember(existing) { mutableStateOf(existing?.name.orEmpty()) }
    var user by remember(existing) {
        mutableStateOf((existing?.credentials as? SmbCredentials.Password)?.user.orEmpty())
    }
    var password by remember(existing) { mutableStateOf("") }
    var domain by remember(existing) {
        mutableStateOf((existing?.credentials as? SmbCredentials.Password)?.domain.orEmpty())
    }
    var displayName by remember(existing) { mutableStateOf(existing?.displayName.orEmpty()) }
    var guest by remember(existing) { mutableStateOf(existing?.credentials is SmbCredentials.Guest) }
    var advanced by remember { mutableStateOf(false) }
    var incomplete by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testMessage by remember { mutableStateOf<String?>(null) }
    var testFailed by remember { mutableStateOf(false) }

    val hostFocus = remember { FocusRequester() }
    val shareFocus = remember { FocusRequester() }
    val userFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val displayNameFocus = remember { FocusRequester() }
    val domainFocus = remember { FocusRequester() }
    val guestFocus = remember { FocusRequester() }

    // The chain is derived from what is actually on screen. Wiring each field to a fixed
    // neighbour would break the moment guest or advanced changes what is visible.
    val chain = buildList {
        add(hostFocus)
        add(shareFocus)

        if (!guest) {
            add(userFocus)
            add(passwordFocus)
        }

        if (advanced) {
            add(displayNameFocus)
            if (!guest) add(domainFocus)
        }
    }

    fun previousOf(field: FocusRequester): FocusRequester? = chain.getOrNull(chain.indexOf(field) - 1)

    fun nextOf(field: FocusRequester): FocusRequester = chain.getOrNull(chain.indexOf(field) + 1) ?: guestFocus

    fun currentShare(): SmbShare? = buildShare(
        existing = existing,
        host = host,
        shareName = shareName,
        displayName = displayName,
        user = user,
        password = password,
        domain = domain,
        guest = guest,
        existingPassword = existingPassword
    )

    TvModalDialog(onDismiss = onDismiss) {
        DialogCard(
            width = 680.dp,
            modifier = Modifier
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(
                    if (existing == null) R.string.share_add_title else R.string.share_edit_title
                ),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 21.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(Modifier.height(14.dp))

            Field(
                label = stringResource(R.string.share_host),
                value = host,
                onValueChange = { host = it },
                focusRequester = hostFocus,
                upFocusRequester = null,
                downFocusRequester = nextOf(hostFocus)
            )

            Field(
                label = stringResource(R.string.share_name),
                value = shareName,
                onValueChange = { shareName = it },
                focusRequester = shareFocus,
                upFocusRequester = previousOf(shareFocus),
                downFocusRequester = nextOf(shareFocus)
            )

            if (!guest) {
                Field(
                    label = stringResource(R.string.share_user),
                    value = user,
                    onValueChange = { user = it },
                    focusRequester = userFocus,
                    upFocusRequester = previousOf(userFocus),
                    downFocusRequester = nextOf(userFocus)
                )

                Field(
                    label = stringResource(
                        if (existingPassword.isNullOrEmpty()) {
                            R.string.share_password
                        } else {
                            R.string.share_password_unchanged
                        }
                    ),
                    value = password,
                    onValueChange = { password = it },
                    focusRequester = passwordFocus,
                    upFocusRequester = previousOf(passwordFocus),
                    downFocusRequester = nextOf(passwordFocus),
                    masked = true
                )
            }

            if (advanced) {
                Field(
                    label = stringResource(R.string.share_display_name),
                    value = displayName,
                    onValueChange = { displayName = it },
                    focusRequester = displayNameFocus,
                    upFocusRequester = previousOf(displayNameFocus),
                    downFocusRequester = nextOf(displayNameFocus)
                )

                if (!guest) {
                    Field(
                        label = stringResource(R.string.share_domain),
                        value = domain,
                        onValueChange = { domain = it },
                        focusRequester = domainFocus,
                        upFocusRequester = previousOf(domainFocus),
                        downFocusRequester = nextOf(domainFocus)
                    )
                }
            }

            if (incomplete) {
                Text(
                    text = stringResource(R.string.share_incomplete),
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 15.sp
                )

                Spacer(Modifier.height(10.dp))
            }

            testMessage?.let { message ->
                Text(
                    text = message,
                    color = if (testFailed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    fontSize = 15.sp
                )

                Spacer(Modifier.height(10.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TvDialogButton(
                    text = stringResource(
                        if (guest) R.string.share_credentials_user else R.string.share_credentials_guest
                    ),
                    modifier = Modifier.width(200.dp),
                    focusRequester = guestFocus,
                    onClick = { guest = !guest }
                )

                TvDialogButton(
                    text = stringResource(
                        if (advanced) R.string.share_advanced_hide else R.string.share_advanced_show
                    ),
                    modifier = Modifier.width(200.dp),
                    onClick = { advanced = !advanced }
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TvDialogButton(
                    text = stringResource(if (testing) R.string.share_testing else R.string.share_test),
                    modifier = Modifier.width(220.dp),
                    onClick = {
                        if (testing) return@TvDialogButton

                        val share = currentShare()

                        if (share == null) {
                            incomplete = true
                        } else {
                            incomplete = false
                            testMessage = null
                            testing = true

                            onTest(share) { success, message ->
                                testing = false
                                testFailed = !success
                                testMessage = message
                            }
                        }
                    }
                )

                Spacer(Modifier.weight(1f))

                TvDialogButton(
                    text = stringResource(R.string.cancel),
                    modifier = Modifier.width(160.dp),
                    onClick = onDismiss
                )

                TvDialogButton(
                    text = stringResource(R.string.save),
                    modifier = Modifier.width(180.dp),
                    onClick = {
                        val share = currentShare()
                        if (share == null) incomplete = true else onSave(share)
                    }
                )
            }
        }
    }

    RequestInitialFocus(hostFocus, target)
}

@Composable
private fun Field(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    focusRequester: FocusRequester,
    upFocusRequester: FocusRequester?,
    downFocusRequester: FocusRequester?,
    masked: Boolean = false
) {
    Text(
        text = label,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        fontSize = 13.sp
    )

    Spacer(Modifier.height(3.dp))

    TvTextInput(
        value = value,
        onValueChange = onValueChange,
        focusRequester = focusRequester,
        downFocusRequester = downFocusRequester,
        upFocusRequester = upFocusRequester,
        masked = masked
    )

    Spacer(Modifier.height(10.dp))
}

/**
 * @return the share to store, or `null` when host or share name are missing. An empty
 *   display name falls back to the share name, and an empty password on an existing share
 *   keeps the stored one instead of clearing it.
 */
private fun buildShare(
    existing: SmbShare?,
    host: String,
    shareName: String,
    displayName: String,
    user: String,
    password: String,
    domain: String,
    guest: Boolean,
    existingPassword: String?
): SmbShare? {
    val trimmedHost = host.trim()
    val trimmedShare = shareName.trim().trim('\\', '/')

    if (trimmedHost.isEmpty() || trimmedShare.isEmpty()) return null

    val credentials = if (guest) {
        SmbCredentials.Guest
    } else {
        SmbCredentials.Password(
            user = user.trim(),
            password = password.ifEmpty { existingPassword.orEmpty() },
            domain = domain.trim().takeIf { it.isNotBlank() }
        )
    }

    return SmbShare(
        id = existing?.id ?: UUID.randomUUID().toString(),
        host = trimmedHost,
        name = trimmedShare,
        displayName = displayName.trim().ifBlank { trimmedShare },
        credentials = credentials
    )
}
