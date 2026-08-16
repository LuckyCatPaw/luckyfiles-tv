package com.luckycatpaw.luckyfilestv.util

import java.io.File
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
        if (clean.isBlank()) throw IllegalArgumentException("Name cannot be empty")
        if (clean == "." || clean == "..") throw IllegalArgumentException("Invalid name: $clean")
        if ('/' in clean || '\\' in clean || '\u0000' in clean) {
            throw IllegalArgumentException("Name contains invalid characters")
        }
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
     * Checks if child is same as or a descendant of parent.
     */
    fun isSameOrChildPath(parentPath: String, childPath: String): Boolean =
        childPath == parentPath || childPath.startsWith(parentPath + File.separator)

    /**
     * Checks if child is same as or a descendant of parent.
     */
    fun isSameOrChild(parent: File, child: File): Boolean {
        val p = runCatching { parent.canonicalPath }.getOrNull() ?: parent.absolutePath
        val c = runCatching { child.canonicalPath }.getOrNull() ?: child.absolutePath
        return isSameOrChildPath(p, c)
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
    ): File {
        fun taken(candidate: File): Boolean = Files.exists(candidate.toPath(), LinkOption.NOFOLLOW_LINKS) ||
            candidate.absolutePath in reservedTargets

        var candidate = File(parent, requestedName)
        if (!taken(candidate)) return candidate

        val extensionIndex = requestedName.lastIndexOf('.')
        val hasExtension = !isDirectory &&
            extensionIndex > 0 &&
            extensionIndex < requestedName.lastIndex

        val baseName = if (hasExtension) requestedName.substring(0, extensionIndex) else requestedName
        val extension = if (hasExtension) requestedName.substring(extensionIndex) else ""

        var number = 1
        while (taken(candidate)) {
            candidate = File(parent, "$baseName ($number)$extension")
            number++
        }
        return candidate
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
    fun isHiddenFile(name: String, hideFolderJpg: Boolean): Boolean {
        if (name.startsWith('.')) return true
        if (hideFolderJpg && name.equals("folder.jpg", ignoreCase = true)) return true
        return false
    }

    /** Result of [sanitizeFileName] when nothing usable is left of the input. */
    const val UNNAMED = "unnamed"
}
