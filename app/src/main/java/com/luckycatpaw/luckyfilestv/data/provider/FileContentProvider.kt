package com.luckycatpaw.luckyfilestv.data.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.util.Base64
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.data.source.FileSourceRegistry
import com.luckycatpaw.luckyfilestv.data.source.RandomAccessSource
import com.luckycatpaw.luckyfilestv.data.source.SourcePath
import com.luckycatpaw.luckyfilestv.util.MimeTypes
import java.io.File
import java.io.FileNotFoundException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking

/**
 * Hands a single file to another app.
 *
 * Local files are passed on as a plain descriptor. A file on a share has no path the system
 * could open, so it is served through a proxy descriptor instead: the reading app sees an
 * ordinary file and every read it performs is answered from the share. That is what lets an
 * external player seek in a video that only exists on the network.
 */
class FileContentProvider : ContentProvider() {

    private val sources: FileSourceRegistry by lazy {
        FileSourceRegistry.create(requireNotNull(context).applicationContext)
    }


    private val readerThreads = AtomicInteger(0)

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") {
            throw FileNotFoundException(requireNotNull(context).getString(R.string.read_only_access))
        }

        val location = resolveLocation(uri)

        if (location.isLocal) {
            val file = location.toFile().canonicalFile
            if (!file.exists() || !file.isFile) throw FileNotFoundException(file.absolutePath)

            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        val handle = try {
            runBlocking { sources.source(location).openRandomAccess(location) }
        } catch (failure: Exception) {
            throw FileNotFoundException(failure.message ?: location.value)
        }

        // From here the handle belongs to the descriptor, which closes it on release. If it
        // never gets that far, nobody else will: the source has to be closed here.
        return try {
            proxyDescriptor(handle)
        } catch (failure: Throwable) {
            runCatching { handle.close() }
            throw failure
        }
    }

    /**
     * Wraps a source in a descriptor another app can read from.
     *
     * The reader thread and the foreground service are counted as strictly as the handle:
     * a failure in here used to leave a live thread behind and a service that never learned
     * its reader was gone, so the ongoing notification stayed up for the rest of the session.
     */
    private fun proxyDescriptor(handle: RandomAccessSource): ParcelFileDescriptor {
        val appContext = requireNotNull(context).applicationContext
        val storageManager = appContext.getSystemService(StorageManager::class.java)

        // A thread per descriptor, not one for all of them. Every read on it is a blocking
        // request to the server, so a shared thread would put a player and the thumbnails of
        // the grid into the same queue: one preview waiting on its timeout would stall
        // playback for as long as it takes.
        val readerThread = HandlerThread("share-reader-${readerThreads.incrementAndGet()}").apply { start() }

        val descriptor = try {
            storageManager.openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY,
                SourceProxyFileDescriptor(handle) {
                    readerThread.quitSafely()
                    RemoteAccessService.descriptorClosed(appContext)
                },
                Handler(readerThread.looper)
            )
        } catch (failure: Throwable) {
            readerThread.quitSafely()
            throw failure
        }

        // Counted once the descriptor exists, not before: the release callback that takes
        // the count down again cannot run any earlier, so there is nothing to undo.
        RemoteAccessService.descriptorOpened()

        return descriptor
    }

    override fun getType(uri: Uri): String = MimeTypes.forFileName(resolveLocation(uri).name)

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val location = resolveLocation(uri)

        val requestedColumns = projection ?: arrayOf(
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE
        )

        val cursor = MatrixCursor(requestedColumns)

        val values = requestedColumns.map { column ->

            when (column) {
                OpenableColumns.DISPLAY_NAME ->
                    location.name

                OpenableColumns.SIZE ->
                    sizeOf(location)

                else ->
                    null
            }
        }.toTypedArray<Any?>()

        cursor.addRow(values)

        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    private fun sizeOf(location: SourcePath): Long? = if (location.isLocal) {
        location.toFile().length()
    } else {
        runCatching { runBlocking { sources.source(location).stat(location)?.size } }.getOrNull()
    }

    private fun resolveLocation(uri: Uri): SourcePath {
        val encoded = uri.lastPathSegment
            ?: throw FileNotFoundException()

        val decodedPath = try {
            String(
                Base64.decode(
                    encoded,
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                ),
                StandardCharsets.UTF_8
            )
        } catch (error: IllegalArgumentException) {
            throw FileNotFoundException(error.message)
        }

        val location = try {
            SourcePath.parse(decodedPath)
        } catch (error: IllegalArgumentException) {
            throw FileNotFoundException(error.message)
        }

        if (!isAllowed(location)) {
            throw SecurityException(
                requireNotNull(context).getString(R.string.path_outside_storage)
            )
        }

        return location
    }

    /**
     * A granted URI must not become a way to read arbitrary paths, so a location only passes
     * when it lies below something this app actually offers: a mounted volume, or one of the
     * configured shares.
     */
    private fun isAllowed(location: SourcePath): Boolean =
        if (location.isLocal) isAllowedFile(location.toFile()) else isBelowConfiguredRoot(location)

    private fun isBelowConfiguredRoot(location: SourcePath): Boolean = runCatching {
        runBlocking { sources.roots() }.any { volume -> location.isSameOrChildOf(volume.path) }
    }.getOrDefault(false)

    private fun isAllowedFile(file: File): Boolean {
        val context = context ?: return false

        val storageManager =
            context.getSystemService(StorageManager::class.java)

        val filePath = file.canonicalPath

        return storageManager.storageVolumes.any { volume ->

            val root = volume.directory
                ?.canonicalFile
                ?: return@any false

            val rootPath = root.canonicalPath

            filePath == rootPath ||
                filePath.startsWith(
                    rootPath + File.separator
                )
        }
    }

    companion object {

        fun createUri(context: android.content.Context, path: String): Uri {
            val encoded = Base64.encodeToString(
                path.toByteArray(StandardCharsets.UTF_8),
                Base64.URL_SAFE or
                    Base64.NO_WRAP or
                    Base64.NO_PADDING
            )

            return Uri.Builder()
                .scheme("content")
                .authority(
                    "${context.packageName}.files"
                )
                .appendPath(encoded)
                .build()
        }
    }
}
