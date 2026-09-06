package com.luckycatpaw.luckyfilestv.util

import kotlin.test.assertEquals
import org.junit.Test

class FileNameOptimizerTest {

    @Test
    fun `splits a release name into series heading and episode title`() {
        assertEquals(
            "Firefly S01E02\nThe Train Job",
            FileNameOptimizer.optimize("Firefly.S01E02.The.Train.Job.1080p.BluRay.x264.mkv")
        )
    }

    @Test
    fun `pads the season and episode numbers`() {
        assertEquals("Show S01E02", FileNameOptimizer.optimize("Show.s1e2.mkv"))
        assertEquals("Show S01E02", FileNameOptimizer.optimize("Show.S01E02.mkv"))
    }

    @Test
    fun `reads the x notation as well`() {
        assertEquals(
            "Columbo S01E02\nRansom for a Dead Man",
            FileNameOptimizer.optimize("Columbo.1x02.Ransom.for.a.Dead.Man.avi")
        )
    }

    @Test
    fun `keeps a double episode within one season short`() {
        assertEquals("Show S01E01-E02\nTitle", FileNameOptimizer.optimize("Show.S01E01-E02.Title.mkv"))
        assertEquals("Show S01E02-E03\nTitle", FileNameOptimizer.optimize("Show.1x02-03.Title.mkv"))
    }

    @Test
    fun `spells out both sides when a double episode crosses a season`() {
        assertEquals("Show S01E10-S02E01", FileNameOptimizer.optimize("Show.S01E10-S02E01.mkv"))
    }

    @Test
    fun `drops a scene prefix that is not part of the title`() {
        assertEquals("Tatort S01E05\nDer Fall", FileNameOptimizer.optimize("tvp.Tatort.S01E05.Der.Fall.mkv"))
    }

    @Test
    fun `joins several episode titles separated by two dots`() {
        assertEquals("Show S01E01\nFirst / Second", FileNameOptimizer.optimize("Show.S01E01.First..Second.mkv"))
    }

    @Test
    fun `leaves a name without an episode code untouched`() {
        assertEquals("Some.Movie.2019.1080p.mkv", FileNameOptimizer.optimize("Some.Movie.2019.1080p.mkv"))
        assertEquals("holiday photos.jpg", FileNameOptimizer.optimize("holiday photos.jpg"))
        assertEquals("", FileNameOptimizer.optimize(""))
        assertEquals("   ", FileNameOptimizer.optimize("   "))
    }

    @Test
    fun `leaves a name untouched when nothing is left in front of the episode code`() {
        // Without a series name there is no heading to build, so the original is kept.
        assertEquals("S01E02.Title.mkv", FileNameOptimizer.optimize("S01E02.Title.mkv"))
    }

    // Only a container extension is stripped, and the list is the one the previews and mime
    // types use. Anything else stays in the name and reads as part of the episode title.
    @Test
    fun `an extension that is not a video container ends up in the title`() {
        assertEquals("Show S01E02\nTitle txt", FileNameOptimizer.optimize("Show.S01E02.Title.txt"))
    }
}
