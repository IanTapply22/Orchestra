package com.iantapply.orchestra.platform.paper;

import java.nio.file.Path;

/** Command-line entry point for validating an event-definition directory. */
public final class ValidateEventsMain {
    private ValidateEventsMain() {}

    /**
     * Validates the directory supplied as the only argument.
     *
     * @param arguments one event-directory path
     * @throws Exception when the directory cannot be read or contains invalid definitions
     */
    static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) throw new IllegalArgumentException("Expected one event directory path");
        var report = EventDefinitionDirectory.validateDirectory(Path.of(arguments[0]));
        System.out.println(report.summary());
        if (!report.valid()) throw new IllegalArgumentException(report.summary());
    }
}
