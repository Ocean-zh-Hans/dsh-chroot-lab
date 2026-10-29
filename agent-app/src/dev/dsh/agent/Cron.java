package dev.dsh.agent;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

final class Cron {
    private final int[][] fields;
    private final boolean anyDay;
    private final boolean anyWeekday;

    private Cron(int[][] fields, boolean anyDay, boolean anyWeekday) {
        this.fields = fields;
        this.anyDay = anyDay;
        this.anyWeekday = anyWeekday;
    }

    static Cron parse(String expression) {
        String[] parts = expression.trim().split("\\s+");
        if (parts.length != 5) throw new IllegalArgumentException("cron requires five fields");
        int[] min = {0, 0, 1, 1, 0}, max = {0, 23, 31, 12, 7};
        int[][] fields = new int[5][];
        for (int i = 0; i < 5; i++) fields[i] = parseField(parts[i], min[i], max[i]);
        return new Cron(fields, parts[2].equals("*"), parts[4].equals("*"));
    }

    long next(long epochMilli, ZoneId zone) {
        ZonedDateTime time = Instant.ofEpochMilli(epochMilli).atZone(zone).withSecond(0).withNano(0).plusMinutes(1);
        for (int i = 0; i < 366 * 24 * 60 * 5; i++) {
            boolean day = contains(fields[2], time.getDayOfMonth());
            boolean weekday = contains(fields[4], time.getDayOfWeek().getValue() % 7);
            boolean dayMatches = anyDay && anyWeekday || anyDay ? weekday : anyWeekday ? day : day || weekday;
            if (contains(fields[3], time.getMonthValue()) && dayMatches
                    && contains(fields[1], time.getHour()) && contains(fields[0], time.getMinute())) {
                return time.toInstant().toEpochMilli();
            }
            time = time.plusMinutes(1);
        }
        throw new IllegalArgumentException("cron has no time in the next five years");
    }

    private static int[] parseField(String text, int min, int max) {
        boolean[] allowed = new boolean[max + 1];
        for (String part : text.split(",", -1)) {
            String[] stepParts = part.split("/", -1);
            if (stepParts.length > 2) throw new IllegalArgumentException("invalid cron field");
            int step = stepParts.length == 2 ? number(stepParts[1], 1, 10000) : 1;
            int start = min, end = max;
            if (!stepParts[0].equals("*")) {
                String[] range = stepParts[0].split("-", -1);
                if (range.length > 2) throw new IllegalArgumentException("invalid cron range");
                start = number(range[0], min, max);
                end = range.length == 2 ? number(range[1], start, max) : start;
            }
            for (int value = start; value <= end; value += step) allowed[value] = true;
        }
        int count = 0;
        for (boolean value : allowed) if (value) count++;
        if (count == 0) throw new IllegalArgumentException("empty cron field");
        int[] result = new int[count];
        int index = 0;
        for (int i = 0; i < allowed.length; i++) if (allowed[i]) result[index++] = i;
        return result;
    }

    private static int number(String text, int min, int max) {
        int value;
        try {
            value = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid cron number");
        }
        if (value < min || value > max) throw new IllegalArgumentException("cron value out of range");
        return value;
    }

    private static boolean contains(int[] values, int wanted) {
        for (int value : values) if (value == wanted) return true;
        return false;
    }
}
