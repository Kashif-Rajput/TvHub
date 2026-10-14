package com.tvhub.media.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A file or folder in the USB browser. Backed either by a real path (fast, works when
 * storage permission is granted) or by a Storage Access Framework document (works when
 * the user picked the drive in the system folder picker).
 */
data class Node(
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    val kind: MediaKind?,
    val file: File? = null,
    val docUri: Uri? = null,
    val treeUri: Uri? = null,
    val docId: String? = null
) {
    val uri: Uri get() = docUri ?: Uri.fromFile(requireNotNull(file))
    val stableId: Long get() = (file?.path ?: docUri.toString()).hashCode().toLong()

    fun toEntry(): MediaEntry? {
        val k = kind ?: return null
        return MediaEntry(
            uri = uri, title = name, displayName = name, kind = k,
            sizeBytes = size, dateModified = modified, path = file?.path
        )
    }
}

object NodeLister {
    private val order = compareBy<Node> { !it.isDir }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }

    fun fileRoot(dir: File, label: String) = Node(label, true, 0, 0, null, file = dir)

    fun treeRoot(tree: Uri, label: String): Node {
        val id = DocumentsContract.getTreeDocumentId(tree)
        return Node(
            label, true, 0, 0, null,
            docUri = DocumentsContract.buildDocumentUriUsingTree(tree, id), treeUri = tree, docId = id
        )
    }

    /** Lists a folder on Dispatchers.IO. Returns null if it can't be read (permission / drive removed). */
    suspend fun list(context: Context, parent: Node): List<Node>? = withContext(Dispatchers.IO) {
        val dir = parent.file
        if (dir != null) {
            val arr = dir.listFiles() ?: return@withContext null
            val out = ArrayList<Node>(arr.size)
            for (f in arr) {
                ensureActive()
                if (f.name.startsWith(".")) continue
                val isDir = f.isDirectory
                out += Node(
                    name = f.name, isDir = isDir,
                    size = if (isDir) 0 else f.length(),
                    modified = f.lastModified(),
                    kind = if (isDir) null else MediaKind.fromName(f.name),
                    file = f
                )
            }
            out.sortWith(order)
            out
        } else {
            val tree = parent.treeUri ?: return@withContext null
            val docId = parent.docId ?: return@withContext null
            runCatching { listDocs(context, tree, docId) }.getOrNull()
        }
    }

    /** One query for all children (DocumentFile would do one query per attribute per file: very slow). */
    private fun listDocs(context: Context, tree: Uri, docId: String): List<Node>? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val proj = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val out = ArrayList<Node>()
        context.contentResolver.query(childrenUri, proj, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                if (name.startsWith(".")) continue
                val mime = c.getString(2)
                val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                out += Node(
                    name = name, isDir = isDir,
                    size = if (c.isNull(3)) 0 else c.getLong(3),
                    modified = if (c.isNull(4)) 0 else c.getLong(4),
                    kind = if (isDir) null else MediaKind.fromName(name, mime),
                    docUri = DocumentsContract.buildDocumentUriUsingTree(tree, id),
                    treeUri = tree, docId = id
                )
            }
        } ?: return null
        out.sortWith(order)
        return out
    }
}
