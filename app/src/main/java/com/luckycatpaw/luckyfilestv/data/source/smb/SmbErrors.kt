package com.luckycatpaw.luckyfilestv.data.source.smb

import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.transport.TransportException
import com.luckycatpaw.luckyfilestv.data.source.SourceException
import com.luckycatpaw.luckyfilestv.data.source.SourceOperation
import com.luckycatpaw.luckyfilestv.data.source.SourcePath
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Translates what smbj throws into the vocabulary the rest of the app already speaks.
 *
 * The distinction that matters for the user is between "wrong credentials", "server not
 * answering" and "this file is not there": each leads to a different reaction, and only the
 * status codes here can tell them apart.
 */
internal fun Throwable.toSourceException(
    operation: SourceOperation,
    path: SourcePath,
    authority: String
): SourceException = when (this) {
    is SourceException -> this

    is SMBApiException -> when (status) {
        NtStatus.STATUS_LOGON_FAILURE,
        NtStatus.STATUS_ACCOUNT_DISABLED,
        NtStatus.STATUS_PASSWORD_EXPIRED -> SourceException.AuthenticationRequired(authority)

        NtStatus.STATUS_ACCESS_DENIED -> SourceException.AccessDenied(path, operation)

        NtStatus.STATUS_BAD_NETWORK_NAME,
        NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
        NtStatus.STATUS_OBJECT_PATH_NOT_FOUND -> SourceException.NotFound(path, operation)

        NtStatus.STATUS_NOT_A_DIRECTORY -> SourceException.NotADirectory(path)

        NtStatus.STATUS_OBJECT_NAME_COLLISION -> SourceException.AlreadyExists(path.name, this)

        else -> SourceException.Failed(operation, this)
    }

    // SocketException covers refused, unreachable and reset connections; on Android a
    // refused port arrives as the plain base class, not as ConnectException.
    is TransportException,
    is SocketException,
    is SocketTimeoutException,
    is UnknownHostException -> SourceException.Unreachable(authority, this)

    is IOException -> SourceException.Failed(operation, this)

    else -> SourceException.Failed(operation, this)
}
