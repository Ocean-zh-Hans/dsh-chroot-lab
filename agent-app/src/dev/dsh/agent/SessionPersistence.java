package dev.dsh.agent;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class SessionPersistence {
    static final class Saved {
        String pkg;
        String activity;
        int epoch;
        JSONArray nodes;
        JSONArray flags;
    }

    private static final byte[] MAGIC = {'D', 'S', 'H', 'S', 'E', 'S', '1'};
    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor();
    private static final Map<String, ScheduledFuture<?>> PENDING = new ConcurrentHashMap<>();

    private SessionPersistence() {
    }

    static JSONObject saveNow(SessionStore.Session session, String name, boolean includeText) throws Exception {
        checkName(name);
        JSONObject document;
        synchronized (session) {
            JSONArray nodes = new JSONArray();
            for (SessionNode node : session.nodes) nodes.put(node.savedJson(includeText));
            JSONArray flags = new JSONArray();
            int flagsSkipped = 0;
            for (Map.Entry<String, SessionStore.Flag> entry : session.flags.entrySet()) {
                if (!includeText && containsText(entry.getValue().selector)) {
                    flagsSkipped++;
                    continue;
                }
                flags.put(new JSONObject().put("name", entry.getKey()).put("selector", entry.getValue().selector));
            }
            document = new JSONObject().put("schemaVersion", 1).put("keyVersion", 1)
                    .put("sessionId", session.id).put("pkg", session.pkg).put("activity", session.activity)
                    .put("savedAt", System.currentTimeMillis()).put("epoch", session.epoch)
                    .put("nodes", nodes).put("flags", flags);
            document.put("flagsSkipped", flagsSkipped);
        }
        byte[] plain = gzip(document.toString().getBytes(StandardCharsets.UTF_8));
        if (plain.length > 2 * 1024 * 1024 && includeText) return saveNow(session, name, false).put("skeleton", true);
        if (plain.length > 2 * 1024 * 1024) throw new AgentError("SNAPSHOT_TOO_LARGE", "session exceeds 2 MB");
        boolean encrypted = true;
        byte[] bytes;
        try {
            byte[] iv = new byte[12];
            new java.security.SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            byte[] ciphertext = cipher.doFinal(plain);
            bytes = new byte[MAGIC.length + iv.length + ciphertext.length];
            System.arraycopy(MAGIC, 0, bytes, 0, MAGIC.length);
            System.arraycopy(iv, 0, bytes, MAGIC.length, iv.length);
            System.arraycopy(ciphertext, 0, bytes, MAGIC.length + iv.length, ciphertext.length);
        } catch (Exception e) {
            encrypted = false;
            bytes = plain;
        }
        if (bytes.length > 2 * 1024 * 1024) throw new AgentError("SNAPSHOT_TOO_LARGE", "session exceeds 2 MB");
        File directory = new File(AgentServer.context().getFilesDir(), "sessions");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new AgentError("PERSIST_FAILED", "cannot create session directory");
        directory.setReadable(false, false);
        directory.setReadable(true, true);
        directory.setWritable(false, false);
        directory.setWritable(true, true);
        directory.setExecutable(false, false);
        directory.setExecutable(true, true);
        File target = new File(directory, name + ".json.gz");
        File temp = new File(directory, name + "." + UUID.randomUUID() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temp)) {
            output.write(bytes);
            output.getFD().sync();
        }
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            temp.delete();
            throw new AgentError("PERSIST_FAILED", "cannot atomically publish session");
        }
        target.setReadable(false, false);
        target.setReadable(true, true);
        target.setWritable(false, false);
        target.setWritable(true, true);
        target.setExecutable(false, false);
        return new JSONObject().put("saved", true).put("name", name).put("bytes", bytes.length)
                .put("encrypted", encrypted).put("includeText", includeText)
                .put("flagsSkipped", document.getInt("flagsSkipped"));
    }

    static Saved load(String name) throws Exception {
        checkName(name);
        File file = new File(new File(AgentServer.context().getFilesDir(), "sessions"), name + ".json.gz");
        if (!file.isFile() || file.length() > 2 * 1024 * 1024) throw new AgentError("NOT_FOUND", "saved session not found or too large");
        byte[] stored;
        try (FileInputStream input = new FileInputStream(file)) {
            stored = new byte[(int) file.length()];
            int offset = 0;
            while (offset < stored.length) {
                int count = input.read(stored, offset, stored.length - offset);
                if (count < 0) break;
                offset += count;
            }
            if (offset != stored.length) throw new AgentError("PERSIST_FAILED", "short session read");
        }
        byte[] compressed = stored;
        if (stored.length > MAGIC.length && Arrays.equals(MAGIC, Arrays.copyOf(stored, MAGIC.length))) {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, stored, MAGIC.length, 12));
            compressed = cipher.doFinal(stored, MAGIC.length + 12, stored.length - MAGIC.length - 12);
        }
        String text = new String(ungzip(compressed, 8 * 1024 * 1024), StandardCharsets.UTF_8);
        JSONObject document = new JSONObject(text);
        if (document.getInt("schemaVersion") != 1) throw new AgentError("INVALID_PARAMS", "unsupported session schema");
        if (document.getInt("keyVersion") != 1) throw new AgentError("KEY_VERSION_CHANGED", "unsupported matchKey version");
        JSONArray nodes = document.getJSONArray("nodes");
        if (nodes.length() > 3000) throw new AgentError("INVALID_PARAMS", "too many saved nodes");
        Saved saved = new Saved();
        saved.pkg = document.optString("pkg");
        saved.activity = document.optString("activity");
        saved.epoch = document.optInt("epoch");
        saved.nodes = nodes;
        saved.flags = document.optJSONArray("flags") == null ? new JSONArray() : document.getJSONArray("flags");
        return saved;
    }

    static JSONObject list() throws Exception {
        File directory = new File(AgentServer.context().getFilesDir(), "sessions");
        JSONArray result = new JSONArray();
        File[] files = directory.listFiles();
        if (files != null) {
            Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
            for (File file : files) {
                if (!file.getName().endsWith(".json.gz") || result.length() >= 100) continue;
                String name = file.getName().substring(0, file.getName().length() - 8);
                JSONObject item = new JSONObject().put("name", name).put("bytes", file.length());
                try {
                    Saved saved = load(name);
                    item.put("pkg", saved.pkg).put("activity", saved.activity).put("epoch", saved.epoch);
                } catch (Exception e) {
                    item.put("readable", false);
                }
                result.put(item);
            }
        }
        return new JSONObject().put("saved", result);
    }

    static JSONObject delete(String name) throws Exception {
        checkName(name);
        File file = new File(new File(AgentServer.context().getFilesDir(), "sessions"), name + ".json.gz");
        boolean deleted = !file.exists() || file.delete();
        if (!deleted) throw new AgentError("PERSIST_FAILED", "cannot delete saved session");
        return new JSONObject().put("deleted", true).put("name", name);
    }

    static void autosave(SessionStore.Session session) {
        if (!session.autosave) return;
        ScheduledFuture<?> previous = PENDING.remove(session.id);
        if (previous != null) previous.cancel(false);
        PENDING.put(session.id, EXECUTOR.schedule(() -> {
            PENDING.remove(session.id);
            try {
                saveNow(session, "_last", session.includeText);
            } catch (Exception e) {
                Diagnostics.record("session.autosave", "app", "PERSIST_FAILED", 0);
            }
        }, session.debounceMs, TimeUnit.MILLISECONDS));
    }

    private static boolean containsText(JSONObject selector) {
        if (selector.has("expression")) return true;
        for (java.util.Iterator<String> it = selector.keys(); it.hasNext();) {
            String key = it.next();
            String field = key.replaceAll("(~=|!=|\\^=|\\$=|>=|<=|>|<|~)$", "");
            if (field.equals("text") || field.equals("desc") || field.equals("hint")) return true;
        }
        return false;
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias("dsh-agent-session-v1")) return (SecretKey) store.getKey("dsh-agent-session-v1", null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder("dsh-agent-session-v1",
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }

    private static byte[] gzip(byte[] input) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream output = new GZIPOutputStream(bytes)) {
            output.write(input);
        }
        return bytes.toByteArray();
    }

    private static byte[] ungzip(byte[] input, int limit) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPInputStream stream = new GZIPInputStream(new java.io.ByteArrayInputStream(input))) {
            byte[] buffer = new byte[8192];
            while (true) {
                int count = stream.read(buffer);
                if (count < 0) break;
                if (bytes.size() + count > limit) throw new AgentError("SNAPSHOT_TOO_LARGE", "decompressed session is too large");
                bytes.write(buffer, 0, count);
            }
        }
        return bytes.toByteArray();
    }

    private static void checkName(String name) throws AgentError {
        if (name == null || !name.matches("[A-Za-z0-9._-]{1,64}") || name.equals(".") || name.equals("..")) {
            throw new AgentError("INVALID_PARAMS", "invalid saved session name");
        }
    }
}
