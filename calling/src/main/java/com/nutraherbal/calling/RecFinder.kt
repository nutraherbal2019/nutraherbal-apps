package com.nutraherbal.calling

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.util.Locale

data class RecFile(val uri: Uri, val name: String, val size: Long, val modified: Long)

/** Finds the phone's own call recording (made by the Phone app's call recorder) in the folder the agent chose once. */
object RecFinder {
    private val exts = setOf("m4a", "mp3", "aac", "amr", "3gp", "wav", "ogg", "opus", "awb", "flac", "mp4")

    fun find(ctx: Context, treeUri: String, startMs: Long, durationSec: Int, number: String): RecFile? {
        if (treeUri.isEmpty()) return null
        val tree: Uri = try { Uri.parse(treeUri) } catch (e: Exception) { return null }
        val rootId: String = try { DocumentsContract.getTreeDocumentId(tree) } catch (e: Exception) { return null }
        val all = ArrayList<RecFile>()
        collect(ctx, tree, rootId, 0, all)
        val end = startMs + durationSec * 1000L
        val lo = startMs - 30000L
        val hi = end + 240000L
        val want = CallLogReader.digits(number)
        var best: RecFile? = null
        var bestScore = Long.MAX_VALUE
        for (f in all) {
            if (f.modified < lo || f.modified > hi) continue
            if (f.size < 2000L) continue
            var score = Math.abs(f.modified - end)
            if (want.length >= 7 && f.name.filter { c -> c.isDigit() }.contains(want)) score -= 10000000L
            if (score < bestScore) { bestScore = score; best = f }
        }
        return best
    }

    private fun collect(ctx: Context, tree: Uri, docId: String, depth: Int, out: MutableList<RecFile>) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val proj = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE
        )
        val cur = try { ctx.contentResolver.query(children, proj, null, null, null) } catch (e: Exception) { null }
        cur?.use {
            while (it.moveToNext()) {
                val id = it.getString(0) ?: continue
                val name = it.getString(1) ?: ""
                val mime = it.getString(2) ?: ""
                val mod = it.getLong(3)
                val size = it.getLong(4)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (depth < 2) collect(ctx, tree, id, depth + 1, out)
                    continue
                }
                val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (mime.startsWith("audio/") || exts.contains(ext)) out.add(RecFile(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, size, mod))
            }
        }
    }

    /** Copy the recording into the app's cache so it can be uploaded later (even offline). Max 25 MB. */
    fun copyToCache(ctx: Context, f: RecFile): File? {
        if (f.size > 25L * 1024L * 1024L) return null
        val ext = f.name.substringAfterLast('.', "m4a").lowercase(Locale.ROOT).ifEmpty { "m4a" }
        val out = File(ctx.cacheDir, "rec_" + System.currentTimeMillis() + "." + ext)
        return try {
            val ins = ctx.contentResolver.openInputStream(f.uri)
            if (ins == null) null else {
                ins.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                if (out.length() > 0L) out else { out.delete(); null }
            }
        } catch (e: Exception) { out.delete(); null }
    }
}
