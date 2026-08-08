package com.iantapply.orchestra.administration;

import com.iantapply.orchestra.api.EventDefinition;
import java.util.List;

/**
 * Immutable definition-validation result shared by CLI and platform administration adapters.
 *
 * @param definitions successfully validated definitions
 * @param errors path-specific validation errors
 */
public record DefinitionValidationReport(List<EventDefinition> definitions, List<String> errors) {
    /** Snapshots report collections. */
    public DefinitionValidationReport {
        definitions = List.copyOf(definitions);
        errors = List.copyOf(errors);
    }

    /**
     * Returns whether every inspected definition was valid.
     *
     * @return {@code true} when there are no errors
     */
    public boolean valid() {
        return errors.isEmpty();
    }

    /**
     * Returns one consistent human-readable validation summary.
     *
     * @return formatted validation summary
     */
    public String summary() {
        if (valid()) return "Validated " + definitions.size() + " event definition(s)";
        return errors.size() + " validation error(s): " + String.join("; ", errors);
    }
}
