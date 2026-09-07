package com.luckycatpaw.luckyfilestv.data.transfer

import android.content.Context
import android.os.storage.StorageManager
import com.luckycatpaw.luckyfilestv.data.transfer.model.TransferIssue
import com.luckycatpaw.luckyfilestv.util.safeAdd
import java.io.File

/**
 * How many bytes a volume will still take.
 *
 * Its own type because the answer comes from the platform and the decision built on it does
 * not: whether a transfer has room is arithmetic, and it is worth being sure about, since
 * refusing a transfer that would have fitted is as wrong as starting one that will not.
 */
internal fun interface AvailableBytes {

    /** @return `null` when the volume cannot be measured, which is not the same as full. */
    fun of(directory: File): Long?
}

/** The figure the platform gives, which accounts for what it could still free up itself. */
internal class AndroidAvailableBytes(context: Context) : AvailableBytes {

    private val appContext = context.applicationContext

    override fun of(directory: File): Long? {
        val storageManager = appContext.getSystemService(StorageManager::class.java)

        return runCatching {
            val uuid = storageManager.getUuidForPath(directory)
            storageManager.getAllocatableBytes(uuid)
        }.getOrElse {
            runCatching { directory.usableSpace }.getOrNull()
        }
    }
}

/** Refuses a transfer that will not fit, and stays out of the way when it cannot tell. */
internal class FreeSpaceCheck(
    private val messages: TransferMessages,
    private val availableBytes: AvailableBytes,
    private val totalBytes: (File) -> Long? = { runCatching { it.totalSpace }.getOrNull() }
) {

    /** @return the reason to refuse, or `null` when there is room or no way to know. */
    fun issueFor(targetDirectory: File, requiredBytes: Long): TransferIssue? {
        if (requiredBytes <= 0L) return null

        // usableSpace returns 0 for a full volume as well as for a failed statfs(2), so the
        // two cases are told apart via totalSpace: it is only 0 when the measurement itself
        // is unavailable. A genuine "0 bytes free" has to fail the check, not skip it.
        val volumeBytes = totalBytes(targetDirectory) ?: return null
        if (volumeBytes <= 0L) return null

        val usableBytes = availableBytes.of(targetDirectory) ?: return null

        if (usableBytes >= safeAdd(requiredBytes, MARGIN_BYTES)) return null

        return TransferIssue(
            sourcePath = targetDirectory.absolutePath,
            message = messages.notEnoughSpace(requiredBytes = requiredBytes, availableBytes = usableBytes)
        )
    }

    private companion object {
        /** Room left over, so a transfer does not fill a volume to the last byte. */
        const val MARGIN_BYTES = 8L * 1024L * 1024L
    }
}
