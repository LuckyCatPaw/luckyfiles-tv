package com.luckycatpaw.luckyfilestv.data.transfer

import android.content.Context
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.util.formatBytes

/**
 * What a transfer tells the user when something does not go through.
 *
 * An interface for the same reason [com.luckycatpaw.luckyfilestv.data.source.SourceMessages]
 * is one: the decisions a transfer makes — this source is gone, that target sits inside its
 * own source, there is not enough room — are worth testing, and every one of them used to
 * build its sentence inline from a Context.
 */
internal interface TransferMessages {

    fun sourceMissing(): String

    fun symbolicLinksNotSupported(): String

    /** @param copying tells the two directions apart, which read differently to a user. */
    fun transferIntoSelf(copying: Boolean): String

    fun unreadableSkipped(name: String): String

    fun notEnoughSpace(requiredBytes: Long, availableBytes: Long): String

    fun targetFolderMissing(): String

    fun targetReadOnly(): String

    fun folderReadFailed(name: String): String

    fun unsafeFileTree(): String

    fun generic(): String
}

/** The wording as it comes out of the resource files. */
internal class AndroidTransferMessages(context: Context) : TransferMessages {

    private val appContext = context.applicationContext

    override fun sourceMissing(): String = appContext.getString(R.string.source_missing)

    override fun symbolicLinksNotSupported(): String = appContext.getString(R.string.symbolic_links_not_supported)

    override fun transferIntoSelf(copying: Boolean): String =
        appContext.getString(if (copying) R.string.copy_into_self else R.string.move_into_self)

    override fun unreadableSkipped(name: String): String = appContext.getString(R.string.unreadable_skipped, name)

    override fun notEnoughSpace(requiredBytes: Long, availableBytes: Long): String = appContext.getString(
        R.string.not_enough_space,
        formatBytes(requiredBytes),
        formatBytes(availableBytes)
    )

    override fun targetFolderMissing(): String = appContext.getString(R.string.target_folder_missing)

    override fun targetReadOnly(): String = appContext.getString(R.string.target_read_only)

    override fun folderReadFailed(name: String): String = appContext.getString(R.string.folder_named_read_failed, name)

    override fun unsafeFileTree(): String = appContext.getString(R.string.unsafe_file_tree)

    override fun generic(): String = appContext.getString(R.string.error_generic)
}
