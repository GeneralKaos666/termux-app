package com.termux.app.data.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.InputStream
import java.io.OutputStream

/**
 * The parts of a SAF directory tree the transfer needs. The platform implementation is
 * [AndroidSafDocumentTree]; tests use an in-memory one.
 */
interface SafDocumentTree {

    /** One document in the tree; [size] is null when the provider does not report one. */
    data class Document(val id: String, val name: String, val size: Long?)

    /** Display name of the picked directory. */
    fun displayName(): String

    /** Children of [parent], or of the tree root when [parent] is null. */
    fun children(parent: Document?): List<Document>

    /** Creates a subdirectory of the tree root. */
    fun createDirectory(name: String): Document

    /** Creates a file inside [parent]. */
    fun createFile(parent: Document, name: String): Document

    fun openInput(document: Document): InputStream

    fun openOutput(document: Document): OutputStream

    fun delete(document: Document): Boolean
}

/** [SafDocumentTree] over a tree URI granted by `ACTION_OPEN_DOCUMENT_TREE`. */
class AndroidSafDocumentTree(
    private val context: Context,
    private val treeUri: Uri,
) : SafDocumentTree {

    override fun displayName(): String {
        val id = DocumentsContract.getTreeDocumentId(treeUri)
        return queryDocument(id)?.name ?: id
    }

    override fun children(parent: SafDocumentTree.Document?): List<SafDocumentTree.Document> {
        val parentId = parent?.id ?: DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val documents = mutableListOf<SafDocumentTree.Document>()
        context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val size = if (cursor.isNull(2)) null else cursor.getLong(2)
                documents += SafDocumentTree.Document(cursor.getString(0), cursor.getString(1), size)
            }
        }
        return documents
    }

    override fun createDirectory(name: String): SafDocumentTree.Document {
        val created = DocumentsContract.createDocument(
            context.contentResolver,
            childUri(DocumentsContract.getTreeDocumentId(treeUri)),
            DocumentsContract.Document.MIME_TYPE_DIR,
            name,
        ) ?: error("could not create directory: $name")
        return documentOf(created)
    }

    override fun createFile(parent: SafDocumentTree.Document, name: String): SafDocumentTree.Document {
        val created = DocumentsContract.createDocument(
            context.contentResolver,
            childUri(parent.id),
            "application/octet-stream",
            name,
        ) ?: error("could not create file: $name")
        return documentOf(created)
    }

    override fun openInput(document: SafDocumentTree.Document): InputStream {
        return context.contentResolver.openInputStream(documentUri(document.id))
            ?: error("could not open ${document.name}")
    }

    override fun openOutput(document: SafDocumentTree.Document): OutputStream {
        // "wt" truncates, so a re-created document never keeps stale bytes.
        return context.contentResolver.openOutputStream(documentUri(document.id), "wt")
            ?: error("could not open ${document.name}")
    }

    override fun delete(document: SafDocumentTree.Document): Boolean {
        return DocumentsContract.deleteDocument(context.contentResolver, documentUri(document.id))
    }

    private fun childUri(parentId: String): Uri =
        DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)

    private fun documentUri(documentId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    private fun documentOf(uri: Uri): SafDocumentTree.Document {
        val id = DocumentsContract.getDocumentId(uri)
        return queryDocument(id) ?: SafDocumentTree.Document(id, id, null)
    }

    private fun queryDocument(documentId: String): SafDocumentTree.Document? {
        val uri = documentUri(documentId)
        return context.contentResolver.query(
            uri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val size = if (cursor.isNull(2)) null else cursor.getLong(2)
            SafDocumentTree.Document(cursor.getString(0), cursor.getString(1), size)
        }
    }
}
