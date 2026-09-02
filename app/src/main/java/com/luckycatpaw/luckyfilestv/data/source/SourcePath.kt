package com.luckycatpaw.luckyfilestv.data.source

import java.io.File
import java.util.Locale

/**
 * Location of a file or directory inside one of the mounted sources.
 *
 * Serialised form:
 * - local storage: the absolute path itself, e.g. `/storage/emulated/0/Movies`
 * - every other source: `scheme://authority/path`, e.g. `smb://nas/media/Movies`
 *
 * Local locations keep their plain path, so document ids, persisted focus paths and every
 * other string that already travels through the app stays valid. A location is therefore an
 * opaque identifier, not a URI: segments are stored unescaped and [value] must never be
 * handed to `Uri.parse`.
 *
 * All path arithmetic lives here. Nothing outside the source layer should split a path on
 * `/` or wrap it in a [File] again.
 */
@JvmInline
internal value class SourcePath private constructor(val value: String) {

    /** `true` for on-device storage, which is the only source that owns a [File]. */
    val isLocal: Boolean
        get() = value.startsWith('/')

    /** Scheme of the owning source, [LOCAL_SCHEME] for on-device storage. */
    val scheme: String
        get() = if (isLocal) LOCAL_SCHEME else value.substringBefore(SCHEME_SEPARATOR)

    /** Server or share host of a remote location, empty for on-device storage. */
    val authority: String
        get() = if (isLocal) "" else value.substringAfter(SCHEME_SEPARATOR).substringBefore('/')

    /** Last path segment, empty at the root of a source. */
    val name: String
        get() = value.substring(prefixLength).substringAfterLast('/')

    /** Lower case extension without the dot, empty when the name carries none. */
    val extension: String
        get() = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    /** `true` at the topmost location of a source: `/` locally, `smb://host` for a server. */
    val isRoot: Boolean
        get() = value.length == prefixLength

    /**
     * Containing directory, or `null` when the source has nothing above this location.
     *
     * For a remote source this can be the bare authority, which is a valid location but not
     * necessarily browsable. Callers walking upwards should stop at the entries reported by
     * [FileSource.roots], as the navigation layer does.
     */
    val parent: SourcePath?
        get() {
            if (isRoot) return null
            val separator = value.lastIndexOf('/')
            return when {
                separator < 0 -> null
                isLocal && separator == 0 -> SourcePath(ROOT)
                separator < prefixLength -> null
                else -> SourcePath(value.substring(0, separator))
            }
        }

    /** Location of [name] inside this directory. */
    fun child(name: String): SourcePath =
        SourcePath(if (value.endsWith('/')) value + name else value + '/' + name)

    /** Location of [name] next to this entry, `null` at the root of a source. */
    fun sibling(name: String): SourcePath? = parent?.child(name)

    /** `true` when this location is [other] itself or lies below it. */
    fun isSameOrChildOf(other: SourcePath): Boolean =
        value == other.value || value.startsWith(other.value.trimEnd('/') + '/')

    /** Only valid for [isLocal] locations. */
    fun toFile(): File {
        require(isLocal) { "Not a local location: $value" }
        return File(value)
    }

    override fun toString(): String = value

    /** Number of leading characters that belong to the source itself rather than the path. */
    private val prefixLength: Int
        get() = if (isLocal) {
            ROOT.length
        } else {
            value.indexOf(SCHEME_SEPARATOR) + SCHEME_SEPARATOR.length + authority.length
        }

    companion object {

        const val LOCAL_SCHEME = "file"

        private const val SCHEME_SEPARATOR = "://"
        private const val ROOT = "/"

        /**
         * @throws IllegalArgumentException if [value] is neither an absolute path nor a
         *   `scheme://authority` location. Callers taking the string from outside the data
         *   layer should run inside [FileRepository], which turns this into a failed result.
         */
        fun parse(value: String): SourcePath {
            val trimmed = value.trim()
            require(trimmed.isNotEmpty()) { "Empty location" }

            val normalised = normalise(trimmed)
            require(normalised.startsWith(ROOT) || normalised.contains(SCHEME_SEPARATOR)) {
                "Neither an absolute path nor a source location: $value"
            }
            return SourcePath(normalised)
        }

        fun parseOrNull(value: String): SourcePath? = runCatching { parse(value) }.getOrNull()

        fun of(file: File): SourcePath = SourcePath(normalise(file.absolutePath))

        fun remote(scheme: String, authority: String, path: String = ""): SourcePath {
            require(scheme.isNotBlank() && authority.isNotBlank()) { "Incomplete source location" }
            val suffix = path.trim('/')
            val base = scheme + SCHEME_SEPARATOR + authority
            return SourcePath(if (suffix.isEmpty()) base else "$base/$suffix")
        }

        private fun normalise(value: String): String = value.trimEnd('/').ifEmpty { ROOT }
    }
}
