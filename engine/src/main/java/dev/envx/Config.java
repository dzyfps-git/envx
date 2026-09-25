package dev.envx;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * User configuration, stored as {@code config.json} in the data home.
 *
 * <p>Nothing is read from disk unless it is an environment source or a linked project that the
 * user explicitly added. {@link #denyRoots} are never read, even if something points inside them.
 */
public final class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Environment name -> definition. */
    public Map<String, EnvDef> environments = new LinkedHashMap<>();
    /** Project directory (absolute) -> environment name. Longest matching prefix wins. */
    public Map<String, String> projects = new LinkedHashMap<>();
    public String defaultEnv;
    /**
     * Use-time sync: when envx is used and an environment was last checked longer ago than this, a sync runs
     * in the background. 0 turns it off (then only `envx env sync` updates the index).
     */
    public double autoSyncHours = 6;
    /** Days of server logs and crash reports kept in the local mirror (env filter=errors, grep scope=logs). */
    public int logDays = 14;
    public List<String> denyRoots = List.of(); // machine-specific: listed in the data home's config.json

    public static final class EnvDef {
        /**
         * Where the server/instance lives, tried in order until one is reachable: a local folder,
         * a UNC share ({@code \\host\share}; preferred over a mapped drive letter, which may be
         * disconnected after a reboot), or {@code ssh://[user@]host/path} (host may be an ~/.ssh/config alias).
         */
        public List<String> sources = new java.util.ArrayList<>();
        /** Mapping flavour used for display and decompilation. v1 supports only Yarn 1.20.1+build.10. */
        public String mappings = "yarn:1.20.1+build.10";
        public String minecraft = "1.20.1";
        public String platform = "fabric";
        /** server or client: which side's mixins can apply here. Client-only mixins are hidden on servers. */
        public String side = "server";

        public boolean hides(String mixinSide) {
            return mixinSide != null && !mixinSide.equals("common") && side != null && !mixinSide.equals(side);
        }
    }

    private transient Path home;

    public Path home() {
        return home;
    }

    public static Path resolveHome() {
        String prop = System.getProperty("envx.home");
        if (prop != null && !prop.isBlank()) return Path.of(prop);
        String env = System.getenv("ENVX_HOME");
        if (env != null && !env.isBlank()) return Path.of(env);
        Path dflt = Path.of(System.getProperty("user.home"), ".envx");
        // ~/.envx/location holds one line, the data home, for a home kept elsewhere (another drive).
        Path location = dflt.resolve("location");
        if (Files.isRegularFile(location)) {
            try {
                String where = Files.readString(location).trim();
                if (!where.isEmpty()) return Path.of(where);
            } catch (IOException ignored) {
                // fall back to the default
            }
        }
        return dflt;
    }

    public static Config load() throws IOException {
        Path home = resolveHome();
        Files.createDirectories(home);
        Path file = home.resolve("config.json");
        Config c = Files.exists(file) ? GSON.fromJson(Files.readString(file), Config.class) : new Config();
        if (c.environments == null) c.environments = new LinkedHashMap<>();
        if (c.projects == null) c.projects = new LinkedHashMap<>();
        if (c.denyRoots == null) c.denyRoots = List.of();
        c.home = home;
        return c;
    }

    public void save() throws IOException {
        Files.writeString(home.resolve("config.json"), GSON.toJson(this));
    }

    /** Resolves the environment for a working directory: linked project, else the default env. */
    public String envFor(Path cwd) {
        if (cwd != null) {
            String abs = norm(cwd.toAbsolutePath().toString());
            String best = null;
            int bestLen = -1;
            for (var e : projects.entrySet()) {
                String p = norm(e.getKey());
                if ((abs.equals(p) || abs.startsWith(p + "\\")) && p.length() > bestLen) {
                    best = e.getValue();
                    bestLen = p.length();
                }
            }
            if (best != null) return best;
        }
        if (defaultEnv != null) return defaultEnv;
        return environments.size() == 1 ? environments.keySet().iterator().next() : null;
    }

    public boolean isDenied(Path p) {
        String abs = norm(p.toAbsolutePath().toString());
        for (String d : denyRoots) {
            String n = norm(d);
            if (abs.equals(n) || abs.startsWith(n + "\\")) return true;
        }
        return false;
    }

    private static String norm(String s) {
        String n = s.replace('/', '\\').toLowerCase(Locale.ROOT);
        return n.endsWith("\\") ? n.substring(0, n.length() - 1) : n;
    }
}
