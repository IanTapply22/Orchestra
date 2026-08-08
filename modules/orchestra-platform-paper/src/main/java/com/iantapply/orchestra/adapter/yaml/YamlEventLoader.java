package com.iantapply.orchestra.adapter.yaml;

import com.iantapply.orchestra.api.ActionSpec;
import com.iantapply.orchestra.api.ConditionSpec;
import com.iantapply.orchestra.api.EventDefinition;
import com.iantapply.orchestra.api.RecurringSchedule;
import com.iantapply.orchestra.api.RetryPolicy;
import com.iantapply.orchestra.api.StageDefinition;
import com.iantapply.orchestra.api.TargetSelector;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Loads schema-versioned event definitions with path-specific validation errors. */
public final class YamlEventLoader {
    /** Current event document schema. */
    public static final int SCHEMA_VERSION = 1;

    /** Creates a stateless YAML event loader. */
    public YamlEventLoader() {}

    /**
     * Loads one event definition.
     *
     * @param path regular YAML file to load
     * @return validated immutable event definition
     * @throws IOException when the path is not a readable regular file
     * @throws IllegalArgumentException when the definition is invalid
     */
    public EventDefinition load(Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("Not a regular file: " + path);
        YamlConfiguration document = YamlConfiguration.loadConfiguration(path.toFile());
        return parse(normalizeMap(document.getValues(false)));
    }

    private EventDefinition parse(Map<?, ?> root) {
        rejectUnknown(root, "", Set.of("schema-version", "id", "display-name", "schedule", "targets", "stages"));
        int schemaVersion = integer(requiredValue(root, "schema-version", "schema-version"), "schema-version");
        if (schemaVersion != SCHEMA_VERSION) {
            throw error("schema-version", "unsupported schema " + schemaVersion + "; expected " + SCHEMA_VERSION);
        }

        String id = requiredString(root, "id", "id");
        String name = optionalString(root.get("display-name"), id, "display-name");
        TargetSelector target = targets(optionalMap(root.get("targets"), "targets"));
        RecurringSchedule schedule = schedule(optionalMap(root.get("schedule"), "schedule"));
        List<?> rawStages = requiredList(root.get("stages"), "stages");
        List<StageDefinition> stages = new ArrayList<>(rawStages.size());
        Set<String> stageIds = new HashSet<>();
        for (int stageIndex = 0; stageIndex < rawStages.size(); stageIndex++) {
            String path = "stages[" + stageIndex + "]";
            Map<?, ?> stage = requiredMap(rawStages.get(stageIndex), path);
            rejectUnknown(stage, path, Set.of("id", "duration", "timeout", "conditions", "actions"));
            String stageId = requiredString(stage, "id", path + ".id");
            if (!stageIds.add(stageId)) throw error(path + ".id", "duplicate stage ID '" + stageId + "'");
            List<ActionSpec> actions =
                    actions(requiredList(stage.get("actions"), path + ".actions"), path, new HashSet<>());
            List<ConditionSpec> conditions =
                    conditions(optionalList(stage.get("conditions"), path + ".conditions"), path);
            try {
                stages.add(new StageDefinition(
                        stageId,
                        duration(valueOr(stage, "duration", "0s"), path + ".duration"),
                        stage.containsKey("timeout") ? duration(stage.get("timeout"), path + ".timeout") : null,
                        conditions,
                        actions));
            } catch (IllegalArgumentException invalid) {
                throw error(path, invalid.getMessage(), invalid);
            }
        }
        try {
            return new EventDefinition(id, name, schedule, target, stages);
        } catch (IllegalArgumentException invalid) {
            throw error("event", invalid.getMessage(), invalid);
        }
    }

    private RecurringSchedule schedule(Map<?, ?> value) {
        if (value.isEmpty()) return null;
        rejectUnknown(value, "schedule", Set.of("cron", "timezone"));
        String cron = requiredString(value, "cron", "schedule.cron");
        String zone = optionalString(value.get("timezone"), "UTC", "schedule.timezone");
        try {
            return new RecurringSchedule(cron, ZoneId.of(zone));
        } catch (RuntimeException invalid) {
            throw error("schedule", invalid.getMessage(), invalid);
        }
    }

    private TargetSelector targets(Map<?, ?> value) {
        rejectUnknown(value, "targets", Set.of("servers", "groups", "tags", "all-online"));
        Set<String> servers = strings(value.get("servers"), "targets.servers");
        Set<String> groups = strings(value.get("groups"), "targets.groups");
        Map<String, String> tags = new HashMap<>();
        for (var entry : optionalMap(value.get("tags"), "targets.tags").entrySet()) {
            tags.put(
                    string(entry.getKey(), "targets.tags"), string(entry.getValue(), "targets.tags." + entry.getKey()));
        }
        boolean all = bool(valueOr(value, "all-online", false), "targets.all-online");
        try {
            return new TargetSelector(servers, groups, tags, all);
        } catch (IllegalArgumentException invalid) {
            throw error("targets", invalid.getMessage(), invalid);
        }
    }

    private List<ActionSpec> actions(List<?> values, String stagePath, Set<String> knownIds) {
        List<ActionSpec> result = new ArrayList<>(values.size());
        for (int index = 0; index < values.size(); index++) {
            String path = stagePath + ".actions[" + index + "]";
            Map<?, ?> raw = requiredMap(values.get(index), path);
            String explicitType = raw.containsKey("type") ? string(raw.get("type"), path + ".type") : null;
            List<String> shorthandTypes = raw.keySet().stream()
                    .map(key -> string(key, path))
                    .filter(key -> !Set.of("id", "retry").contains(key))
                    .toList();
            if (explicitType == null && shorthandTypes.size() != 1) {
                throw error(path, "expected exactly one shorthand action type or an explicit 'type'");
            }
            String type = explicitType == null ? shorthandTypes.getFirst() : explicitType;
            Map<String, Object> arguments = new HashMap<>();
            if (explicitType != null) {
                copyActionArguments(raw, arguments);
            } else {
                Object shorthand = raw.get(type);
                if (shorthand instanceof Map<?, ?> nested) {
                    nested.forEach((key, nestedValue) -> arguments.put(string(key, path), nestedValue));
                } else {
                    arguments.put(defaultArgument(type), shorthand);
                }
            }
            String actionId = raw.containsKey("id") ? string(raw.get("id"), path + ".id") : type + "_" + index;
            if (!knownIds.add(actionId)) throw error(path + ".id", "duplicate action ID '" + actionId + "'");
            try {
                result.add(new ActionSpec(
                        actionId, type, arguments, retry(optionalMap(raw.get("retry"), path + ".retry"), path)));
            } catch (IllegalArgumentException invalid) {
                throw error(path, invalid.getMessage(), invalid);
            }
        }
        return result;
    }

    private List<ConditionSpec> conditions(List<?> values, String stagePath) {
        List<ConditionSpec> result = new ArrayList<>(values.size());
        for (int index = 0; index < values.size(); index++) {
            String path = stagePath + ".conditions[" + index + "]";
            Map<?, ?> raw = requiredMap(values.get(index), path);
            String type = requiredString(raw, "type", path + ".type");
            Map<String, Object> args = new HashMap<>();
            raw.forEach((key, argumentValue) -> {
                if (!"type".equals(string(key, path))) args.put(string(key, path), argumentValue);
            });
            try {
                result.add(new ConditionSpec(type, args));
            } catch (IllegalArgumentException invalid) {
                throw error(path, invalid.getMessage(), invalid);
            }
        }
        return result;
    }

    private RetryPolicy retry(Map<?, ?> raw, String actionPath) {
        if (raw.isEmpty()) return RetryPolicy.DEFAULT;
        String path = actionPath + ".retry";
        rejectUnknown(raw, path, Set.of("max-attempts", "initial-delay", "multiplier", "maximum-delay"));
        try {
            return new RetryPolicy(
                    integer(valueOr(raw, "max-attempts", 3), path + ".max-attempts"),
                    duration(valueOr(raw, "initial-delay", "1s"), path + ".initial-delay"),
                    decimal(valueOr(raw, "multiplier", 2), path + ".multiplier"),
                    duration(valueOr(raw, "maximum-delay", "30s"), path + ".maximum-delay"));
        } catch (IllegalArgumentException invalid) {
            if (invalid.getMessage() != null && invalid.getMessage().startsWith(path)) throw invalid;
            throw error(path, invalid.getMessage(), invalid);
        }
    }

    private static String defaultArgument(String type) {
        return switch (type) {
            case "broadcast", "title", "action_bar", "discord_webhook" -> "message";
            case "command" -> "execute";
            default -> "value";
        };
    }

    private static void copyActionArguments(Map<?, ?> source, Map<String, Object> destination) {
        source.forEach((key, value) -> {
            String name = String.valueOf(key);
            if (!Set.of("id", "type", "retry").contains(name)) destination.put(name, value);
        });
    }

    private static void rejectUnknown(Map<?, ?> value, String path, Set<String> allowed) {
        for (Object rawKey : value.keySet()) {
            String key = String.valueOf(rawKey);
            if (!allowed.contains(key)) throw error(join(path, key), "unknown field");
        }
    }

    private static Map<?, ?> requiredMap(Object value, String path) {
        if (value instanceof Map<?, ?> map) return map;
        throw error(path, "expected a map");
    }

    private static Map<?, ?> optionalMap(Object value, String path) {
        if (value == null) return Map.of();
        return requiredMap(value, path);
    }

    private static List<?> requiredList(Object value, String path) {
        if (value instanceof List<?> list) return list;
        throw error(path, "expected a list");
    }

    private static List<?> optionalList(Object value, String path) {
        if (value == null) return List.of();
        return requiredList(value, path);
    }

    private static Set<String> strings(Object value, String path) {
        if (value == null) return Set.of();
        Set<String> result = new HashSet<>();
        if (value instanceof List<?> list) {
            for (int index = 0; index < list.size(); index++)
                result.add(string(list.get(index), path + "[" + index + "]"));
        } else {
            result.add(string(value, path));
        }
        return result;
    }

    private static Object requiredValue(Map<?, ?> map, String key, String path) {
        Object value = map.get(key);
        if (value == null) throw error(path, "is required");
        return value;
    }

    private static Object valueOr(Map<?, ?> map, String key, Object fallback) {
        Object value = map.get(key);
        return value == null ? fallback : value;
    }

    private static String requiredString(Map<?, ?> map, String key, String path) {
        String value = string(requiredValue(map, key, path), path);
        if (value.isBlank()) throw error(path, "must not be blank");
        return value;
    }

    private static String optionalString(Object value, String fallback, String path) {
        return value == null ? fallback : string(value, path);
    }

    private static String string(Object value, String path) {
        if (value instanceof Map<?, ?> || value instanceof List<?>) throw error(path, "expected a scalar value");
        return String.valueOf(value);
    }

    private static int integer(Object value, String path) {
        try {
            return Integer.parseInt(string(value, path));
        } catch (NumberFormatException invalid) {
            throw error(path, "expected an integer", invalid);
        }
    }

    private static double decimal(Object value, String path) {
        try {
            return Double.parseDouble(string(value, path));
        } catch (NumberFormatException invalid) {
            throw error(path, "expected a number", invalid);
        }
    }

    private static boolean bool(Object value, String path) {
        String text = string(value, path);
        if (!"true".equalsIgnoreCase(text) && !"false".equalsIgnoreCase(text)) {
            throw error(path, "expected true or false");
        }
        return Boolean.parseBoolean(text);
    }

    private static java.time.Duration duration(Object value, String path) {
        try {
            return DurationParser.parse(value);
        } catch (RuntimeException invalid) {
            throw error(path, "invalid duration: " + invalid.getMessage(), invalid);
        }
    }

    private static String join(String prefix, String key) {
        return prefix.isEmpty() ? key : prefix + "." + key;
    }

    private static IllegalArgumentException error(String path, String message) {
        return new IllegalArgumentException(path + ": " + message);
    }

    private static IllegalArgumentException error(String path, String message, Throwable cause) {
        return new IllegalArgumentException(path + ": " + message, cause);
    }

    private static Map<String, Object> normalizeMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), normalize(value)));
        return result;
    }

    private static Object normalize(Object value) {
        if (value instanceof ConfigurationSection section) return normalizeMap(section.getValues(false));
        if (value instanceof Map<?, ?> map) return normalizeMap(map);
        if (value instanceof List<?> list)
            return list.stream().map(YamlEventLoader::normalize).toList();
        return value;
    }
}
