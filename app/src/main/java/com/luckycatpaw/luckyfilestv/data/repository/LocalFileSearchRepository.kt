package com.luckycatpaw.luckyfilestv.data.repository

import com.luckycatpaw.luckyfilestv.data.common.model.BrowserItem
import com.luckycatpaw.luckyfilestv.data.common.model.FileManagerSettings
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

internal class LocalFileSearchRepository(private val storageRepository: StorageRepository) {
    suspend fun search(
        query: String,
        directoriesOnly: Boolean,
        settings: FileManagerSettings,
        acceptedMimeTypes: List<String>
    ): List<BrowserItem> = withContext(Dispatchers.IO) {
        val results = mutableListOf<BrowserItem>()
        val pendingDirectories = ArrayDeque<File>()
        val visitedDirectories = HashSet<String>()
        val storageRoots = storageRoots()
        val mimeMatcher = MimeTypes.matcher(acceptedMimeTypes)
        var scannedEntries = 0
        val deadlineNanos = System.nanoTime() + SCAN_TIME_BUDGET_MS * 1_000_000

        storageRoots.forEach { pendingDirectories.add(it) }

        while (
            pendingDirectories.isNotEmpty() &&
            results.size < MAX_SEARCH_RESULTS &&
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

                if (file.isDirectory) {
                    pendingDirectories.add(file)
                    if (file.name.contains(query, ignoreCase = true)) {
                        results += BrowserItem.Folder(file.name, file.absolutePath)
                    }
                } else if (!directoriesOnly &&
                    shouldInclude(file, settings, mimeMatcher) &&
                    file.name.contains(query, ignoreCase = true)
                ) {
                    results += file.toBrowserItem()
                }

                if (results.size >= MAX_SEARCH_RESULTS) break
            }
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
        val pendingDirectories = ArrayDeque<File>().apply { add(storageRoot) }
        val visitedDirectories = HashSet<String>()
        var scannedEntries = 0
        val deadlineNanos = System.nanoTime() + SCAN_TIME_BUDGET_MS * 1_000_000

        while (
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

                if (file.isDirectory) {
                    pendingDirectories.add(file)
                    continue
                }

                if (!shouldInclude(file, settings, mimeMatcher)) continue

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
        }

        return buildList(newestFiles.size) {
            while (newestFiles.isNotEmpty()) {
                newestFiles.poll()?.let { add(RecentBrowserItem(it.file.toBrowserItem(), it.modified)) }
            }
        }.asReversed()
    }

    private fun shouldInclude(file: File, settings: FileManagerSettings, mimeMatcher: (String) -> Boolean): Boolean {
        if (!file.isFile) return false
        if (FileUtil.isHiddenFile(file.name, settings.hideFolderJpg)) return false
        return mimeMatcher(MimeTypes.forFileName(file.name))
    }

    private suspend fun storageRoots(): List<File> = storageRepository.getStorages().map { File(it.path) }

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
