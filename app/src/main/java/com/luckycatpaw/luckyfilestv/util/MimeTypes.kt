package com.luckycatpaw.luckyfilestv.util

import android.webkit.MimeTypeMap
import java.util.Locale

internal object MimeTypes {
    const val ANY = "*/*"
    const val BINARY = "application/octet-stream"

    val VIDEO_EXTENSIONS =
        setOf("avi", "mkv", "mp4", "m4v", "mov", "webm", "mpeg", "mpg", "ts", "m2ts", "wmv", "flv", "vob")
    val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif")
    val AUDIO_EXTENSIONS =
        setOf("mp3", "m4a", "mka", "aac", "flac", "ogg", "oga", "opus", "wav", "wma", "ape", "alac", "ac3", "dts")

    fun normalize(value: String?): String? {
        val mimeType = value
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf(String::isNotBlank)
            ?: return null

        return mimeType.takeIf { parse(it) != null }
    }

    fun forFileName(fileName: String): String {
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase(Locale.ROOT)

        val knownType = when (extension) {
            "mkv" -> "video/x-matroska"
            "mp4" -> "video/mp4"
            "avi" -> "video/x-msvideo"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            else -> null
        }

        return knownType ?: MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(extension)
            ?.lowercase(Locale.ROOT)
            ?: BINARY
    }

    fun matcher(requestedMimeTypes: Iterable<String>): (String) -> Boolean {
        val requested = requestedMimeTypes.map { value ->
            ParsedRequest(
                value = value,
                parts = parse(value)
            )
        }

        if (requested.any { it.value == ANY }) {
            return { true }
        }

        return { actual ->
            val actualParts = parse(actual)

            requested.any { request ->
                val requestedParts = request.parts

                if (requestedParts == null || actualParts == null) {
                    request.value.equals(actual, ignoreCase = true)
                } else {
                    partMatches(requestedParts.first, actualParts.first) &&
                        partMatches(requestedParts.second, actualParts.second)
                }
            }
        }
    }

    fun overlap(first: String, second: String): Boolean {
        if (first == ANY || second == ANY) return true

        val firstParts = parse(first)
        val secondParts = parse(second)

        if (firstParts == null || secondParts == null) {
            return first.equals(second, ignoreCase = true)
        }

        return partsOverlap(firstParts.first, secondParts.first) &&
            partsOverlap(firstParts.second, secondParts.second)
    }

    private fun parse(value: String): Pair<String, String>? {
        val slashIndex = value.indexOf('/')

        if (
            slashIndex <= 0 ||
            slashIndex != value.lastIndexOf('/') ||
            slashIndex >= value.lastIndex
        ) {
            return null
        }

        return value.substring(0, slashIndex) to value.substring(slashIndex + 1)
    }

    private fun partMatches(requested: String, actual: String): Boolean =
        requested == "*" || requested.equals(actual, ignoreCase = true)

    private fun partsOverlap(first: String, second: String): Boolean =
        first == "*" || second == "*" || first.equals(second, ignoreCase = true)

    private data class ParsedRequest(val value: String, val parts: Pair<String, String>?)
}
