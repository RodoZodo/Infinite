package rodozodo.infinite.client.core.vk;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class ShaderSources {
    private static final String IMPORT = "#import";

    private ShaderSources() {}

    private static final Map<String, String> LOADED = new ConcurrentHashMap<>();

    static String load(String id) {
        return LOADED.computeIfAbsent(id, key -> {
            var out = new StringBuilder("#version 460 core\n");
            expand(key, out, new ArrayDeque<>(), new HashSet<>());
            return out.toString();
        });
    }

    private static void expand(String id, StringBuilder out, Deque<String> chain, Set<String> included) {
        if (chain.contains(id)) {
            throw new IllegalStateException("Shader import cycle: " + String.join(" -> ", chain) + " -> " + id);
        }
        if (!included.add(id)) return;
        chain.push(id);
        for (String line : read(id).split("\\R", -1)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("#version")) {
                continue;
            }
            if (trimmed.startsWith(IMPORT)) {
                expand(importTarget(trimmed, id), out, chain, included);
            } else {
                out.append(line).append('\n');
            }
        }
        chain.pop();
    }

    private static String importTarget(String line, String from) {
        int open = line.indexOf('<'), close = line.lastIndexOf('>');
        String target = open > 0 && close > open ? line.substring(open + 1, close).strip() : "";
        if (target.indexOf(':') <= 0 || !line.substring(IMPORT.length(), open).isBlank()) {
            throw new IllegalArgumentException("Malformed import in " + from + ": " + line);
        }
        return target;
    }

    private static String read(String id) {
        int split = id.indexOf(':');
        String path = "/assets/" + id.substring(0, split) + "/shaders/" + id.substring(split + 1);
        try (InputStream in = ShaderSources.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalArgumentException("Missing shader asset " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read shader asset " + path, e);
        }
    }
}
