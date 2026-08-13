package com.luckycatpaw.luckyfilestv.data.repository

import com.luckycatpaw.luckyfilestv.data.common.model.BrowserItem
import com.luckycatpaw.luckyfilestv.data.common.model.FileManagerSettings
import com.luckycatpaw.luckyfilestv.util.MimeTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.ArrayDeque
import java.util.PriorityQueue

internal data class RecentBrowserItem(
    val item: BrowserItem.File,
    val modified: Long
)

internal class LocalFileSearchRepository(
    private val storageRepository: StorageRepository
) {
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
        val restrictedRoots = restrictedRoots(storageRoots)
        val mimeMatcher = MimeTypes.matcher(acceptedMimeTypes)
        var scannedEntries = 0

        storageRoots.forEach { pendingDirectories.add(it) }

        while (
            pendingDirectories.isNotEmpty() &&
            results.size < MAX_SEARCH_RESULTS &&
            scannedEntries < MAX_SCAN_ENTRIES
        ) {
            currentCoroutineContext().ensureActive()
            val directory = pendingDirectories.removeFirst().canonicalOrNull() ?: continue

            if (!directory.isDirectory) continue
            if (!visitedDirectories.add(directory.absolutePath)) continue
            if (isSafRestricted(directory, restrictedRoots)) continue

            for (child in directory.listFilesSafely()) {
                currentCoroutineContext().ensureActive()
                if (++scannedEntries >= MAX_SCAN_ENTRIES) break

                val file = child.canonicalOrNull() ?: continue

                if (file.name.startsWith('.') || isSafRestricted(file, restrictedRoots)) continue

                if (file.isDirectory) {
                    pendingDirectories.add(file)

                    if (file.name.contains(query, ignoreCase = true)) {
                        results += BrowserItem.Folder(
                            name = file.name,
                            path = file.absolutePath
                        )
                    }
                } else if (
                    !directoriesOnly &&
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

    suspend fun loadRecents(
        settings: FileManagerSettings,
        acceptedMimeTypes: List<String>
    ): List<RecentBrowserItem> = withContext(Dispatchers.IO) {
        val storageRoots = storageRoots()
        val restrictedRoots = restrictedRoots(storageRoots)
        val mimeMatcher = MimeTypes.matcher(acceptedMimeTypes)

        val results = mutableListOf<RecentBrowserItem>()

        for (storageRoot in storageRoots) {
            currentCoroutineContext().ensureActive()
            results += loadRecentsFromStorage(
                storageRoot,
                restrictedRoots,
                settings,
                mimeMatcher
            )
        }

        results
    }

    private suspend fun loadRecentsFromStorage(
        storageRoot: File,
        restrictedRoots: List<File>,
        settings: FileManagerSettings,
        mimeMatcher: (String) -> Boolean
    ): List<RecentBrowserItem> {
        val newestFiles = PriorityQueue<RecentCandidate>(compareBy { it.modified })
        val pendingDirectories = ArrayDeque<File>().apply { add(storageRoot) }
        val visitedDirectories = HashSet<String>()
        var scannedEntries = 0

        while (pendingDirectories.isNotEmpty() && scannedEntries < MAX_SCAN_ENTRIES) {
            currentCoroutineContext().ensureActive()
            val directory = pendingDirectories.removeFirst().canonicalOrNull() ?: continue

            if (!directory.isDirectory) continue
            if (!visitedDirectories.add(directory.absolutePath)) continue
            if (isSafRestricted(directory, restrictedRoots)) continue

            for (child in directory.listFilesSafely()) {
                currentCoroutineContext().ensureActive()
                if (++scannedEntries >= MAX_SCAN_ENTRIES) break

                val file = child.canonicalOrNull() ?: continue

                if (file.name.startsWith('.') || isSafRestricted(file, restrictedRoots)) continue

                if (file.isDirectory) {
                    pendingDirectories.add(file)
                    continue
                }

                if (!shouldInclude(file, settings, mimeMatcher)) continue

                val candidate = RecentCandidate(
                    file = file,
                    modified = file.lastModified()
                )

                if (newestFiles.size < MAX_RECENTS_PER_STORAGE) {
                    newestFiles.add(candidate)
                } else if (candidate.modified > newestFiles.peek().modified) {
                    newestFiles.poll()
                    newestFiles.add(candidate)
                }
            }
        }

        return buildList(newestFiles.size) {
            while (newestFiles.isNotEmpty()) {
                val candidate = newestFiles.poll()
                add(
                    RecentBrowserItem(
                        candidate.file.toBrowserItem(),
                        candidate.modified
                    )
                )
            }
        }
    }

    private fun shouldInclude(
        file: File,
        settings: FileManagerSettings,
        mimeMatcher: (String) -> Boolean
    ): Boolean {
        if (!file.isFile) return false
        if (settings.hideFolderJpg && file.name.equals(FOLDER_COVER_NAME, true)) return false

        val actualMimeType = MimeTypes.forFileName(file.name)
        return mimeMatcher(actualMimeType)
    }

    private fun isSafRestricted(file: File, restrictedRoots: List<File>): Boolean {
        val filePath = file.path

        return restrictedRoots.any { restrictedRoot ->
            filePath == restrictedRoot.path ||
                    filePath.startsWith(restrictedRoot.path + File.separator)
        }
    }

    private suspend fun storageRoots(): List<File> {
        return storageRepository.getStorages().map { File(it.path) }
    }

    private fun restrictedRoots(storageRoots: List<File>): List<File> {
        return storageRoots.flatMap { root ->
            listOf(File(root, "Android/data"), File(root, "Android/obb"))
        }.mapNotNull { it.canonicalOrNull() }
    }

    private fun File.canonicalOrNull(): File? = runCatching { canonicalFile }.getOrNull()

    private fun File.listFilesSafely(): Array<out File> {
        return runCatching { listFiles().orEmpty() }.getOrDefault(emptyArray())
    }

    private fun File.toBrowserItem(): BrowserItem.File {
        return BrowserItem.File(name = name, path = absolutePath)
    }

    private data class RecentCandidate(
        val file: File,
        val modified: Long
    )

    private companion object {
        const val FOLDER_COVER_NAME = "folder.jpg"
        const val MAX_SEARCH_RESULTS = 300
        const val MAX_RECENTS_PER_STORAGE = 64
        const val MAX_SCAN_ENTRIES = 100_000
    }
}
