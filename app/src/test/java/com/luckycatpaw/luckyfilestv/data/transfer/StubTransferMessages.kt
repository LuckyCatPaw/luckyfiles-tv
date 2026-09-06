package com.luckycatpaw.luckyfilestv.data.transfer

/** Names each message instead of wording it, so a test reads which decision was taken. */
internal object StubTransferMessages : TransferMessages {

    override fun sourceMissing() = "sourceMissing"

    override fun symbolicLinksNotSupported() = "symbolicLinksNotSupported"

    override fun transferIntoSelf(copying: Boolean) = "transferIntoSelf"

    override fun unreadableSkipped(name: String) = "unreadableSkipped"

    override fun notEnoughSpace(requiredBytes: Long, availableBytes: Long) = "notEnoughSpace"

    override fun targetFolderMissing() = "targetFolderMissing"

    override fun targetReadOnly() = "targetReadOnly"

    override fun folderReadFailed(name: String) = "folderReadFailed"

    override fun unsafeFileTree() = "unsafeFileTree"

    override fun generic() = "generic"
}
