package com.luckycatpaw.luckyfilestv.data.transfer

import com.luckycatpaw.luckyfilestv.data.source.SourcePath
import com.luckycatpaw.luckyfilestv.data.source.smb.SmbShare
import java.io.File
import java.util.Locale

/**
 * Identity used for transfer comparisons, without changing the path shown in the browser.
 *
 * SmbFileSource resolves host and share names without case. The planner and the destructive
 * replacement guard must agree with that lookup. Names below the share remain untouched:
 * their case rules belong to the server, and lowercasing them could merge distinct files.
 * Host aliases and overlapping shares still need server-side identity information.
 */
internal fun SourcePath.transferIdentity(): SourcePath {
    if (scheme != SmbShare.SCHEME) return this
    val parts = segments
    val share = parts.firstOrNull()?.lowercase(Locale.ROOT).orEmpty()
    val path = (listOf(share) + parts.drop(1)).joinToString("/")
    return SourcePath.remote(scheme, authority.lowercase(Locale.ROOT), path)
}

/** A replacement removes the entry itself, including any source below a real directory. */
internal fun SourcePath.isSameOrChildEntryOf(parent: SourcePath): Boolean =
    entryIdentity().isSameOrChildOf(parent.entryIdentity())

private fun SourcePath.entryIdentity(): SourcePath {
    if (!isLocal) return transferIdentity()
    val entry = toFile().toPath().toAbsolutePath().normalize().toFile()
    // Resolve routes through linked parents, but keep a final link as the entry being replaced.
    val parent = entry.parentFile?.canonicalFile ?: return SourcePath.of(entry)
    return SourcePath.of(File(parent, entry.name))
}
