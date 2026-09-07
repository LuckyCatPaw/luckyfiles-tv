package com.luckycatpaw.luckyfilestv.data.transfer

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Test

/**
 * Whether a transfer will fit.
 *
 * Both mistakes cost the user something: starting a transfer that fills the volume leaves a
 * half-written file and a device with no room, and refusing one that would have fitted
 * simply does not do the job. The awkward part is that a volume which cannot be measured
 * looks exactly like a full one, and only the total tells the two apart.
 */
class FreeSpaceCheckTest {

    private val directory = File("/storage/emulated/0")

    @Test
    fun `room to spare is no reason to refuse`() {
        val check = check(available = 10L * GIGABYTE)

        assertNull(check.issueFor(directory, requiredBytes = GIGABYTE))
    }

    @Test
    fun `a transfer larger than the volume is refused, naming both numbers`() {
        val check = check(available = GIGABYTE)

        val issue = assertNotNull(check.issueFor(directory, requiredBytes = 10L * GIGABYTE))

        assertEquals(directory.absolutePath, issue.sourcePath)
        assertEquals("notEnoughSpace", issue.message)
    }

    @Test
    fun `a margin is kept so the volume is not filled to the last byte`() {
        // Fitting exactly is not fitting: the eight megabytes left over are what keeps the
        // device usable afterwards.
        val check = check(available = GIGABYTE)

        assertNull(check.issueFor(directory, requiredBytes = GIGABYTE - 9L * MEGABYTE))
        assertNotNull(check.issueFor(directory, requiredBytes = GIGABYTE - 7L * MEGABYTE))
    }

    @Test
    fun `a volume that cannot be measured is left alone`() {
        // Refusing here would block every transfer on storage the platform will not report
        // on, which is worse than letting the write itself fail.
        assertNull(check(available = null).issueFor(directory, requiredBytes = GIGABYTE))
        assertNull(check(available = GIGABYTE, total = null).issueFor(directory, requiredBytes = 10L * GIGABYTE))
    }

    @Test
    fun `a full volume is refused, unlike one that could not be measured`() {
        // Zero free reads the same as a failed statfs; the total is what separates them, and
        // getting this backwards would let a transfer start onto a volume with nothing left.
        val full = check(available = 0L, total = 64L * GIGABYTE)
        val unmeasurable = check(available = 0L, total = 0L)

        assertNotNull(full.issueFor(directory, requiredBytes = GIGABYTE))
        assertNull(unmeasurable.issueFor(directory, requiredBytes = GIGABYTE))
    }

    @Test
    fun `nothing to transfer needs no room`() {
        assertNull(check(available = 0L).issueFor(directory, requiredBytes = 0L))
        assertNull(check(available = 0L).issueFor(directory, requiredBytes = -1L))
    }

    private fun check(available: Long?, total: Long? = 64L * GIGABYTE): FreeSpaceCheck = FreeSpaceCheck(
        messages = StubTransferMessages,
        availableBytes = { available },
        totalBytes = { total }
    )

    private companion object {
        const val MEGABYTE = 1024L * 1024L
        const val GIGABYTE = 1024L * MEGABYTE
    }
}
