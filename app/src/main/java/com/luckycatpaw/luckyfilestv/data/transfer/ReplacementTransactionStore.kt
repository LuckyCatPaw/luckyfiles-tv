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

    suspend fun prepareReplacement(
        target: File,
        preparedReplacement: File
    ): ReplacementPreparation {
        return transactionMutex.withLock {
            recoverPendingLocked()

            require(exists(target))
            require(!exists(preparedReplacement))
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
            activeJournals += journal.absolutePath

            ReplacementPreparation(
                target = transaction.target,
                preparedReplacement = transaction.preparedReplacement,
                backup = transaction.backup,
                journal = journal
            )
        }
    }

    suspend fun installReplacement(
        preparation: ReplacementPreparation
    ): Boolean {
        return transactionMutex.withLock {
            require(preparation.journal.absolutePath in activeJournals)
            require(preparation.journal.isFile)
            require(exists(preparation.target))
            require(exists(preparation.preparedReplacement))

            if (!preparation.target.renameTo(preparation.backup)) {
                preparation.journal.delete()
                error(appContext.getString(R.string.replace_prepare_failed))
            }

            if (!preparation.preparedReplacement.renameTo(preparation.target)) {
                val restored = preparation.backup.renameTo(preparation.target)
                if (restored) {
                    preparation.journal.delete()
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

            val backupDeleted = deleteOwnedPath(preparation.backup)
            val replacementDeleted = deleteOwnedPath(preparation.preparedReplacement)

            if (backupDeleted && replacementDeleted) {
                preparation.journal.delete()
            }

            !backupDeleted || !replacementDeleted
        }
    }

    suspend fun finishPreparation(preparation: ReplacementPreparation) {
        transactionMutex.withLock {
            try {
                deleteOwnedPath(preparation.preparedReplacement)
                if (!exists(preparation.preparedReplacement) && !exists(preparation.backup)) {
                    preparation.journal.delete()
                }
            } finally {
                activeJournals -= preparation.journal.absolutePath
            }
        }
    }

    private suspend fun recoverPendingLocked() {
        val journals = journalDirectory.listFiles()
            ?.filter { it.isFile && it.extension == "txn" }
            .orEmpty()

        for (journal in journals) {
            if (journal.absolutePath in activeJournals) continue

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

            if (!exists(transaction.preparedReplacement) && !exists(transaction.backup)) {
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

    internal data class ReplacementPreparation(
        val target: File,
        val preparedReplacement: File,
        val backup: File,
        val journal: File
    )

    private companion object {
        const val JOURNAL_DIRECTORY = "replacement_transactions"
        const val JOURNAL_VERSION = 1
        val transactionMutex = Mutex()
        val activeJournals = mutableSetOf<String>()
    }
}
