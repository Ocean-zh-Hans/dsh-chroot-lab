package dev.dsh.agent

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

class AgentFileProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    @Throws(Exception::class)
    private fun file(uri: Uri): File {
        val parts = uri.pathSegments
        if (parts.size < 2) throw SecurityException("missing file path")
        val root = parts[0]
        if (root != "dsh" && root != "download") throw SecurityException("path is not allowed")
        val relative = StringBuilder()
        for (i in 1 until parts.size) {
            if (i > 1) relative.append('/')
            relative.append(parts[i])
        }
        val base = File(Environment.getExternalStorageDirectory(), if (root == "dsh") ".dsh" else "Download")
        val file = SafePaths.resolve(base, relative.toString(), true)
        if (!file.isFile) throw SecurityException("not a regular file")
        return file
    }

    @Throws(FileNotFoundException::class)
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read only")
        try {
            return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: FileNotFoundException) {
            throw e
        } catch (e: Exception) {
            throw SecurityException("invalid shared path")
        }
    }

    override fun getType(uri: Uri): String {
        val extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
        val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        return type ?: "application/octet-stream"
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        return try {
            val file = file(uri)
            val cursor = MatrixCursor(arrayOf("_display_name", "_size"))
            cursor.addRow(arrayOf<Any>(file.name, file.length()))
            cursor
        } catch (_: Exception) {
            null
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        throw UnsupportedOperationException("read only")
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        throw UnsupportedOperationException("read only")
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        throw UnsupportedOperationException("read only")
    }
}
