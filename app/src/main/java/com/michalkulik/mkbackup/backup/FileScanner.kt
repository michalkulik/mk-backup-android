package com.michalkulik.mkbackup.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * A single file discovered under one of the selected folders.
 *
 * [path] is the path relative to the selection root, prefixed with the folder's visible name so
 * that two different selections can never produce the same path.
 */
data class ScannedFile(
    val path: String,
    val size: Long,
    val modified: Long,
    val uri: Uri,
)

/**
 * Walks Storage Access Framework trees and lists every regular file below them.
 *
 * The traversal talks to `DocumentsContract` directly instead of using `DocumentFile`: the latter
 * performs one binder round trip per attribute and is an order of magnitude slower on folders with
 * thousands of entries (a camera roll alone is usually tens of thousands).
 */
object FileScanner {

    private val PROJECTION = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )

    private const val DIRECTORY_MIME = DocumentsContract.Document.MIME_TYPE_DIR

    /**
     * Scans every tree in [treeUris]. [excludePatterns] are glob patterns (`*`, `?`) matched against
     * the relative path; a matching file, or any file below a matching folder, is skipped.
     */
    fun scan(
        context: Context,
        treeUris: List<String>,
        excludePatterns: List<String> = emptyList(),
        onDiscovered: (Int) -> Unit = {},
    ): List<ScannedFile> {
        val resolver = context.contentResolver
        val matcher = GlobMatcher(excludePatterns)
        val result = ArrayList<ScannedFile>()

        treeUris.forEach { treeUriString ->
            val treeUri = runCatching { Uri.parse(treeUriString) }.getOrNull() ?: return@forEach
            val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
                ?: return@forEach
            val rootName = runCatching { displayName(resolver, treeUri, rootId) }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: "root"

            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootId)
            walk(resolver, childrenUri, rootName, matcher, result) { onDiscovered(result.size) }
        }
        return result
    }

    private fun walk(
        resolver: android.content.ContentResolver,
        childrenUri: Uri,
        parentPath: String,
        matcher: GlobMatcher,
        out: MutableList<ScannedFile>,
        onDiscovered: () -> Unit,
    ) {
        val cursor = runCatching {
            resolver.query(childrenUri, PROJECTION, null, null, null)
        }.getOrNull() ?: return

        cursor.use { c ->
            val idColumn = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeColumn = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedColumn = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

            while (c.moveToNext()) {
                val documentId = c.getString(idColumn) ?: continue
                val name = c.getString(nameColumn) ?: continue
                val mime = c.getString(mimeColumn)
                val path = "$parentPath/$name"

                if (matcher.matches(path)) continue

                if (mime == DIRECTORY_MIME) {
                    val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(childrenUri, documentId)
                    walk(resolver, childUri, path, matcher, out, onDiscovered)
                } else {
                    val uri = DocumentsContract.buildDocumentUriUsingTree(childrenUri, documentId)
                    out += ScannedFile(
                        path = path,
                        size = if (c.isNull(sizeColumn)) -1L else c.getLong(sizeColumn),
                        modified = if (c.isNull(modifiedColumn)) 0L else c.getLong(modifiedColumn),
                        uri = uri,
                    )
                    onDiscovered()
                }
            }
        }
    }

    private fun displayName(
        resolver: android.content.ContentResolver,
        treeUri: Uri,
        documentId: String,
    ): String? {
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        return resolver.query(
            documentUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }
}
