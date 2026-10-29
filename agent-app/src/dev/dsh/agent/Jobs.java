package dev.dsh.agent;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class Jobs {
    private static final class Task {
        final String id = UUID.randomUUID().toString();
        final long created = System.currentTimeMillis();
        final String method;
        final JSONObject params;
        volatile String state = "queued";
        volatile Object result;
        volatile Future<?> running;
        volatile Future<?> scheduled;
        volatile EventBus.Subscription subscription;
        volatile String schedule;
        volatile long nextRun;

        Task(String method, JSONObject params) {
            this.method = method;
            this.params = params == null ? new JSONObject() : params;
        }
    }

    private static final Map<String, Task> TASKS = new LinkedHashMap<>();
    private static final ExecutorService EXECUTOR = new java.util.concurrent.ThreadPoolExecutor(2, 8, 30,
            java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(64));
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor();
    private static Context context;

    private Jobs() {
    }

    static synchronized void init(Context ctx) {
        if (context != null) return;
        context = ctx.getApplicationContext();
        restore();
    }

    static JSONObject start(String method, JSONObject params) throws Exception {
        if (method.startsWith("job.") || method.equals("events.subscribe")) {
            throw new AgentError("INVALID_PARAMS", "job cannot contain another job or subscription");
        }
        Task task = new Task(method, params);
        synchronized (Jobs.class) {
            trim();
            TASKS.put(task.id, task);
        }
        try {
            run(task);
            return status(task.id);
        } catch (Exception e) {
            synchronized (Jobs.class) {
                TASKS.remove(task.id);
            }
            throw e;
        }
    }

    private static void run(Task task) {
        task.state = "running";
        Future<?> future = EXECUTOR.submit(() -> {
            try {
                task.result = AgentServer.dispatch(task.method, task.params);
                task.state = "done";
            } catch (Throwable error) {
                task.state = "failed";
                task.result = AgentServer.error(error instanceof AgentError ? ((AgentError) error).code : "INTERNAL",
                        "job failed");
            } finally {
                synchronized (Jobs.class) {
                    task.running = null;
                }
            }
        });
        task.running = future;
        if (task.state.equals("done") || task.state.equals("failed")) task.running = null;
    }

    static synchronized JSONObject status(String id) throws Exception {
        Task task = TASKS.get(id);
        if (task == null) throw new AgentError("NOT_FOUND", "unknown job");
        JSONObject result = new JSONObject().put("jobId", task.id).put("state", task.state)
                .put("method", task.method).put("created", task.created);
        if (task.schedule != null) result.put("schedule", task.schedule).put("nextRun", task.nextRun);
        if (task.result != null) result.put("result", task.result);
        return result;
    }

    static synchronized JSONObject cancel(String id) throws Exception {
        Task task = TASKS.get(id);
        if (task == null) throw new AgentError("NOT_FOUND", "unknown job");
        if (task.schedule == null && (task.state.equals("done") || task.state.equals("failed"))) return status(id);
        if (task.running != null) task.running.cancel(true);
        if (task.scheduled != null) task.scheduled.cancel(false);
        if (task.subscription != null) task.subscription.close();
        task.state = "cancelled";
        task.nextRun = 0;
        boolean persisted = persist();
        return status(id).put("persisted", persisted);
    }

    static synchronized JSONObject schedule(String method, JSONObject params, JSONObject spec) throws Exception {
        if (method.startsWith("job.") || method.equals("events.subscribe")) {
            throw new AgentError("INVALID_PARAMS", "scheduled job cannot contain another job or subscription");
        }
        Task task = new Task(method, params);
        task.schedule = spec.toString();
        trim();
        TASKS.put(task.id, task);
        try {
            String event = spec.optString("on");
            if (!event.isEmpty()) {
                task.subscription = AgentServer.events.subscribe(message -> {
                    if (!message.optString("event").equals(event)) return;
                    synchronized (Jobs.class) {
                        if (task.state.equals("cancelled") || task.state.equals("running")) return;
                        run(task);
                    }
                });
            } else {
                if (spec.has("at")) task.nextRun = spec.getLong("at");
                else task.nextRun = Cron.parse(spec.getString("cron")).next(System.currentTimeMillis(), ZoneId.systemDefault());
                task.state = "scheduled";
                task.scheduled = SCHEDULER.schedule(() -> {
                    run(task);
                    if (spec.has("cron")) scheduleNext(task);
                }, Math.max(0, task.nextRun - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
            }
            if (!persist()) throw new AgentError("PERSIST_FAILED", "cannot persist scheduled job");
            return status(task.id);
        } catch (Exception e) {
            cancel(task.id);
            throw e;
        }
    }

    private static void scheduleNext(Task task) {
        synchronized (Jobs.class) {
            if (task.state.equals("cancelled")) return;
            try {
                task.nextRun = Cron.parse(task.schedule == null ? "" : new JSONObject(task.schedule).getString("cron"))
                        .next(System.currentTimeMillis(), ZoneId.systemDefault());
                task.state = "scheduled";
                task.scheduled = SCHEDULER.schedule(() -> {
                    run(task);
                    scheduleNext(task);
                }, Math.max(0, task.nextRun - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                persist();
            } catch (Exception e) {
                task.state = "failed";
            }
        }
    }

    private static void trim() {
        long cutoff = System.currentTimeMillis() - 86400000;
        Iterator<Map.Entry<String, Task>> iterator = TASKS.entrySet().iterator();
        while (iterator.hasNext() && TASKS.size() >= 100) {
            Task task = iterator.next().getValue();
            if (task.running == null && task.scheduled == null && task.subscription == null && task.created < cutoff) iterator.remove();
        }
        if (TASKS.size() >= 100) throw new IllegalStateException("job limit reached");
    }

    private static File file() {
        return new File(context.getFilesDir(), "schedules.json");
    }

    private static synchronized boolean persist() {
        if (context == null) return false;
        try {
            JSONArray list = new JSONArray();
            for (Task task : TASKS.values()) {
                if (task.schedule == null || task.state.equals("cancelled")) continue;
                list.put(new JSONObject().put("method", task.method).put("params", task.params)
                        .put("schedule", task.schedule));
            }
            File temp = new File(context.getFilesDir(), "schedules-" + UUID.randomUUID() + ".tmp");
            try (FileOutputStream output = new FileOutputStream(temp)) {
                output.write(new JSONObject().put("version", 1).put("jobs", list).toString().getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            temp.setReadable(false, false);
            temp.setReadable(true, true);
            java.nio.file.Files.move(temp.toPath(), file().toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            file().setReadable(false, false);
            file().setReadable(true, true);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void restore() {
        File file = file();
        if (!file.isFile()) return;
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) Math.min(file.length(), 1048576)];
            int count = input.read(bytes);
            JSONArray list = new JSONObject(new String(bytes, 0, Math.max(0, count), StandardCharsets.UTF_8)).getJSONArray("jobs");
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.getJSONObject(i);
                schedule(item.getString("method"), item.optJSONObject("params"), new JSONObject(item.getString("schedule")));
            }
        } catch (Exception ignored) {
        }
    }
}
