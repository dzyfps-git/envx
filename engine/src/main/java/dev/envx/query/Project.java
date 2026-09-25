package dev.envx.query;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A Fabric mod project the agent is working in: the nearest folder at or above the working directory with a
 * Gradle build and {@code src/main/resources/fabric.mod.json}. Found from where the agent works, never by
 * scanning drives, because many folders hold other people's sources kept for reference.
 *
 * <p>Read-only and cheap: a handful of small text files, plus the newest built jar's name and time.
 */
public record Project(Path dir, String modId, String version, Map<String, String> build, Map<String, String> depends,
                      Map<String, String> optional, Map<String, String> breaks,
                      List<String> mixinConfigs, Path jar, boolean jarStale) {

    private static final Pattern LOOM = Pattern.compile("id\\s*\\(?\\s*['\"]fabric-loom['\"]\\s*\\)?\\s*version\\s*\\(?\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern JAVA = Pattern.compile(
            "(?:JavaLanguageVersion\\.of\\(\\s*|options\\.release\\s*=\\s*|release\\.set\\(\\s*|JavaVersion\\.VERSION_)(\\d+)");

    /** The project containing {@code cwd}, or null; never one inside the config's denied roots. */
    public static Project find(dev.envx.Config config, Path cwd) {
        if (cwd == null || config.isDenied(cwd)) return null;
        Project p = find(cwd);
        return p == null || config.isDenied(p.dir()) ? null : p;
    }

    /** The project containing {@code cwd}, or null. Callers with a config use {@link #find(dev.envx.Config, Path)}. */
    static Project find(Path cwd) {
        if (cwd == null) return null;
        for (Path d = cwd.toAbsolutePath().normalize(); d != null; d = d.getParent()) {
            boolean gradle = Files.exists(d.resolve("build.gradle")) || Files.exists(d.resolve("build.gradle.kts"));
            Path fmj = d.resolve("src/main/resources/fabric.mod.json");
            if (gradle && Files.exists(fmj)) {
                try {
                    return read(d, fmj);
                } catch (IOException | RuntimeException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static Project read(Path dir, Path fmj) throws IOException {
        Properties p = new Properties();
        Path props = dir.resolve("gradle.properties");
        if (Files.exists(props)) try (var r = Files.newBufferedReader(props)) {
            p.load(r);
        }
        JsonObject o = JsonParser.parseString(Files.readString(fmj)).getAsJsonObject();
        String id = o.has("id") ? o.get("id").getAsString() : null;
        String version = o.has("version") ? o.get("version").getAsString() : null;
        if (version != null && version.contains("${")) version = p.getProperty("mod_version", version);

        String gradle = "";
        for (String f : List.of("build.gradle", "build.gradle.kts")) {
            if (Files.exists(dir.resolve(f))) gradle = Files.readString(dir.resolve(f));
        }
        Map<String, String> build = new LinkedHashMap<>();
        put(build, "minecraft", p.getProperty("minecraft_version"));
        put(build, "yarn", p.getProperty("yarn_mappings"));
        put(build, "fabricloader", p.getProperty("loader_version"));
        put(build, "fabric-api", p.getProperty("fabric_version", p.getProperty("fabric_api_version")));
        Matcher java = JAVA.matcher(gradle);
        String javaVersion = null;
        while (java.find()) javaVersion = java.group(1); // the last setting wins, as in Gradle
        put(build, "java", javaVersion);
        Matcher loom = LOOM.matcher(gradle);
        String loomVersion = loom.find() ? loom.group(1) : null;
        if (loomVersion == null || loomVersion.contains("${")) loomVersion = p.getProperty("loom_version"); // version "${loom_version}"
        put(build, "loom", loomVersion);
        Path wrapper = dir.resolve("gradle/wrapper/gradle-wrapper.properties");
        if (Files.exists(wrapper)) {
            Matcher g = Pattern.compile("gradle-([\\d.]+)-(?:bin|all)\\.zip").matcher(Files.readString(wrapper));
            put(build, "gradle", g.find() ? g.group(1) : null);
        }

        Map<String, String> depends = relations(o, "depends");
        Map<String, String> optional = relations(o, "recommends", "suggests");
        Map<String, String> breaks = relations(o, "breaks", "conflicts");
        List<String> mixins = new ArrayList<>();
        if (o.has("mixins") && o.get("mixins").isJsonArray()) {
            for (JsonElement m : o.getAsJsonArray("mixins")) {
                mixins.add(m.isJsonObject() ? m.getAsJsonObject().get("config").getAsString() : m.getAsString());
            }
        }
        Path jar = builtJar(dir, id);
        // Only src/main: side source sets (test harnesses) do not make the mod jar stale.
        boolean stale = jar != null && newerSources(dir.resolve("src/main"), Files.getLastModifiedTime(jar).toMillis());
        return new Project(dir, id, version, build, depends, optional, breaks, mixins, jar, stale);
    }

    private static void put(Map<String, String> m, String k, String v) {
        if (v != null && !v.isBlank() && !v.contains("${")) m.put(k, v.trim());
    }

    private static Map<String, String> relations(JsonObject o, String... keys) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String k : keys) {
            if (o.has(k) && o.get(k).isJsonObject()) {
                for (var e : o.getAsJsonObject(k).entrySet()) m.put(e.getKey(), versionRange(e.getValue()));
            }
        }
        return m;
    }

    private static String versionRange(JsonElement v) {
        if (v.isJsonArray()) {
            List<String> parts = new ArrayList<>();
            v.getAsJsonArray().forEach(x -> parts.add(x.getAsString()));
            return String.join(" || ", parts);
        }
        return v.getAsString();
    }

    /**
     * The project's built mod jar: the newest jar in build/libs whose fabric.mod.json has the project's mod id
     * (a build can also produce test harnesses or other side jars); else the newest non-sources/dev jar.
     */
    static Path builtJar(Path dir, String modId) throws IOException {
        Path libs = dir.resolve("build/libs");
        if (!Files.isDirectory(libs)) return null;
        List<Path> jars;
        try (Stream<Path> s = Files.list(libs)) {
            jars = s.filter(f -> f.toString().endsWith(".jar"))
                    .filter(f -> !f.getFileName().toString().matches(".*-(sources|dev|javadoc|all-dev)\\.jar"))
                    .sorted(java.util.Comparator.comparingLong((Path f) -> f.toFile().lastModified()).reversed())
                    .toList();
        }
        if (modId != null) {
            for (Path j : jars) if (modId.equals(jarModId(j))) return j;
        }
        return jars.isEmpty() ? null : jars.getFirst();
    }

    private static String jarModId(Path jar) {
        try (var z = new java.util.zip.ZipFile(jar.toFile())) {
            var e = z.getEntry("fabric.mod.json");
            if (e == null) return null;
            try (var in = z.getInputStream(e)) {
                JsonObject o = JsonParser.parseString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                return o.has("id") ? o.get("id").getAsString() : null;
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static boolean newerSources(Path src, long than) throws IOException {
        if (!Files.isDirectory(src)) return false;
        try (Stream<Path> s = Files.walk(src)) {
            return s.filter(Files::isRegularFile).anyMatch(f -> {
                try {
                    return Files.getLastModifiedTime(f).toMillis() > than;
                } catch (IOException e) {
                    return false;
                }
            });
        }
    }
}
