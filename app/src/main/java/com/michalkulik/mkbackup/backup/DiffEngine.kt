package com.michalkulik.mkbackup.backup

import com.michalkulik.mkbackup.core.ManifestEntry

/**
 * The difference between the last successful backup and the state on disk right now.
 *
 * Paths are relative to the selection root (see [ScannedFile.path]). [unchanged] is only needed to
 * rebuild the manifest; it is never uploaded.
 */
data class DiffResult(
    val added: List<String>,
    val modified: List<String>,
    val deleted: List<String>,
    val unchanged: List<String>,
) {
    val hasChanges: Boolean get() = added.isNotEmpty() || modified.isNotEmpty() || deleted.isNotEmpty()

    val uploadCount: Int get() = added.size + modified.size
}

/**
 * Pure classification logic. It deliberately works on plain strings so it can be unit tested
 * without any Android types.
 *
 * A file counts as *modified* only when its content hash differs. Files whose size and modification
 * time are unchanged are short-circuited before hashing at all (see [metadataMatches]), which is
 * what makes a run over a large camera roll cheap.
 */
object DiffEngine {

    /** True when the stored entry has the same size and timestamp, so the bytes cannot differ. */
    fun metadataMatches(entry: ManifestEntry?, size: Long, modified: Long): Boolean =
        entry != null && entry.size == size && entry.modified == modified

    /**
     * @param previous what the last successful backup contains, keyed by path.
     * @param current  path → SHA-256 for everything found on disk right now.
     */
    fun classify(previous: Map<String, ManifestEntry>, current: Map<String, String>): DiffResult {
        val added = ArrayList<String>()
        val modified = ArrayList<String>()
        val unchanged = ArrayList<String>()

        for ((path, sha256) in current) {
            val entry = previous[path]
            when {
                entry == null -> added += path
                entry.sha256 == sha256 -> unchanged += path
                else -> modified += path
            }
        }

        val deleted = previous.keys.filterNot { it in current }
        return DiffResult(added = added, modified = modified, deleted = deleted, unchanged = unchanged)
    }
}
