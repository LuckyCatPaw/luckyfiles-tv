package com.luckycatpaw.luckyfilestv.data.source.smb

import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mssmb2.SMBApiException
import com.luckycatpaw.luckyfilestv.data.source.SourceException
import com.luckycatpaw.luckyfilestv.data.source.SourceOperation
import com.luckycatpaw.luckyfilestv.data.source.SourcePath
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import org.junit.Test

/**
 * What the user is told when a share says no.
 *
 * The three answers that lead to different reactions are "your credentials are wrong",
 * "the server is not answering" and "that file is not there". Only the status code can
 * tell them apart, so the mapping is the whole of it.
 */
class SmbErrorsTest {

    @Test
    fun `a rejected logon asks for credentials, naming the server`() {
        for (status in listOf(
            NtStatus.STATUS_LOGON_FAILURE,
            NtStatus.STATUS_ACCOUNT_DISABLED,
            NtStatus.STATUS_PASSWORD_EXPIRED
        )) {
            val mapped = apiException(status).translate()

            assertIs<SourceException.AuthenticationRequired>(mapped)
            assertEquals(AUTHORITY, mapped.authority)
        }
    }

    @Test
    fun `a refused operation names the path and what was attempted`() {
        val mapped = apiException(NtStatus.STATUS_ACCESS_DENIED).translate(SourceOperation.DELETE)

        assertIs<SourceException.AccessDenied>(mapped)
        assertEquals(PATH, mapped.path)
        assertEquals(SourceOperation.DELETE, mapped.operation)
    }

    @Test
    fun `a missing share reads the same as a missing file`() {
        // STATUS_BAD_NETWORK_NAME is the share being gone rather than the file, but there is
        // nothing the user can do differently, so it maps to the same answer.
        for (status in listOf(
            NtStatus.STATUS_BAD_NETWORK_NAME,
            NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
            NtStatus.STATUS_OBJECT_PATH_NOT_FOUND
        )) {
            assertIs<SourceException.NotFound>(apiException(status).translate())
        }
    }

    @Test
    fun `the remaining named statuses keep their own answer`() {
        assertIs<SourceException.NotADirectory>(apiException(NtStatus.STATUS_NOT_A_DIRECTORY).translate())

        val collision = apiException(NtStatus.STATUS_OBJECT_NAME_COLLISION).translate()

        assertIs<SourceException.AlreadyExists>(collision)
        assertEquals(PATH.name, collision.name)
    }

    @Test
    fun `an unmapped status falls back to a plain failure that keeps the cause`() {
        val original = apiException(NtStatus.STATUS_INVALID_PARAMETER)

        val mapped = original.translate(SourceOperation.RENAME)

        assertIs<SourceException.Failed>(mapped)
        assertEquals(SourceOperation.RENAME, mapped.operation)
        assertSame(original, mapped.cause)
    }

    @Test
    fun `everything the network can do reads as unreachable`() {
        // A refused port arrives on Android as the plain SocketException, not as
        // ConnectException, which is why the base class is what gets matched.
        for (failure in listOf(SocketException("refused"), SocketTimeoutException(), UnknownHostException("nas"))) {
            val mapped = failure.translate()

            assertIs<SourceException.Unreachable>(mapped)
            assertEquals(AUTHORITY, mapped.authority)
        }
    }

    @Test
    fun `any other IO problem is a failure of the operation`() {
        val mapped = IOException("disk").translate(SourceOperation.WRITE)

        assertIs<SourceException.Failed>(mapped)
        assertEquals(SourceOperation.WRITE, mapped.operation)
    }

    @Test
    fun `a SourceException is already in the right vocabulary and passes through`() {
        val original = SourceException.NotADirectory(PATH)

        assertSame(original, original.translate())
    }

    private fun Throwable.translate(operation: SourceOperation = SourceOperation.LIST): SourceException =
        toSourceException(operation, PATH, AUTHORITY)

    private fun apiException(status: NtStatus): SMBApiException =
        SMBApiException(status.value, SMB2MessageCommandCode.SMB2_CREATE, "test", null)

    private companion object {
        const val AUTHORITY = "nas"
        val PATH = SourcePath.remote("smb", AUTHORITY, "media/Film.mkv")
    }
}
