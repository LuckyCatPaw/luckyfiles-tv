package com.luckycatpaw.luckyfilestv.data.repository

import android.content.Context
import com.luckycatpaw.luckyfilestv.data.common.model.BrowserItem
import com.luckycatpaw.luckyfilestv.data.common.model.FileManagerSettings
import com.luckycatpaw.luckyfilestv.data.common.model.FileProperties
import com.luckycatpaw.luckyfilestv.data.source.FileEntry
import com.luckycatpaw.luckyfilestv.data.source.FileOperationException
import com.luckycatpaw.luckyfilestv.data.source.FileSourceRegistry
import com.luckycatpaw.luckyfilestv.data.source.SourceMessages
import com.luckycatpaw.luckyfilestv.data.source.SourceOperation
import com.luckycatpaw.luckyfilestv.data.source.SourcePath
import com.luckycatpaw.luckyfilestv.data.source.toListOptions
import kotlinx.coroutines.CancellationException

/** Contents of a directory in the shape the browser screens consume. */
internal data class DirectoryContent(val items: List<BrowserItem>, val title: String, val writable: Boolean)

/**
 * Entry point of the UI into the file sources.
 *
 * Takes the plain location strings the screens work with, hands the call to the source that
 * owns the location and returns a [Result] whose failure already carries localised text. All
 * knowledge about paths, threading and error wording sits below this class; adding a network
 * source changes nothing here.
 */
internal class FileRepository(context: Context, private val sources: FileSourceRegistry) {

    private val messages = SourceMessages(context)

    /** Entry points of all sources: mounted volumes today, network shares later. */
    suspend fun roots(): List<BrowserItem.Storage> = sources.roots().map { root ->
        BrowserItem.Storage(name = root.name, path = root.path.value, removable = root.removable)
    }

    suspend fun isRoot(path: String): Boolean {
        val location = SourcePath.parseOrNull(path) ?: return false
        return sources.isRoot(location)
    }

    /** Containing directory, `null` when the location is the top of its source. */
    fun parentOf(path: String): String? = SourcePath.parseOrNull(path)?.parent?.value

    suspend fun list(path: String, settings: FileManagerSettings): Result<DirectoryContent> =
        runOperation(SourceOperation.LIST) {
            val location = SourcePath.parse(path)
            val listing = sources.source(location).list(location, settings.toListOptions())

            DirectoryContent(
                items = listing.entries.map { it.toBrowserItem() },
                title = listing.displayName,
                writable = listing.writable
            )
        }

    suspend fun getProperties(path: String): Result<FileProperties> = runOperation(SourceOperation.PROPERTIES) {
        val location = SourcePath.parse(path)
        sources.source(location).properties(location)
    }

    suspend fun rename(path: String, newName: String): Result<String> = runOperation(SourceOperation.RENAME) {
        val location = SourcePath.parse(path)
        sources.source(location).rename(location, newName).value
    }

    suspend fun delete(path: String): Result<Unit> = runOperation(SourceOperation.DELETE) {
        val location = SourcePath.parse(path)
        sources.source(location).delete(location)
    }

    suspend fun createFolder(parentPath: String, name: String): Result<String> =
        runOperation(SourceOperation.CREATE_DIRECTORY) {
            val location = SourcePath.parse(parentPath)
            sources.source(location).createDirectory(location, name).value
        }

    private suspend fun <T> runOperation(operation: SourceOperation, block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(FileOperationException(messages.localize(failure, operation), failure))
    }
}

private fun FileEntry.toBrowserItem(): BrowserItem = if (isDirectory) {
    BrowserItem.Folder(name = name, path = path.value)
} else {
    BrowserItem.File(name = name, path = path.value)
}
