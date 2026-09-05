package com.luckycatpaw.luckyfilestv.data.repository

import com.luckycatpaw.luckyfilestv.data.common.model.BrowserItem
import com.luckycatpaw.luckyfilestv.data.common.model.FileManagerSettings
import com.luckycatpaw.luckyfilestv.data.source.local.LocalVolumeRepository
import com.luckycatpaw.luckyfilestv.util.FileUtil
import com.luckycatpaw.luckyfilestv.util.MimeTypes
import java.io.File
import java.util.ArrayDeque
import java.util.PriorityQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class RecentBrowserItem(val item: BrowserItem.File, val modified: Long)

internal class LocalFileSearchRepository(private val volumes: LocalVolumeRepository) {
    suspend fun search(
        query: String,
        directoriesOnly: Boolean,
        settings: FileManagerSettings,
        acceptedMimeTypes: List<String>
    ): List<BrowserItem> = withContext(Dispatchers.IO) {
        val results = mutableListOf<BrowserItem>()
        val mimeMatcher = MimeTypes.matcher(acceptedMimeTypes)

        walk(storageRoots(), settings) { file ->
            val matches = file.name.contains(query, ignoreCase = true)

            if (file.isDirectory) {
                if (matches) results += BrowserItem.Folder(file.name, file.absolutePath)
            } else if (!directoriesOnly && matches && shouldInclude(file, settings, mimeMatcher)) {
                results += file.toBrowserItem()
            }

            results.size < MAX_SEARCH_RESULTS
        }

        results
    }

    suspend fun loadRecents(settings: FileManagerSettings, acceptedMimeTypes: List<String>): List<RecentBrowserItem> =
        withContext(Dispatchers.IO) {
            val storageRoots = storageRoots()
            val mimeMatcher = MimeTypes.matcher(acceptedMimeTypes)
            val results = mutableListOf<RecentBrowserItem>()

            for (storageRoot in storageRoots) {
                currentCoroutineContext().ensureActive()
                results += loadRecentsFromStorage(storageRoot, settings, mimeMatcher)
            }
            results
        }

    private suspend fun loadRecentsFromStorage(
        storageRoot: File,
        settings: FileManagerSettings,
        mimeMatcher: (String) -> Boolean
    ): List<RecentBrowserItem> {
        val newestFiles = PriorityQueue<RecentCandidate>(compareBy { it.modified })

        walk(listOf(storageRoot), settings) { file ->
            if (!file.isDirectory && shouldInclude(file, settings, mimeMatcher)) {
                val candidate = RecentCandidate(file, file.lastModified())

                if (newestFiles.size < MAX_RECENTS_PER_STORAGE) {
                    newestFiles.add(candidate)
                } else {
                    val oldest = newestFiles.peek()

                    if (oldest != null && candidate.modified > oldest.modified) {
                        newestFiles.poll()
                        newestFiles.add(candidate)
                    }
                }
            }

            // Never done early: the newest file may be the last one the walk reaches, so
            // this one runs until the entry or time budget is spent.
            true
        }

        return buildList(newestFiles.size) {
            while (newestFiles.isNotEmpty()) {
                newestFiles.poll()?.let { add(RecentBrowserItem(it.file.toBrowserItem(), it.modified)) }
            }
        }.asReversed()
    }

    /**
     * Walks [roots] breadth first and offers every entry to [onEntry].
     *
     * Search and recents used to carry one of these each, identical down to the order of the
     * three skip conditions and differing only in what they did per file. Which is the part
     * worth keeping separate: the traversal itself is where the subtle rules live — a
     * canonical path so a symbolic link cannot send the walk into a loop, the visited set on
     * that canonical path, the SAF-restricted directories, and three budgets that all have to
     * be checked in both loops rather than only the outer one.
     *
     * Directories are always descended into; whether they are also reported is up to
     * [onEntry], which receives them like any other entry. Returning `false` from it stops
     * the walk, which is how a caller expresses "I have enough now" without a second budget
     * of its own.
     *
     * Hidden entries and anything under a restricted root never reach [onEntry] at all, so a
     * caller cannot forget to filter them.
     */
    private suspend fun walk(
        roots: List<File>,
        settings: FileManagerSettings,
        onEntry: (File) -> Boolean
    ) {
        val pendingDirectories = ArrayDeque<File>().apply { addAll(roots) }
        val visitedDirectories = HashSet<String>()
        var scannedEntries = 0
        var wantsMore = true
        val deadlineNanos = System.nanoTime() + SCAN_TIME_BUDGET_MS * 1_000_000

        while (
            wantsMore &&
            pendingDirectories.isNotEmpty() &&
            scannedEntries < MAX_SCAN_ENTRIES &&
            System.nanoTime() < deadlineNanos
        ) {
            currentCoroutineContext().ensureActive()
            val directory = pendingDirectories.removeFirst().canonicalOrNull() ?: continue

            if (!directory.isDirectory) continue
            if (!visitedDirectories.add(directory.absolutePath)) continue
            if (FileUtil.isSafRestrictedPath(directory.path)) continue

            for (child in directory.listFilesSafely()) {
                currentCoroutineContext().ensureActive()
                if (++scannedEntries >= MAX_SCAN_ENTRIES) break

                val file = child.canonicalOrNull() ?: continue
                if (FileUtil.isHiddenFile(file.name, settings.hideFolderJpg) ||
                    FileUtil.isSafRestrictedPath(file.path)
                ) {
                    continue
                }

                if (file.isDirectory) pendingDirectories.add(file)

                wantsMore = onEntry(file)
                if (!wantsMore) break
            }
        }
    }

    private fun shouldInclude(file: File, settings: FileManagerSettings, mimeMatcher: (String) -> Boolean): Boolean {
        if (!file.isFile) return false
        if (FileUtil.isHiddenFile(file.name, settings.hideFolderJpg)) return false
        return mimeMatcher(MimeTypes.forFileName(file.name))
    }

    private suspend fun storageRoots(): List<File> = volumes.volumes().map { it.path.toFile() }

    private fun File.canonicalOrNull(): File? = runCatching { canonicalFile }.getOrNull()

    private fun File.listFilesSafely(): Array<out File> = runCatching {
        listFiles().orEmpty()
    }.getOrDefault(emptyArray())

    private fun File.toBrowserItem(): BrowserItem.File = BrowserItem.File(name = name, path = absolutePath)

    private data class RecentCandidate(val file: File, val modified: Long)

    private companion object {
        const val MAX_SEARCH_RESULTS = 300
        const val MAX_RECENTS_PER_STORAGE = 64
        const val MAX_SCAN_ENTRIES = 100_000
        const val SCAN_TIME_BUDGET_MS = 8_000L
    }
}
