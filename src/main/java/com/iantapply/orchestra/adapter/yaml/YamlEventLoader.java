package com.iantapply.orchestra.adapter.yaml;

import com.iantapply.orchestra.api.ActionSpec;
import com.iantapply.orchestra.api.ConditionSpec;
import com.iantapply.orchestra.api.EventDefinition;
import com.iantapply.orchestra.api.RecurringSchedule;
import com.iantapply.orchestra.api.RetryPolicy;
import com.iantapply.orchestra.api.StageDefinition;
import com.iantapply.orchestra.api.TargetSelector;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Loads and validates event definitions from Bukkit-compatible YAML files. */
public final class YamlEventLoader {
    /** Creates a stateless YAML event loader. */
    public YamlEventLoader() {
    }

    /**
     * Loads one event definition.
     *
     * @param path regular YAML file to load
     * @return validated immutable event definition
     * @throws IOException when the path is not a readable regular file
     * @throws IllegalArgumentException when the definition is invalid
     */
    public EventDefinition load(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException("Not a regular file: " + path);
        }
        YamlConfiguration document = YamlConfiguration.loadConfiguration(path.toFile());
        return parse(normalizeMap(document.getValues(false)));
    }

    private EventDefinition parse(Map<?, ?> root) {
        String id = required(root, "id");
        String name = string(valueOr(root, "display-name", id));
        TargetSelector target = targets(map(root.get("targets")));
        RecurringSchedule schedule = schedule(map(root.get("schedule")));
        List<?> rawStages = list(root.get("stages"));
        List<StageDefinition> stages = new ArrayList<>(rawStages.size());
        for (Object item : rawStages) {
            Map<?, ?> stage = map(item);
            List<ActionSpec> actions = actions(list(stage.get("actions")));
            List<ConditionSpec> conditions = conditions(list(stage.get("conditions")));
            stages.add(new StageDefinition(required(stage, "id"), DurationParser.parse(stage.get("duration")),
                    stage.containsKey("timeout") ? DurationParser.parse(stage.get("timeout")) : null,
                    conditions, actions));
        }
        return new EventDefinition(id, name, schedule, target, stages);
    }

    private RecurringSchedule schedule(Map<?, ?> value) {
        if (value.isEmpty() || value.get("cron") == null) {
            return null;
        }
        String zone = string(valueOr(value, "timezone", "UTC"));
        return new RecurringSchedule(string(value.get("cron")), java.time.ZoneId.of(zone));
    }

    private TargetSelector targets(Map<?, ?> value) {
        Set<String> servers = strings(value.get("servers"));
        Set<String> groups = strings(value.get("groups"));
        Map<String, String> tags = new HashMap<>();
        for (var entry : map(value.get("tags")).entrySet()) {
            tags.put(string(entry.getKey()), string(entry.getValue()));
        }
        boolean all = Boolean.parseBoolean(string(valueOr(value, "all-online", false)));
        return new TargetSelector(servers, groups, tags, all);
    }

    private List<ActionSpec> actions(List<?> values) {
        List<ActionSpec> result = new ArrayList<>(values.size());
        int index = 0;
        for (Object value : values) {
            Map<?, ?> raw = map(value);
            String explicitType = raw.containsKey("type") ? string(raw.get("type")) : null;
            String type = explicitType != null ? explicitType : raw.keySet().stream()
                    .map(YamlEventLoader::string).filter(k -> !Set.of("id", "retry").contains(k)).findFirst().orElseThrow();
            Map<String, Object> arguments = new HashMap<>();
            if (explicitType != null) {
                copyActionArguments(raw, arguments);
            } else {
                Object shorthand = raw.get(type);
                if (shorthand instanceof Map<?, ?> nested) {
                    nested.forEach((key, nestedValue) -> arguments.put(string(key), nestedValue));
                } else {
                    arguments.put(defaultArgument(type), shorthand);
                }
            }
            String actionId = raw.containsKey("id") ? string(raw.get("id")) : type + "_" + index++;
            result.add(new ActionSpec(actionId, type, arguments, retry(map(raw.get("retry")))));
        }
        return result;
    }

    private List<ConditionSpec> conditions(List<?> values) {
        List<ConditionSpec> result = new ArrayList<>();
        for (Object value : values) {
            Map<?, ?> raw = map(value);
            String type = required(raw, "type");
            Map<String, Object> args = new HashMap<>();
            raw.forEach((key, argumentValue) -> {
                if (!"type".equals(string(key))) {
                    args.put(string(key), argumentValue);
                }
            });
            result.add(new ConditionSpec(type, args));
        }
        return result;
    }

    private RetryPolicy retry(Map<?, ?> raw) {
        if (raw.isEmpty()) {
            return RetryPolicy.DEFAULT;
        }
        return new RetryPolicy(integer(valueOr(raw, "max-attempts", 3)),
                DurationParser.parse(valueOr(raw, "initial-delay", "1s")),
                Double.parseDouble(string(valueOr(raw, "multiplier", 2))),
                DurationParser.parse(valueOr(raw, "maximum-delay", "30s")));
    }

    private static String defaultArgument(String type) {
        return switch (type) {
            case "broadcast", "title", "action_bar", "discord_webhook" -> "message";
            case "command" -> "execute";
            default -> "value";
        };
    }

    private static void copyActionArguments(Map<?, ?> source, Map<String, Object> destination) {
        Set<String> reservedKeys = Set.of("id", "type", "retry");
        source.forEach((key, value) -> {
            String argumentName = string(key);
            if (!reservedKeys.contains(argumentName)) {
                destination.put(argumentName, value);
            }
        });
    }

    private static Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static Set<String> strings(Object value) {
        if (value == null) {
            return Set.of();
        }
        if (value instanceof List<?> list) {
            Set<String> result = new HashSet<>();
            list.forEach(item -> result.add(string(item)));
            return result;
        }
        return Set.of(string(value));
    }

    private static String required(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null || string(value).isBlank()) throw new IllegalArgumentException("Missing required key: " + key);
        return string(value);
    }
    private static String string(Object value) {
        return String.valueOf(value);
    }

    private static int integer(Object value) {
        return Integer.parseInt(string(value));
    }
    private static Object valueOr(Map<?, ?> map, String key, Object fallback) {
        Object value = map.get(key);
        return value == null ? fallback : value;
    }

    private static Map<String, Object> normalizeMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(string(key), normalize(value)));
        return result;
    }

    private static Object normalize(Object value) {
        if (value instanceof ConfigurationSection section) return normalizeMap(section.getValues(false));
        if (value instanceof Map<?, ?> map) return normalizeMap(map);
        if (value instanceof List<?> list) return list.stream().map(YamlEventLoader::normalize).toList();
        return value;
    }
}
