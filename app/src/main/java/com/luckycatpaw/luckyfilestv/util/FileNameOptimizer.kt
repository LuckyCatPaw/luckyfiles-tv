package com.luckycatpaw.luckyfilestv.util

object FileNameOptimizer {
    private data class EpisodeMatch(
        val startIndex: Int,
        val endIndex: Int,
        val episodeCode: String
    )

    // Supports S01E02, S01E01-E02, S01E01-S02E01, 1x02, and 1x02-03.
    private val seasonEpisodeRegex = Regex(
        """(?i)(?<!\d)S(\d{1,2})E(\d{1,3})(?:(?:-)?(?:S(\d{1,2}))?E?(\d{1,3}))?(?!\d)"""
    )
    private val xEpisodeRegex = Regex(
        """(?i)(?<!\d)(\d{1,2})x(\d{1,3})(?:-(?:(\d{1,2})x)?(\d{1,3}))?(?!\d)"""
    )

    // Known scene-release prefixes that are not part of a series title.
    private val releasePrefixRegex = Regex("""(?i)^(?:deli|tvp)[\s._-]+""")

    // Removes common release metadata after an episode title.
    private val releaseSuffixRegex = Regex(
        """(?ix)
            [\s._-]+
            (?:
                2160p |
                1080p |
                720p |
                576p |
                480p |
                4k |
                uhd |
                hdr10? |
                hdr |
                dolby[\s._-]*vision |
                dv |
                web[\s._-]*dl |
                webdl |
                webrip |
                bluray |
                blu[\s._-]*ray |
                bdrip |
                brrip |
                dvdrip |
                hdtv |
                x264 |
                x265 |
                h264 |
                h265 |
                hevc |
                av1 |
                xvid |
                divx |
                aac |
                ac3 |
                eac3 |
                ddp |
                dts |
                truehd |
                atmos |
                german(?:[\s._-]+dl)? |
                multi |
                dubbed |
                repack |
                proper
            )
            (?:[\s._-].*)?
            $
        """.trimIndent()
    )
    private val multipleDotsRegex = Regex("""\.{2,}""")
    private val nameSeparatorsRegex = Regex("""[._]+""")
    private val whitespaceRegex = Regex("""\s+""")

    private val knownVideoExtensions = setOf(
        "avi",
        "mkv",
        "mp4",
        "m4v",
        "mov",
        "webm",
        "mpeg",
        "mpg",
        "ts",
        "m2ts",
        "wmv",
        "flv",
        "vob"
    )

    fun optimize(fileName: String): String {
        if (fileName.isBlank()) return fileName

        // Keep non-episode file names byte-for-byte identical.
        val baseName = removeKnownVideoExtension(fileName)
        val episode = findEpisodeMatch(baseName) ?: return fileName
        val seriesName = cleanSeriesName(baseName.substring(0, episode.startIndex))

        if (seriesName.isBlank()) return fileName

        val episodeTitle = cleanEpisodeTitle(baseName.substring(episode.endIndex))
        val heading = "$seriesName ${episode.episodeCode}"

        return if (episodeTitle.isBlank()) heading else "$heading\n$episodeTitle"
    }

    private fun findEpisodeMatch(text: String): EpisodeMatch? {
        return seasonEpisodeRegex.toEpisodeMatch(text)
            ?: xEpisodeRegex.toEpisodeMatch(text)
    }

    private fun Regex.toEpisodeMatch(text: String): EpisodeMatch? {
        val match = find(text) ?: return null
        val firstSeason = match.groupValues[1].toIntOrNull() ?: return null
        val firstEpisode = match.groupValues[2].toIntOrNull() ?: return null
        val secondSeasonValue = match.groupValues[3]
        val secondEpisodeValue = match.groupValues[4]

        val episodeCode = if (secondEpisodeValue.isBlank()) {
            formatSingleEpisode(firstSeason, firstEpisode)
        } else {
            formatDoubleEpisode(
                firstSeason = firstSeason,
                firstEpisode = firstEpisode,
                secondSeason = secondSeasonValue.toIntOrNull() ?: firstSeason,
                secondEpisode = secondEpisodeValue.toIntOrNull() ?: firstEpisode
            )
        }

        return EpisodeMatch(
            startIndex = match.range.first,
            endIndex = match.range.last + 1,
            episodeCode = episodeCode
        )
    }

    private fun cleanSeriesName(value: String): String {
        val cleaned = value
            .trimNameSeparators()
            .replace(releasePrefixRegex, "")
            .normalizeSeparators()
            .trimNameSeparators()

        return cleaned.capitalizeFirstCharacter()
    }

    private fun cleanEpisodeTitle(value: String): String {
        val cleaned = value
            .trimNameSeparators()
            .replace(releaseSuffixRegex, "")
            .trimNameSeparators()

        if (cleaned.isBlank()) return ""

        // Two or more dots separate multiple episode titles.
        return cleaned
            .split(multipleDotsRegex)
            .asSequence()
            .map { it.normalizeSeparators().trimNameSeparators() }
            .filter(String::isNotBlank)
            .map { it.capitalizeFirstCharacter() }
            .joinToString(separator = " / ")
    }

    private fun String.normalizeSeparators(): String {
        return replace(nameSeparatorsRegex, " ")
            .replace(whitespaceRegex, " ")
            .trim()
    }

    private fun formatSingleEpisode(season: Int, episode: Int): String {
        return buildString {
            append('S')
            append(season.toEpisodeNumber())
            append('E')
            append(episode.toEpisodeNumber())
        }
    }

    private fun formatDoubleEpisode(
        firstSeason: Int,
        firstEpisode: Int,
        secondSeason: Int,
        secondEpisode: Int
    ): String {
        val first = formatSingleEpisode(firstSeason, firstEpisode)

        return if (firstSeason == secondSeason) {
            "$first-E${secondEpisode.toEpisodeNumber()}"
        } else {
            "$first-${formatSingleEpisode(secondSeason, secondEpisode)}"
        }
    }

    private fun Int.toEpisodeNumber(): String {
        return coerceAtLeast(0).toString().padStart(length = 2, padChar = '0')
    }

    private fun removeKnownVideoExtension(fileName: String): String {
        val lastDot = fileName.lastIndexOf('.')
        if (lastDot <= 0 || lastDot >= fileName.lastIndex) return fileName

        val extension = fileName.substring(lastDot + 1).lowercase()
        return if (extension in knownVideoExtensions) {
            fileName.substring(0, lastDot)
        } else {
            fileName
        }
    }

    private fun String.trimNameSeparators(): String = trim(' ', '.', '_', '-')

    private fun String.capitalizeFirstCharacter(): String {
        return replaceFirstChar { character ->
            if (character.isLowerCase()) character.titlecase() else character.toString()
        }
    }
}
