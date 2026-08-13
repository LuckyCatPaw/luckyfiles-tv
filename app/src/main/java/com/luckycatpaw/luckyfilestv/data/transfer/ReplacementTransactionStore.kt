package com.luckycatpaw.luckyfilestv.data.transfer

import android.content.Context
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.data.common.FileTreeWalker
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.UUID

internal class ReplacementTransactionStore(
    context: Context,
    private val fileTreeWalker: FileTreeWalker
) {
    private val appContext = context.applicationContext
    private val journalDirectory = File(
        appContext.noBackupFilesDir,
        JOURNAL_DIRECTORY
    )

    suspend fun recoverPending() {
        transactionMutex.withLock {
            recoverPendingLocked()
        }
    }

    suspend fun installReplacement(
        target: File,
        preparedReplacement: File
    ): Boolean {
        return transactionMutex.withLock {
            recoverPendingLocked()

            require(exists(target))
            require(exists(preparedReplacement))
            require(target.parentFile?.canonicalFile == preparedReplacement.parentFile?.canonicalFile)

            val transactionId = UUID.randomUUID().toString()
            val parent = requireNotNull(target.parentFile)
            val backup = File(parent, ".luckyfiles-$transactionId.backup")
            val journal = File(journalDirectory, "$transactionId.txn")
            val transaction = ReplacementTransaction(
                target = target.canonicalFile,
                preparedReplacement = preparedReplacement.canonicalFile,
                backup = backup.canonicalFile
            )

            writeJournal(journal, transaction)

            if (!target.renameTo(backup)) {
                journal.delete()
                error(appContext.getString(R.string.replace_prepare_failed))
            }

            if (!preparedReplacement.renameTo(target)) {
                val restored = backup.renameTo(target)
                if (restored) {
                    journal.delete()
                }
                error(
                    appContext.getString(
                        if (restored) {
                            R.string.replace_failed
                        } else {
                            R.string.replace_restore_failed
                        }
                    )
                )
            }

            val backupDeleted = deleteOwnedPath(backup)
            val replacementDeleted = deleteOwnedPath(preparedReplacement)

            if (backupDeleted && replacementDeleted) {
                journal.delete()
            }

            !backupDeleted || !replacementDeleted
        }
    }

    private suspend fun recoverPendingLocked() {
        val journals = journalDirectory.listFiles()
            ?.filter { it.isFile && it.extension == "txn" }
            .orEmpty()

        for (journal in journals) {
            val transaction = readJournal(journal)
            if (transaction == null || !transaction.isValid()) {
                journal.delete()
                continue
            }

            val targetExists = exists(transaction.target)
            val backupExists = exists(transaction.backup)
            val replacementExists = exists(transaction.preparedReplacement)

            if (!targetExists && backupExists) {
                transaction.backup.renameTo(transaction.target)
            } else if (!targetExists && replacementExists) {
                transaction.preparedReplacement.renameTo(transaction.target)
            }

            if (exists(transaction.target)) {
                deleteOwnedPath(transaction.preparedReplacement)
                deleteOwnedPath(transaction.backup)
            }

            if (
                exists(transaction.target) &&
                !exists(transaction.preparedReplacement) &&
                !exists(transaction.backup)
            ) {
                journal.delete()
            }
        }
    }

    private fun writeJournal(
        journal: File,
        transaction: ReplacementTransaction
    ) {
        check(journalDirectory.exists() || journalDirectory.mkdirs())

        val temporary = File(journalDirectory, ".${journal.name}.tmp")

        FileOutputStream(temporary).use { fileOutput ->
            DataOutputStream(fileOutput).use { output ->
                output.writeInt(JOURNAL_VERSION)
                output.writeUTF(transaction.target.absolutePath)
                output.writeUTF(transaction.preparedReplacement.absolutePath)
                output.writeUTF(transaction.backup.absolutePath)
                output.flush()
                fileOutput.fd.sync()
            }
        }

        try {
            Files.move(
                temporary.toPath(),
                journal.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: Exception) {
            check(journal.exists() || temporary.renameTo(journal))
        }
    }

    private fun readJournal(journal: File): ReplacementTransaction? {
        return runCatching {
            DataInputStream(FileInputStream(journal)).use { input ->
                check(input.readInt() == JOURNAL_VERSION)
                ReplacementTransaction(
                    target = File(input.readUTF()).canonicalFile,
                    preparedReplacement = File(input.readUTF()).canonicalFile,
                    backup = File(input.readUTF()).canonicalFile
                )
            }
        }.getOrNull()
    }

    private suspend fun deleteOwnedPath(file: File): Boolean {
        if (!exists(file)) return true

        return runCatching {
            fileTreeWalker.delete(file)
        }.isSuccess
    }

    private fun exists(file: File): Boolean {
        return Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)
    }

    private data class ReplacementTransaction(
        val target: File,
        val preparedReplacement: File,
        val backup: File
    ) {
        fun isValid(): Boolean {
            val targetParent = target.parentFile?.path ?: return false
            val replacementParent = preparedReplacement.parentFile?.path ?: return false
            val backupParent = backup.parentFile?.path ?: return false

            return targetParent == replacementParent &&
                    targetParent == backupParent &&
                    preparedReplacement.name.startsWith(".luckyfiles-") &&
                    backup.name.startsWith(".luckyfiles-") &&
                    backup.name.endsWith(".backup")
        }
    }

    private companion object {
        const val JOURNAL_DIRECTORY = "replacement_transactions"
        const val JOURNAL_VERSION = 1
        val transactionMutex = Mutex()
    }
}
