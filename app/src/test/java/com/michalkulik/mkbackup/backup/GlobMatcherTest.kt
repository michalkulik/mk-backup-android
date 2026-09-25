package com.michalkulik.mkbackup.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobMatcherTest {

    @Test
    fun `empty pattern list matches nothing`() {
        val matcher = GlobMatcher(emptyList())
        assertTrue(matcher.isEmpty)
        assertFalse(matcher.matches("DCIM/photo.jpg"))
    }

    @Test
    fun `star does not cross directory boundaries`() {
        val matcher = GlobMatcher(listOf("*.tmp"))
        assertTrue(matcher.matches("DCIM/x.tmp"))
        assertTrue(matcher.matches("x.tmp"))
        assertFalse(matcher.matches("x.tmp.bak"))
    }

    @Test
    fun `double star crosses directories`() {
        val matcher = GlobMatcher(listOf("Cache/**"))
        assertTrue(matcher.matches("Cache/a/b/c.bin"))
        assertFalse(matcher.matches("Other/a.bin"))
    }

    @Test
    fun `question mark matches a single character`() {
        val matcher = GlobMatcher(listOf("IMG_??.jpg"))
        assertTrue(matcher.matches("IMG_01.jpg"))
        assertFalse(matcher.matches("IMG_001.jpg"))
    }

    @Test
    fun `a pattern without wildcards matches as a substring`() {
        val matcher = GlobMatcher(listOf(".thumbnails"))
        assertTrue(matcher.matches("DCIM/.thumbnails/x.jpg"))
        assertFalse(matcher.matches("DCIM/photo.jpg"))
    }

    @Test
    fun `blank patterns are ignored`() {
        val matcher = GlobMatcher(listOf("  ", ""))
        assertTrue(matcher.isEmpty)
    }
}
