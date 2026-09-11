package com.luckycatpaw.luckyfilestv.util

import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlinx.coroutines.CancellationException

/**
 * Common file operation utilities to ensure consistency across repositories and providers.
 */
object FileUtil {

    /**
     * Validates a file or directory name, ensuring it doesn't contain forbidden characters
     * and isn't empty or a reserved name like "." or "..".
     */
    fun validateFileName(name: String): String {
        val clean = name.trim()
        require(clean.isNotBlank()) { "Name cannot be empty" }
        require(clean != "." && clean != "..") { "Invalid name: $clean" }
        require('/' !in clean && '\\' !in clean && '\u0000' !in clean) { "Name contains invalid characters" }
        return clean
    }

    /**
     * Sanitizes a name by replacing invalid characters with underscores.
     */
    fun sanitizeFileName(name: String): String {
        val cleaned = name
            .replace('/', '_')
            .replace('\\', '_')
            .replace("\u0000", "")
            .trim()

        if (cleaned.isBlank() || cleaned == "." || cleaned == "..") {
            return UNNAMED
        }
        return cleaned
    }

    /**
     * Checks if a file path is within a SAF restricted directory (e.g. Android/data).
     */
    fun isSafRestrictedPath(path: String): Boolean {
        val segments = path
            .replace('\\', '/')
            .split('/')
            .filter(String::isNotEmpty)

        return segments.windowed(size = 2).any { pair ->
            pair[0].equals("Android", ignoreCase = true) &&
                (pair[1].equals("data", ignoreCase = true) || pair[1].equals("obb", ignoreCase = true))
        }
    }

    /**
     * The separator is appended only when [parentPath] does not already end in one, because
     * the filesystem root is its own separator: `"/" + "/"` produced `"//"`, which nothing
     * starts with, so every path came back as being outside `/`.
     */
    fun isSameOrChildPath(parentPath: String, childPath: String): Boolean {
        if (childPath == parentPath) return true

        val prefix = if (parentPath.endsWith(File.separatorChar)) parentPath else parentPath + File.separator

        return childPath.startsWith(prefix)
    }

    /**
     * Checks if child is same as or a descendant of parent.
     */
    fun isSameOrChild(parent: File, child: File): Boolean {
        val p = runCatching { parent.canonicalPath }.getOrNull() ?: parent.absolutePath
        val c = runCatching { child.canonicalPath }.getOrNull() ?: child.absolutePath
        return isSameOrChildPath(p, c)
    }

    /**
     * Names to try for a new entry called [requestedName], in order: the name itself, then
     * `Name (1)`, `Name (2)` and so on without end.
     *
     * Only the naming rule lives here, not the question of what is occupied — that differs
     * per storage. Locally it is a stat that must not follow symbolic links; on a share it
     * is a request to the server. Both used to carry their own copy of the extension
     * handling, which is the part with the edge cases: a name that is all extension
     * (`.gitignore`), one that ends in a dot, a directory whose name happens to contain one.
     *
     * Infinite on purpose. A caller stops at the first free name, and expressing that as
     * `first { }` or a `for` with a `return` reads better than a `while` that has to test
     * the same candidate twice to get its counter started.
     */
    fun uniqueNameCandidates(requestedName: String, isDirectory: Boolean): Sequence<String> = sequence {
        yield(requestedName)

        val extensionIndex = requestedName.lastIndexOf('.')
        val hasExtension = !isDirectory &&
            extensionIndex > 0 &&
            extensionIndex < requestedName.lastIndex

        val baseName = if (hasExtension) requestedName.substring(0, extensionIndex) else requestedName
        val extension = if (hasExtension) requestedName.substring(extensionIndex) else ""

        var number = 1

        while (true) {
            yield("$baseName ($number)$extension")
            number++
        }
    }

    /**
     * Generates a unique destination in [parent] by appending " (n)" to the requested name.
     *
     * Existence is checked without following symbolic links, so a dangling link is treated
     * as an occupied name rather than a free one. [reservedTargets] lets a caller planning
     * several transfers up front avoid handing out the same destination twice.
     */
    fun createUniqueDestination(
        parent: File,
        requestedName: String,
        isDirectory: Boolean,
        reservedTargets: Set<String> = emptySet()
    ): File = uniqueNameCandidates(requestedName, isDirectory)
        .map { name -> File(parent, name) }
        .first { candidate ->
            !Files.exists(candidate.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                candidate.absolutePath !in reservedTargets
        }

    /**
     * Moves [source] to [target] without requesting replacement of an existing entry.
     *
     * Plain Files.move reports an occupied name as a conflict; moving an entry onto itself
     * is a no-op. ATOMIC_MOVE is deliberately absent because it ignores the other options
     * and may replace a destination even without REPLACE_EXISTING.
     *
     * This is not an atomic no-replace guarantee against concurrent writers: a filesystem
     * provider may check the target before renaming. No placeholder is created or cleaned
     * up, so a failed move cannot remove another writer's entry through reservation cleanup.
     *
     * Across filesystems the provider may copy a file and then delete its source, blocking
     * until that work finishes. It does not recursively copy a nonempty directory. Callers
     * needing rename-only behaviour must establish the volume boundary before calling.
     * This method does not promise AtomicMoveNotSupportedException for a cross-volume move.
     *
     * @throws FileAlreadyExistsException if the provider reports an occupied target.
     * @throws IOException on any other failure.
     */
    @Throws(IOException::class)
    fun moveWithoutReplacing(source: File, target: File) {
        Files.move(source.toPath(), target.toPath())
    }

    /**
     * Wraps a suspending block in a [Result], ensuring [CancellationException] is rethrown
     * to avoid breaking coroutine state management.
     */
    suspend fun <T> runCancellable(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * Common logic to determine if a file should be hidden (e.g. folder.jpg).
     */
    fun isHiddenFile(name: String, hideFolderJpg: Boolean, showHidden: Boolean = false): Boolean {
        if (!showHidden && name.startsWith('.')) return true
        if (hideFolderJpg && name.equals("folder.jpg", ignoreCase = true)) return true
        return false
    }

    /** Result of [sanitizeFileName] when nothing usable is left of the input. */
    const val UNNAMED = "unnamed"
}
