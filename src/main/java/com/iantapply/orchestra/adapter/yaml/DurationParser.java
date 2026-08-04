package com.iantapply.orchestra.adapter.yaml;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses compact event-duration values such as {@code 500ms}, {@code 5m}, or {@code 2h30m}. */
public final class DurationParser {
    private static final Pattern PART = Pattern.compile("(\\d+)(ms|s|m|h|d)");

    private DurationParser() {}

    /**
     * Parses a supported duration representation.
     *
     * @param input compact duration or ISO-8601 duration; {@code null} means zero
     * @return parsed duration
     * @throws IllegalArgumentException when the value is malformed or overflows
     */
    public static Duration parse(Object input) {
        if (input == null) return Duration.ZERO;
        String value = input.toString().trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("p")) return Duration.parse(value.toUpperCase(Locale.ROOT));
        Matcher matcher = PART.matcher(value);
        long millis = 0;
        int end = 0;
        while (matcher.find()) {
            if (matcher.start() != end) throw new IllegalArgumentException("Invalid duration: " + value);
            long amount = Long.parseLong(matcher.group(1));
            millis = Math.addExact(
                    millis,
                    switch (matcher.group(2)) {
                        case "ms" -> amount;
                        case "s" -> Math.multiplyExact(amount, 1_000);
                        case "m" -> Math.multiplyExact(amount, 60_000);
                        case "h" -> Math.multiplyExact(amount, 3_600_000);
                        case "d" -> Math.multiplyExact(amount, 86_400_000);
                        default -> throw new IllegalStateException();
                    });
            end = matcher.end();
        }
        if (end != value.length()) throw new IllegalArgumentException("Invalid duration: " + value);
        return Duration.ofMillis(millis);
    }
}
