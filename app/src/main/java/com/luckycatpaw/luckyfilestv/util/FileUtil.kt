package com.luckycatpaw.luckyfilestv.util

import kotlinx.coroutines.CancellationException
import java.io.File

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
        val cleaned = name.replace('/', '_')
            .replace('\\', '_')
            .replace("\u0000", "")
            .trim()
        
        if (cleaned.isBlank() || cleaned == "." || cleaned == "..") {
            return "unnamed"
        }
        return cleaned
    }

    /**
     * Checks if a file path is within a SAF restricted directory (e.g. Android/data).
     */
    fun isSafRestrictedPath(path: String): Boolean {
        return path.contains("/Android/data", ignoreCase = true) || 
               path.contains("/Android/obb", ignoreCase = true)
    }

    /**
     * Checks if child is same as or a descendant of parent.
     */
    fun isSameOrChildPath(parentPath: String, childPath: String): Boolean {
        return childPath == parentPath || childPath.startsWith(parentPath + File.separator)
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
     * Generates a unique destination file in the target directory by appending a number
     * if the file already exists.
     */
    fun createUniqueDestination(parent: File, requestedName: String, isDirectory: Boolean): File {
        var candidate = File(parent, requestedName)
        if (!candidate.exists()) return candidate

        val (baseName, extension) = if (!isDirectory) {
            val dot = requestedName.lastIndexOf('.')
            if (dot in 1 until requestedName.lastIndex) {
                requestedName.substring(0, dot) to requestedName.substring(dot)
            } else requestedName to ""
        } else requestedName to ""

        var number = 1
        while (candidate.exists()) {
            candidate = File(parent, "$baseName ($number)$extension")
            number++
        }
        return candidate
    }

    /**
     * Wraps a suspending block in a [Result], ensuring [CancellationException] is rethrown
     * to avoid breaking coroutine state management.
     */
    suspend fun <T> runCancellable(block: suspend () -> T): Result<T> {
        return try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Common logic to determine if a file should be hidden (e.g. folder.jpg).
     */
    fun isHiddenFile(name: String, hideFolderJpg: Boolean): Boolean {
        if (name.startsWith('.')) return true
        if (hideFolderJpg && name.equals("folder.jpg", ignoreCase = true)) return true
        return false
    }
}
