package com.iantapply.orchestra.schedule;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.Month;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Five-field cron parser supporting wildcards, lists, ranges, steps, and English month/day names. */
public final class CronExpression {
    private final BitSet minutes;
    private final BitSet hours;
    private final BitSet days;
    private final BitSet months;
    private final BitSet weekdays;

    /**
     * Parses a five-field cron expression.
     *
     * @param expression minute, hour, day-of-month, month, and day-of-week fields
     */
    public CronExpression(String expression) {
        String[] fields = expression.trim().toUpperCase(Locale.ROOT).split("\\s+");
        if (fields.length != 5) throw new IllegalArgumentException("Cron must contain five fields");
        minutes = parse(fields[0], 0, 59, Map.of());
        hours = parse(fields[1], 0, 23, Map.of());
        days = parse(fields[2], 1, 31, Map.of());
        months = parse(fields[3], 1, 12, names(Month.values()));
        weekdays = parse(fields[4], 0, 7, weekdayNames());
        if (weekdays.get(7)) weekdays.set(0);
    }

    /**
     * Finds the next matching minute.
     *
     * @param after exclusive lower bound
     * @param zone evaluation time zone
     * @return next matching instant
     * @throws IllegalStateException if no match occurs within five years
     */
    public Instant nextAfter(Instant after, ZoneId zone) {
        ZonedDateTime candidate = after.atZone(zone).withSecond(0).withNano(0).plusMinutes(1);
        ZonedDateTime limit = candidate.plusYears(5);
        while (candidate.isBefore(limit)) {
            if (matches(candidate)) return candidate.toInstant();
            candidate = candidate.plusMinutes(1);
        }
        throw new IllegalStateException("No cron occurrence found within five years");
    }

    /**
     * Tests one instant against this expression.
     *
     * @param instant instant to test
     * @param zone evaluation time zone
     * @return whether all cron fields match
     */
    public boolean matches(Instant instant, ZoneId zone) {
        return matches(instant.atZone(zone));
    }

    private boolean matches(ZonedDateTime value) {
        int weekday = value.getDayOfWeek().getValue() % 7;
        return minutes.get(value.getMinute()) && hours.get(value.getHour()) && days.get(value.getDayOfMonth())
                && months.get(value.getMonthValue()) && weekdays.get(weekday);
    }

    private static BitSet parse(String field, int minimum, int maximum, Map<String, Integer> names) {
        BitSet result = new BitSet(maximum + 1);
        for (String part : field.split(",")) {
            String[] stepped = part.split("/", 2);
            int step = stepped.length == 2 ? Integer.parseInt(stepped[1]) : 1;
            if (step < 1) throw new IllegalArgumentException("Cron step must be positive");
            int start; int end;
            if ("*".equals(stepped[0])) {
                start = minimum;
                end = maximum;
            } else if (stepped[0].contains("-")) {
                String[] range = stepped[0].split("-", 2);
                start = number(range[0], names);
                end = number(range[1], names);
            } else {
                start = number(stepped[0], names);
                end = start;
            }

            if (start < minimum || end > maximum || start > end) {
                throw new IllegalArgumentException("Cron value out of range: " + part);
            }
            for (int value = start; value <= end; value += step) {
                result.set(value);
            }
        }
        return result;
    }

    private static int number(String value, Map<String, Integer> names) {
        Integer named = names.get(value);
        return named == null ? Integer.parseInt(value) : named;
    }
    private static Map<String, Integer> names(Month[] values) {
        Map<String, Integer> result = new HashMap<>();
        for (Month value : values) result.put(value.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).toUpperCase(Locale.ROOT), value.getValue());
        return result;
    }
    private static Map<String, Integer> weekdayNames() {
        Map<String, Integer> result = new HashMap<>();
        for (DayOfWeek value : DayOfWeek.values()) result.put(value.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).toUpperCase(Locale.ROOT), value.getValue() % 7);
        return result;
    }
}
