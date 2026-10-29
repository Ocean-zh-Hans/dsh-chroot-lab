package dev.dsh.agent;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class FileBridge {
    private static final int CHUNK = 256 * 1024;
    private static final long MAX_FILE = 256 * 1024 * 1024;
    private static final Map<String, File> UPLOADS = new HashMap<>();

    private FileBridge() {
    }

    static JSONObject call(String method, JSONObject params) throws Exception {
        switch (method) {
            case "fs.put": return put(params);
            case "fs.get": return get(params);
            case "fs.list": return list(params);
            case "fs.mkdir": return mkdir(params);
            case "fs.rm": return remove(params);
            case "share.file": return share(params);
            default: return null;
        }
    }

    static boolean storageAvailable() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return AgentServer.context().checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private static void permission() throws AgentError {
        if (!storageAvailable()) throw new AgentError("STORAGE_PERMISSION", "grant file access in Android settings");
    }

    private static File root(String requested) throws Exception {
        File external = Environment.getExternalStorageDirectory();
        if (requested == null || requested.isEmpty() || requested.equals("dsh")) return new File(external, ".dsh");
        if (requested.equals("download")) return new File(external, "Download");
        throw new AgentError("INVALID_PARAMS", "root must be dsh or download");
    }

    private static File path(JSONObject params, boolean mustExist) throws Exception {
        permission();
        String value = params.optString("path");
        String requestedRoot = params.optString("root");
        if (value.startsWith("/")) {
            File canonical = new File(value).getCanonicalFile();
            for (String name : new String[]{"dsh", "download"}) {
                File base = root(name).getCanonicalFile();
                if (canonical.getPath().startsWith(base.getPath() + File.separator)) {
                    String relative = canonical.getPath().substring(base.getPath().length() + 1);
                    return SafePaths.resolve(base, relative, mustExist);
                }
            }
            throw new AgentError("PATH_NOT_ALLOWED", "only Download and .dsh are allowed");
        }
        try {
            return SafePaths.resolve(root(requestedRoot), value, mustExist);
        } catch (java.io.IOException e) {
            if (mustExist && e.getMessage().contains("does not exist")) throw new AgentError("NOT_FOUND", e.getMessage());
            throw new AgentError("PATH_NOT_ALLOWED", e.getMessage());
        }
    }

    private static JSONObject put(JSONObject params) throws Exception {
        String encoded = params.getString("base64");
        if (encoded.length() > CHUNK * 2) throw new AgentError("INVALID_PARAMS", "chunk exceeds 256 KiB after decoding");
        byte[] bytes;
        try {
            bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            throw new AgentError("INVALID_PARAMS", "invalid base64");
        }
        if (bytes.length > CHUNK) throw new AgentError("INVALID_PARAMS", "chunk exceeds 256 KiB");
        String id = params.optString("uploadId");
        File target = path(params, false);
        if (target.isDirectory()) throw new AgentError("INVALID_PARAMS", "target is a directory");
        if (target.exists() && !params.optBoolean("overwrite")) throw new AgentError("CONFLICT", "target exists; overwrite requires confirmation");
        if (params.optBoolean("overwrite")) AgentError.confirm(params);
        File temp;
        synchronized (UPLOADS) {
            if (id.isEmpty()) {
                if (UPLOADS.size() >= 8) throw new AgentError("INVALID_PARAMS", "too many active uploads");
                id = UUID.randomUUID().toString();
                File uploads = new File(root("dsh"), ".uploads");
                if (!uploads.isDirectory() && !uploads.mkdirs()) throw new AgentError("PERSIST_FAILED", "cannot create upload directory");
                temp = new File(uploads, id + ".part");
                UPLOADS.put(id, temp);
            } else {
                temp = UPLOADS.get(id);
                if (temp == null) throw new AgentError("NOT_FOUND", "unknown uploadId");
            }
        }
        long total;
        try (FileOutputStream output = new FileOutputStream(temp, params.optBoolean("append") && temp.exists())) {
            output.write(bytes);
            output.getFD().sync();
            total = output.getChannel().size();
        }
        if (total > MAX_FILE) throw new AgentError("INVALID_PARAMS", "file exceeds 256 MiB");
        boolean finish = params.optBoolean("finish", !params.has("uploadId"));
        String digest = sha256(temp);
        if (finish) {
            if (params.has("size") && params.getLong("size") != total) throw new AgentError("CONFLICT", "file size does not match");
            if (params.has("sha256") && !params.getString("sha256").equalsIgnoreCase(digest)) throw new AgentError("CONFLICT", "file hash does not match");
            File parent = target.getParentFile();
            if (parent == null || !parent.isDirectory() && !parent.mkdirs()) throw new AgentError("PERSIST_FAILED", "cannot create target directory");
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            synchronized (UPLOADS) {
                UPLOADS.remove(id);
            }
        }
        return new JSONObject().put("uploadId", id).put("nextOffset", total).put("complete", finish)
                .put("sha256", digest).put("path", target.getPath());
    }

    private static JSONObject get(JSONObject params) throws Exception {
        File file = path(params, true);
        if (!file.isFile() || file.length() > MAX_FILE) throw new AgentError("INVALID_PARAMS", "not a regular file or file too large");
        long offset = Math.max(0, params.optLong("offset"));
        if (offset > file.length()) throw new AgentError("INVALID_PARAMS", "offset is past end of file");
        int length = (int) Math.min(Math.min(CHUNK, params.optInt("maxBytes", CHUNK)), file.length() - offset);
        byte[] bytes = new byte[length];
        try (FileInputStream input = new FileInputStream(file)) {
            input.getChannel().position(offset);
            int total = 0;
            while (total < length) {
                int count = input.read(bytes, total, length - total);
                if (count < 0) break;
                total += count;
            }
            if (total != length) throw new AgentError("PERSIST_FAILED", "short file read");
        }
        long next = offset + length;
        return new JSONObject().put("path", file.getPath()).put("size", file.length()).put("offset", offset)
                .put("nextOffset", next).put("eof", next == file.length()).put("sha256", sha256(file))
                .put("base64", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP));
    }

    private static JSONObject list(JSONObject params) throws Exception {
        File directory = params.optString("path").isEmpty() ? root(params.optString("root")) : path(params, true);
        JSONArray files = new JSONArray();
        if (!directory.exists()) return new JSONObject().put("files", files).put("path", directory.getPath());
        if (!directory.isDirectory()) throw new AgentError("INVALID_PARAMS", "not a directory");
        for (File file : SafePaths.list(directory)) {
            files.put(new JSONObject().put("name", file.getName()).put("directory", file.isDirectory())
                    .put("size", file.length()).put("modified", file.lastModified()));
        }
        return new JSONObject().put("files", files).put("path", directory.getPath());
    }

    private static JSONObject mkdir(JSONObject params) throws Exception {
        File directory = path(params, false);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new AgentError("PERSIST_FAILED", "cannot create directory");
        return new JSONObject().put("created", true).put("path", directory.getPath());
    }

    private static JSONObject remove(JSONObject params) throws Exception {
        AgentError.confirm(params);
        File file = path(params, true);
        if (file.isDirectory() && !params.optBoolean("recursive")) throw new AgentError("INVALID_PARAMS", "recursive is required for a directory");
        SafePaths.removeTree(file);
        return new JSONObject().put("removed", true).put("path", file.getPath());
    }

    private static JSONObject share(JSONObject params) throws Exception {
        File file = path(params, true);
        if (!file.isFile()) throw new AgentError("INVALID_PARAMS", "not a file");
        String canonical = file.getCanonicalPath();
        File download = new File(Environment.getExternalStorageDirectory(), "Download").getCanonicalFile();
        String root = canonical.startsWith(download.getPath() + File.separator) ? "download" : "dsh";
        File base = root.equals("download") ? download : new File(Environment.getExternalStorageDirectory(), ".dsh").getCanonicalFile();
        String relative = canonical.substring(base.getPath().length() + 1);
        Uri uri = new Uri.Builder().scheme("content").authority("dev.dsh.agent.files")
                .appendPath(root).appendPath(relative).build();
        Intent intent = new Intent(Intent.ACTION_SEND).setType(params.optString("mimeType", "application/octet-stream"))
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!params.optString("package").isEmpty()) intent.setPackage(params.getString("package"));
        try {
            AgentServer.context().startActivity(params.optString("package").isEmpty() ? Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) : intent);
        } catch (SecurityException | android.content.ActivityNotFoundException e) {
            if (RootClient.available()) {
                JSONObject rootParams = new JSONObject().put("action", "android.intent.action.VIEW")
                        .put("data", uri.toString()).put("type", intent.getType()).put("flags", Intent.FLAG_GRANT_READ_URI_PERMISSION);
                if (!params.optString("package").isEmpty()) rootParams.put("package", params.getString("package"));
                return RootClient.call("activity.start", rootParams);
            }
            throw new AgentError("START_REJECTED", "share activity was rejected");
        }
        return new JSONObject().put("launchRequested", true).put("confirmed", false);
    }

    static String saveBase64(String value, String encoded) throws Exception {
        JSONObject params = new JSONObject().put("path", value).put("base64", encoded).put("finish", true);
        return put(params).getString("path");
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            while (true) {
                int count = input.read(buffer);
                if (count < 0) break;
                digest.update(buffer, 0, count);
            }
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", value));
        return result.toString();
    }
}
