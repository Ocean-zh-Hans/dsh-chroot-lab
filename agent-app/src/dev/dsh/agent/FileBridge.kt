package dev.dsh.agent

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

internal object FileBridge {
    private const val CHUNK = 256 * 1024
    private const val MAX_FILE = 256L * 1024 * 1024
    private val UPLOADS = HashMap<String, File>()

    @JvmStatic
    fun call(method: String, params: JSONObject): JSONObject? = when (method) {
        "fs.put" -> put(params)
        "fs.get" -> get(params)
        "fs.list" -> list(params)
        "fs.mkdir" -> mkdir(params)
        "fs.rm" -> remove(params)
        "share.file" -> share(params)
        else -> null
    }

    @JvmStatic
    fun storageAvailable(): Boolean {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager()
        return AgentServer.context()?.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun permission() {
        if (!storageAvailable()) throw AgentError("STORAGE_PERMISSION", "grant file access in Android settings")
    }

    private fun root(requested: String?): File {
        val external = Environment.getExternalStorageDirectory()
        if (requested.isNullOrEmpty() || requested == "dsh") return File(external, ".dsh")
        if (requested == "download") return File(external, "Download")
        throw AgentError("INVALID_PARAMS", "root must be dsh or download")
    }

    @Throws(Exception::class)
    private fun path(params: JSONObject, mustExist: Boolean): File {
        permission()
        val value = params.optString("path")
        val requestedRoot = params.optString("root")
        if (value.startsWith("/")) {
            val canonical = File(value).canonicalFile
            for (name in arrayOf("dsh", "download")) {
                val base = root(name).canonicalFile
                if (canonical.path.startsWith(base.path + File.separator)) {
                    val relative = canonical.path.substring(base.path.length + 1)
                    return SafePaths.resolve(base, relative, mustExist)
                }
            }
            throw AgentError("PATH_NOT_ALLOWED", "only Download and .dsh are allowed")
        }
        try {
            return SafePaths.resolve(root(requestedRoot), value, mustExist)
        } catch (e: java.io.IOException) {
            if (mustExist && e.message.orEmpty().contains("does not exist")) throw AgentError("NOT_FOUND", e.message.orEmpty())
            throw AgentError("PATH_NOT_ALLOWED", e.message.orEmpty())
        }
    }

    private fun put(params: JSONObject): JSONObject {
        val encoded = params.getString("base64")
        if (encoded.length > CHUNK * 2) throw AgentError("INVALID_PARAMS", "chunk exceeds 256 KiB after decoding")
        val bytes = try {
            android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            throw AgentError("INVALID_PARAMS", "invalid base64")
        }
        if (bytes.size > CHUNK) throw AgentError("INVALID_PARAMS", "chunk exceeds 256 KiB")
        var id = params.optString("uploadId")
        val target = path(params, false)
        if (target.isDirectory) throw AgentError("INVALID_PARAMS", "target is a directory")
        if (target.exists() && !params.optBoolean("overwrite")) throw AgentError("CONFLICT", "target exists; overwrite requires confirmation")
        if (params.optBoolean("overwrite")) AgentError.confirm(params)
        val temp: File
        synchronized(UPLOADS) {
            if (id.isEmpty()) {
                if (UPLOADS.size >= 8) throw AgentError("INVALID_PARAMS", "too many active uploads")
                id = UUID.randomUUID().toString()
                val uploads = File(root("dsh"), ".uploads")
                if (!uploads.isDirectory && !uploads.mkdirs()) throw AgentError("PERSIST_FAILED", "cannot create upload directory")
                temp = File(uploads, id + ".part")
                UPLOADS[id] = temp
            } else {
                temp = UPLOADS[id] ?: throw AgentError("NOT_FOUND", "unknown uploadId")
            }
        }
        val total: Long
        FileOutputStream(temp, params.optBoolean("append") && temp.exists()).use { output ->
            output.write(bytes)
            output.fd.sync()
            total = output.channel.size()
        }
        if (total > MAX_FILE) throw AgentError("INVALID_PARAMS", "file exceeds 256 MiB")
        val finish = params.optBoolean("finish", !params.has("uploadId"))
        val digest = sha256(temp)
        if (finish) {
            if (params.has("size") && params.getLong("size") != total) throw AgentError("CONFLICT", "file size does not match")
            if (params.has("sha256") && !params.getString("sha256").equals(digest, ignoreCase = true)) throw AgentError("CONFLICT", "file hash does not match")
            val parent = target.parentFile
            if (parent == null || (!parent.isDirectory && !parent.mkdirs())) throw AgentError("PERSIST_FAILED", "cannot create target directory")
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            synchronized(UPLOADS) { UPLOADS.remove(id) }
        }
        return JSONObject().put("uploadId", id).put("nextOffset", total).put("complete", finish)
            .put("sha256", digest).put("path", target.path)
    }

    private fun get(params: JSONObject): JSONObject {
        val file = path(params, true)
        if (!file.isFile || file.length() > MAX_FILE) throw AgentError("INVALID_PARAMS", "not a regular file or file too large")
        val offset = maxOf(0L, params.optLong("offset"))
        if (offset > file.length()) throw AgentError("INVALID_PARAMS", "offset is past end of file")
        val length = minOf(CHUNK.toLong(), params.optInt("maxBytes", CHUNK).toLong(), file.length() - offset).toInt()
        val bytes = ByteArray(length)
        FileInputStream(file).use { input ->
            input.channel.position(offset)
            var total = 0
            while (total < length) {
                val count = input.read(bytes, total, length - total)
                if (count < 0) break
                total += count
            }
            if (total != length) throw AgentError("PERSIST_FAILED", "short file read")
        }
        val next = offset + length
        return JSONObject().put("path", file.path).put("size", file.length()).put("offset", offset)
            .put("nextOffset", next).put("eof", next == file.length()).put("sha256", sha256(file))
            .put("base64", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
    }

    private fun list(params: JSONObject): JSONObject {
        val directory = if (params.optString("path").isEmpty()) root(params.optString("root")) else path(params, true)
        val files = JSONArray()
        if (!directory.exists()) return JSONObject().put("files", files).put("path", directory.path)
        if (!directory.isDirectory) throw AgentError("INVALID_PARAMS", "not a directory")
        for (file in SafePaths.list(directory)) {
            files.put(JSONObject().put("name", file.name).put("directory", file.isDirectory)
                .put("size", file.length()).put("modified", file.lastModified()))
        }
        return JSONObject().put("files", files).put("path", directory.path)
    }

    private fun mkdir(params: JSONObject): JSONObject {
        val directory = path(params, false)
        if (!directory.isDirectory && !directory.mkdirs()) throw AgentError("PERSIST_FAILED", "cannot create directory")
        return JSONObject().put("created", true).put("path", directory.path)
    }

    private fun remove(params: JSONObject): JSONObject {
        AgentError.confirm(params)
        val file = path(params, true)
        if (file.isDirectory && !params.optBoolean("recursive")) throw AgentError("INVALID_PARAMS", "recursive is required for a directory")
        SafePaths.removeTree(file)
        return JSONObject().put("removed", true).put("path", file.path)
    }

    private fun share(params: JSONObject): JSONObject {
        val file = path(params, true)
        if (!file.isFile) throw AgentError("INVALID_PARAMS", "not a file")
        val canonical = file.canonicalPath
        val download = File(Environment.getExternalStorageDirectory(), "Download").canonicalFile
        val root = if (canonical.startsWith(download.path + File.separator)) "download" else "dsh"
        val base = if (root == "download") download else File(Environment.getExternalStorageDirectory(), ".dsh").canonicalFile
        val relative = canonical.substring(base.path.length + 1)
        val uri = Uri.Builder().scheme("content").authority("dev.dsh.agent.files")
            .appendPath(root).appendPath(relative).build()
        val intent = Intent(Intent.ACTION_SEND).setType(params.optString("mimeType", "application/octet-stream"))
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        if (params.optString("package").isNotEmpty()) intent.setPackage(params.getString("package"))
        try {
            val ctx = AgentServer.context() ?: throw AgentError("INTERNAL", "agent is not initialized")
            ctx.startActivity(if (params.optString("package").isEmpty()) Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) else intent)
        } catch (e: SecurityException) {
            return shareRoot(uri, intent, params)
        } catch (e: ActivityNotFoundException) {
            return shareRoot(uri, intent, params)
        }
        return JSONObject().put("launchRequested", true).put("confirmed", false)
    }

    private fun shareRoot(uri: Uri, intent: Intent, params: JSONObject): JSONObject {
        if (RootClient.available()) {
            val rootParams = JSONObject().put("action", "android.intent.action.VIEW")
                .put("data", uri.toString()).put("type", intent.type).put("flags", Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (params.optString("package").isNotEmpty()) rootParams.put("package", params.getString("package"))
            return RootClient.call("activity.start", rootParams)
        }
        throw AgentError("START_REJECTED", "share activity was rejected")
    }

    @JvmStatic
    fun saveBase64(value: String, encoded: String): String {
        val params = JSONObject().put("path", value).put("base64", encoded).put("finish", true)
        return put(params).getString("path")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val result = StringBuilder()
        for (value in digest.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", value))
        return result.toString()
    }
}
