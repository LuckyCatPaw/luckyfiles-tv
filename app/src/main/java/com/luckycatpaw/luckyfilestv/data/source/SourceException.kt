package com.luckycatpaw.luckyfilestv.data.source

import android.content.Context
import com.luckycatpaw.luckyfilestv.R
import java.io.IOException

/** The operation a failure happened in, which decides how it is phrased for the user. */
internal enum class SourceOperation {
    LIST,
    PROPERTIES,
    READ,
    WRITE,
    RENAME,
    DELETE,
    CREATE_DIRECTORY
}

/**
 * Failure of a [FileSource].
 *
 * The message carried here is for logs and never reaches the screen: sources have no access
 * to resources, and a network source would otherwise duplicate every string a local one
 * already needs. [SourceMessages] turns the type into localised text in one place.
 */
internal sealed class SourceException(message: String, cause: Throwable? = null) : IOException(message, cause) {

    class NotFound(val path: SourcePath, val operation: SourceOperation) :
        SourceException("Missing during $operation: $path")

    class NotADirectory(val path: SourcePath) : SourceException("Not a directory: $path")

    class AccessDenied(val path: SourcePath, val operation: SourceOperation) :
        SourceException("Access denied during $operation: $path")

    class ParentMissing(val path: SourcePath) : SourceException("No parent directory: $path")

    class AlreadyExists(val name: String, cause: Throwable? = null) :
        SourceException("Already exists: $name", cause)

    class InvalidName(val name: String, val forDirectory: Boolean) : SourceException("Invalid name: $name")

    class Failed(val operation: SourceOperation, cause: Throwable? = null) :
        SourceException("Operation failed: $operation", cause)

    /** A source cannot serve this call at all, e.g. writing to a read-only protocol. */
    class Unsupported(val detail: String) : SourceException("Unsupported: $detail")

    /** Credentials are missing or were rejected. Used by network sources. */
    class AuthenticationRequired(val authority: String) : SourceException("Authentication required: $authority")

    /** The server did not answer. Used by network sources. */
    class Unreachable(val authority: String, cause: Throwable? = null) :
        SourceException("Unreachable: $authority", cause)
}

/** Single place that phrases a source failure for the user. */
internal class SourceMessages(context: Context) {

    private val appContext = context.applicationContext

    fun localize(error: Throwable, operation: SourceOperation): String = when (error) {
        is SourceException -> localizeSourceError(error)
        else -> fallback(operation)
    }

    private fun localizeSourceError(error: SourceException): String = when (error) {
        is SourceException.NotFound -> appContext.getString(
            if (error.operation == SourceOperation.LIST || error.operation == SourceOperation.CREATE_DIRECTORY) {
                R.string.target_folder_missing
            } else {
                R.string.file_or_folder_missing
            }
        )

        is SourceException.NotADirectory -> appContext.getString(R.string.target_folder_missing)

        is SourceException.AccessDenied -> appContext.getString(
            when (error.operation) {
                SourceOperation.LIST, SourceOperation.PROPERTIES, SourceOperation.READ -> R.string.folder_load_failed
                else -> R.string.target_read_only
            }
        )

        is SourceException.ParentMissing -> appContext.getString(R.string.parent_folder_missing)

        is SourceException.AlreadyExists -> appContext.getString(R.string.already_exists, error.name)

        is SourceException.InvalidName -> appContext.getString(
            if (error.forDirectory) R.string.invalid_folder_name else R.string.invalid_name
        )

        is SourceException.Failed -> fallback(error.operation)

        is SourceException.Unsupported -> appContext.getString(R.string.error_generic)

        is SourceException.AuthenticationRequired -> appContext.getString(R.string.share_authentication_failed)

        is SourceException.Unreachable -> appContext.getString(R.string.storage_source_unavailable)
    }

    private fun fallback(operation: SourceOperation): String = appContext.getString(
        when (operation) {
            SourceOperation.LIST -> R.string.folder_load_failed
            SourceOperation.PROPERTIES -> R.string.properties_read_failed
            SourceOperation.RENAME -> R.string.rename_failed
            SourceOperation.CREATE_DIRECTORY -> R.string.folder_create_failed
            SourceOperation.READ, SourceOperation.WRITE, SourceOperation.DELETE -> R.string.error_generic
        }
    )
}

/** Carries the already localised text of a failed operation to the UI. */
internal class FileOperationException(message: String, cause: Throwable?) : IOException(message, cause)
