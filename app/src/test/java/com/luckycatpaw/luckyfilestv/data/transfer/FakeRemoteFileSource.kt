package com.luckycatpaw.luckyfilestv.data.transfer

import com.luckycatpaw.luckyfilestv.data.common.model.FileProperties
import com.luckycatpaw.luckyfilestv.data.source.DirectoryListing
import com.luckycatpaw.luckyfilestv.data.source.FileEntry
import com.luckycatpaw.luckyfilestv.data.source.FileSource
import com.luckycatpaw.luckyfilestv.data.source.ListOptions
import com.luckycatpaw.luckyfilestv.data.source.SourceCapabilities
import com.luckycatpaw.luckyfilestv.data.source.SourceException
import com.luckycatpaw.luckyfilestv.data.source.SourceOperation
import com.luckycatpaw.luckyfilestv.data.source.SourcePath
import com.luckycatpaw.luckyfilestv.data.source.Volume
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * A share, in memory.
 *
 * The remote halves of a transfer talk to a source through stat, list, openInput and
 * openOutput and nothing else, so a map of paths is enough to exercise them — including the
 * shapes a real server would be tedious to arrange: a directory that refuses to be listed,
 * and one that contains itself.
 */
internal class FakeRemoteFileSource(override val id: String = "smb") : FileSource {

    override val capabilities: SourceCapabilities = SourceCapabilities(
        writable = true,
        randomAccessRead = false,
        atomicMove = false,
        cheapMetadata = false,
        requiresNetwork = true
    )

    /** Directories that answer a listing with a failure, as an unreachable share would. */
    val unlistable = mutableSetOf<String>()

    /** Bytes written through [openOutput], by path. */
    val written = mutableMapOf<String, ByteArray>()

    val created = mutableListOf<String>()
    val deleted = mutableListOf<String>()

    /** Set to have every listing answer with one directory child, however deep the walk goes. */
    var endlessChildName: String? = null

    private val nodes = mutableMapOf<String, Node>()

    fun directory(path: String, lastModified: Long = 0L) = apply {
        nodes[path] = Node(isDirectory = true, lastModified = lastModified)
    }

    fun file(path: String, content: String = "", lastModified: Long = 0L) = apply {
        nodes[path] = Node(isDirectory = false, lastModified = lastModified, content = content.toByteArray())
    }

    override suspend fun roots(): List<Volume> = emptyList()

    override suspend fun stat(path: SourcePath): FileEntry? = nodes[path.value]?.let { node ->
        FileEntry(
            path = path,
            name = path.name,
            isDirectory = node.isDirectory,
            size = node.content.size.toLong(),
            lastModified = node.lastModified
        )
    }

    override suspend fun list(path: SourcePath, options: ListOptions): DirectoryListing {
        if (path.value in unlistable) throw SourceException.AccessDenied(path, SourceOperation.LIST)

        endlessChildName?.let { name ->
            return listing(path, listOf(FileEntry(path.child(name), name, isDirectory = true, 0L, 0L)))
        }

        val prefix = "${path.value}/"
        val children = nodes
            .filterKeys { it.startsWith(prefix) && !it.removePrefix(prefix).contains('/') }
            .map { (childPath, node) ->
                val name = childPath.removePrefix(prefix)
                FileEntry(
                    path = path.child(name),
                    name = name,
                    isDirectory = node.isDirectory,
                    size = node.content.size.toLong(),
                    lastModified = node.lastModified
                )
            }
            .sortedBy { it.name }

        return listing(path, children)
    }

    override suspend fun properties(path: SourcePath): FileProperties = throw NotImplementedError()

    override suspend fun createDirectory(parent: SourcePath, name: String): SourcePath {
        created += parent.child(name).value
        nodes[parent.child(name).value] = Node(isDirectory = true, lastModified = 0L)
        return parent.child(name)
    }

    override suspend fun rename(path: SourcePath, newName: String): SourcePath = throw NotImplementedError()

    override suspend fun delete(path: SourcePath) {
        if (path.value !in nodes) throw SourceException.NotFound(path, SourceOperation.DELETE)
        deleted += path.value
        nodes.remove(path.value)
    }

    override suspend fun openInput(path: SourcePath, offset: Long): InputStream {
        val node = nodes[path.value] ?: throw SourceException.NotFound(path, SourceOperation.READ)
        return ByteArrayInputStream(node.content, offset.toInt(), node.content.size - offset.toInt())
    }

    override suspend fun openOutput(path: SourcePath, overwrite: Boolean): OutputStream {
        if (!overwrite && path.value in nodes) throw SourceException.AlreadyExists(path.name)

        return object : ByteArrayOutputStream() {
            override fun close() {
                written[path.value] = toByteArray()
                nodes[path.value] = Node(isDirectory = false, lastModified = 0L, content = toByteArray())
                super.close()
            }
        }
    }

    private fun listing(path: SourcePath, entries: List<FileEntry>) = DirectoryListing(
        path = path,
        displayName = path.name,
        writable = true,
        entries = entries
    )

    private class Node(val isDirectory: Boolean, val lastModified: Long, val content: ByteArray = ByteArray(0))
}
