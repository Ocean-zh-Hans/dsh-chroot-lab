package dev.dsh.agent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class EventBus {
    interface Subscription extends AutoCloseable {
        JSONObject take() throws InterruptedException;
        void close();
    }

    private static final int LIMIT = 1000;
    private final JSONObject[] entries = new JSONObject[LIMIT];
    private final List<QueueSubscription> listeners = new ArrayList<>();
    private final ExecutorService callbacks = Executors.newCachedThreadPool();
    private final String bootId = UUID.randomUUID().toString();
    private long sequence;
    private long generation;
    private long lastUiChange;
    private long windowStarted;
    private long windowEvents;
    private String foreground = "";

    static final class Replay {
        final JSONArray events;
        final long baseSeq;
        final boolean resetRequired;

        Replay(JSONArray events, long baseSeq, boolean resetRequired) {
            this.events = events;
            this.baseSeq = baseSeq;
            this.resetRequired = resetRequired;
        }
    }

    synchronized long generation() { return generation; }
    synchronized long lastUiChange() { return lastUiChange; }
    synchronized String foreground() { return foreground; }
    synchronized long sequence() { return sequence; }
    String bootId() { return bootId; }

    synchronized void foreground(String value) {
        foreground = value == null ? "" : value;
    }

    synchronized void uiChanged(String ignored) {
        generation++;
        lastUiChange = System.currentTimeMillis();
        notifyAll();
    }

    synchronized long waitForChange(long previous, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (generation == previous) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) break;
            wait(Math.min(remaining, 1000));
        }
        return generation;
    }

    private JSONObject frame(String type, JSONObject data) {
        sequence++;
        long now = System.currentTimeMillis();
        if (windowStarted == 0 || now - windowStarted >= 1000) {
            windowStarted = now;
            windowEvents = 0;
        }
        windowEvents++;
        try {
            JSONObject event = new JSONObject().put("event", type).put("seq", sequence).put("time", now)
                    .put("ts", now).put("processId", bootId)
                    .put("data", data == null ? new JSONObject() : data);
            entries[(int) (sequence % LIMIT)] = event;
            return event;
        } catch (Exception e) {
            return null;
        }
    }

    void publish(String type, JSONObject data) {
        synchronized (this) {
            JSONObject event = frame(type, data);
            if (event == null) return;
            notifyAll();
            for (QueueSubscription target : new ArrayList<>(listeners)) target.offer(event);
        }
    }

    synchronized long queueDepth() {
        long depth = 0;
        for (QueueSubscription listener : listeners) depth += listener.queue.size();
        return depth;
    }

    synchronized long eventsPerSec() {
        return windowEvents;
    }

    private static boolean wanted(JSONObject event, Set<String> types, Set<String> packages) {
        String type = event.optString("event");
        if (type.startsWith("events.")) return true;
        if (!types.isEmpty() && !types.contains(type)) return false;
        JSONObject data = event.optJSONObject("data");
        return packages.isEmpty() || data != null && packages.contains(data.optString("package"));
    }

    private Replay replay(long since, String processId, Set<String> types, Set<String> packages) {
        long current = sequence;
        if (since <= 0) return new Replay(new JSONArray(), current, false);
        if (!bootId.equals(processId) || since > current || current - since > LIMIT) {
            return new Replay(new JSONArray(), current, true);
        }
        JSONArray result = new JSONArray();
        for (long seq = Math.max(1, since + 1); seq <= current; seq++) {
            JSONObject event = entries[(int) (seq % LIMIT)];
            if (event != null && event.optLong("seq") == seq && wanted(event, types, packages)) result.put(event);
        }
        return new Replay(result, current, false);
    }

    synchronized Replay subscribe(Subscription subscription, long since, String processId,
            Set<String> types, Set<String> packages) {
        if (listeners.size() >= 8) throw new IllegalStateException("event subscription limit reached");
        Replay replay = replay(since, processId, types, packages);
        listeners.add((QueueSubscription) subscription);
        return replay;
    }

    synchronized Subscription subscribe() {
        if (listeners.size() >= 8) throw new IllegalStateException("event subscription limit reached");
        QueueSubscription subscription = new QueueSubscription();
        listeners.add(subscription);
        return subscription;
    }

    Subscription subscribe(Consumer<JSONObject> listener) {
        QueueSubscription subscription = (QueueSubscription) subscribe();
        callbacks.execute(() -> {
            try {
                while (true) {
                    JSONObject event = subscription.take();
                    if (event == null) break;
                    listener.accept(event);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        return subscription;
    }

    Subscription subscription(Set<String> types, Set<String> packages) {
        return new QueueSubscription(types, packages);
    }

    private synchronized void remove(QueueSubscription subscription) {
        listeners.remove(subscription);
    }

    private final class QueueSubscription implements Subscription {
        private final ArrayBlockingQueue<JSONObject> queue = new ArrayBlockingQueue<>(256);
        private final Set<String> types;
        private final Set<String> packages;
        private volatile boolean closed;
        private long dropped;

        QueueSubscription() {
            this(java.util.Collections.emptySet(), java.util.Collections.emptySet());
        }

        QueueSubscription(Set<String> types, Set<String> packages) {
            this.types = new java.util.HashSet<>(types);
            this.packages = new java.util.HashSet<>(packages);
        }

        void offer(JSONObject event) {
            if (closed || !wanted(event, types, packages)) return;
            if (queue.offer(event)) return;
            queue.poll();
            dropped++;
            JSONObject notice = null;
            try {
                notice = frame("events.dropped", new JSONObject().put("count", dropped));
            } catch (Exception ignored) {
            }
            if (notice != null) queue.offer(notice);
            if (!queue.offer(event)) {
                queue.poll();
                dropped++;
                queue.offer(event);
            }
            dropped = 0;
        }

        @Override public JSONObject take() throws InterruptedException {
            while (!closed || !queue.isEmpty()) {
                JSONObject result = queue.poll(250, TimeUnit.MILLISECONDS);
                if (result != null) return result;
            }
            return null;
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            remove(this);
        }
    }
}
