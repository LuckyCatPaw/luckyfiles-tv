package com.luckycatpaw.luckyfilestv.data.source

import android.content.Context
import com.luckycatpaw.luckyfilestv.data.common.FileTreeWalker
import com.luckycatpaw.luckyfilestv.data.source.local.LocalFileSource
import com.luckycatpaw.luckyfilestv.data.source.local.LocalVolumeRepository
import com.luckycatpaw.luckyfilestv.data.source.smb.SmbFileSource
import com.luckycatpaw.luckyfilestv.data.source.smb.SmbSessionPool
import com.luckycatpaw.luckyfilestv.data.source.smb.SmbShareRepository
import com.luckycatpaw.luckyfilestv.data.source.smb.SmbShareStore

/**
 * Routes a location to the source that owns it.
 *
 * Adding a protocol means adding one [FileSource] here; nothing above the data layer learns
 * about it. Sources are keyed by scheme, so a single network source serves all of its hosts.
 */
internal class FileSourceRegistry(private val sources: List<FileSource>) {

    init {
        val duplicates = sources.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Several sources registered for: $duplicates" }
    }

    fun source(path: SourcePath): FileSource = sources.firstOrNull { it.id == path.scheme }
        ?: throw SourceException.Unsupported(path.scheme)

    /** Entry points of every source, in registration order: local storage first. */
    suspend fun roots(): List<Volume> = sources.flatMap { it.roots() }

    suspend fun volumeAt(path: SourcePath): Volume? = roots().firstOrNull { it.path == path }

    suspend fun isRoot(path: SourcePath): Boolean = volumeAt(path) != null

    companion object {

        /** The one place that knows which sources exist. */
        fun create(
            context: Context,
            volumes: LocalVolumeRepository = LocalVolumeRepository(context),
            fileTreeWalker: FileTreeWalker = FileTreeWalker(),
            smbShares: SmbShareStore = SmbShareRepository(context)
        ): FileSourceRegistry = FileSourceRegistry(
            listOf(
                LocalFileSource(volumes, fileTreeWalker),
                SmbFileSource(smbShares, SmbSessionPool.shared(context))
            )
        )
    }
}
