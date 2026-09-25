package com.michalkulik.mkbackup.backup

import com.michalkulik.mkbackup.core.ManifestEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffEngineTest {

    @Test
    fun `new files are additions`() {
        val result = DiffEngine.classify(emptyMap(), mapOf("a.txt" to "h1"))

        assertEquals(listOf("a.txt"), result.added)
        assertTrue(result.modified.isEmpty())
        assertTrue(result.deleted.isEmpty())
        assertTrue(result.unchanged.isEmpty())
        assertTrue(result.hasChanges)
        assertEquals(1, result.uploadCount)
    }

    @Test
    fun `same hash is unchanged`() {
        val previous = mapOf("a.txt" to entry("a.txt", "h1"))

        val result = DiffEngine.classify(previous, mapOf("a.txt" to "h1"))

        assertEquals(listOf("a.txt"), result.unchanged)
        assertFalse(result.hasChanges)
        assertEquals(0, result.uploadCount)
    }

    @Test
    fun `different hash is a modification`() {
        val previous = mapOf("a.txt" to entry("a.txt", "h1"))

        val result = DiffEngine.classify(previous, mapOf("a.txt" to "h2"))

        assertEquals(listOf("a.txt"), result.modified)
        assertTrue(result.added.isEmpty())
        assertEquals(1, result.uploadCount)
    }

    @Test
    fun `missing files are deletions`() {
        val previous = mapOf(
            "a.txt" to entry("a.txt", "h1"),
            "b.txt" to entry("b.txt", "h2"),
        )

        val result = DiffEngine.classify(previous, mapOf("a.txt" to "h1"))

        assertEquals(listOf("b.txt"), result.deleted)
        assertEquals(listOf("a.txt"), result.unchanged)
        assertTrue(result.hasChanges)
        assertEquals(0, result.uploadCount)
    }

    @Test
    fun `metadata match short circuits hashing`() {
        val previous = entry("a.txt", "h1", size = 42, modified = 7)

        assertTrue(DiffEngine.metadataMatches(previous, size = 42, modified = 7))
        assertFalse(DiffEngine.metadataMatches(previous, size = 43, modified = 7))
        assertFalse(DiffEngine.metadataMatches(previous, size = 42, modified = 8))
        assertFalse(DiffEngine.metadataMatches(null, size = 42, modified = 7))
    }

    private fun entry(path: String, sha: String, size: Long = 10, modified: Long = 1_000) =
        ManifestEntry(path = path, size = size, modified = modified, sha256 = sha)
}
