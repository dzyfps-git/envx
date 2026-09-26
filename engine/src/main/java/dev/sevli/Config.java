package dev.sevli;

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
     * Use-time sync: when sevli is used and an environment was last checked longer ago than this, a sync runs
     * in the background. 0 turns it off (then only `sevli sync` updates the index).
     */
    public double autoSyncHours = 6;
    /** Days of server logs and crash reports kept in the local mirror (env filter=errors, grep scope=logs). */
    public int logDays = 14;
    /**
     * After a sync, decompile every loaded jar not decompiled yet in a background process (idle priority), so
     * {@code grep scope=source} covers all code and {@code source} reads from the cache. Each jar is done once.
     */
    public boolean decompileAll = true;
    /** Threads (and CPUs) the background decompile may use. */
    public int decompileThreads = 2;
    /** Heap limit of the background decompile, in MB (Minecraft itself needs about 3 GB). */
    public int decompileMemoryMb = 3072;
    /** The published catalog of supported baselines and modpacks (recipes only; ADR 0013). */
    public String catalogUrl = "https://raw.githubusercontent.com/dzyfps-git/sevli-catalog/main/index.json";
    public List<String> denyRoots = List.of(); // machine-specific: listed in the data home's config.json
    /**
     * Which jars of an environment are indexed (ADR 0014). Unset: only jars the published catalog supports
     * ({@code catalog/Supported}); the rest are listed as not indexed. {@code "all"}: every jar, for the
     * maintainer's own install, where new jars are tested before they are published (not offered in the app).
     */
    public String supportPolicy;

    public boolean indexesEverything() {
        return "all".equals(supportPolicy);
    }

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
        /**
         * The supported-list version the user accepted for this environment (set when it is first synced, raised by
         * {@code sevli accept}). Jars that only a newer list supports wait for that click; nothing is indexed unasked.
         */
        public Integer supportAccepted;
        /** Copy the server's recent logs and crash reports (ADR 0008); `sevli logs <name> off` stops it. */
        public boolean logs = true;

        public boolean hides(String mixinSide) {
            return mixinSide != null && !mixinSide.equals("common") && side != null && !mixinSide.equals(side);
        }
    }

    private transient Path home;

    public Path home() {
        return home;
    }

    /** The data home fixed for this run by a property or variable (Sevli's, or envx's from before the rename), else null. */
    public static String fixedHome() {
        for (String v : new String[]{System.getProperty("sevli.home"), System.getenv("SEVLI_HOME"),
                System.getProperty("envx.home"), System.getenv("ENVX_HOME")}) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    public static Path resolveHome() {
        String fixed = fixedHome();
        if (fixed != null) return Path.of(fixed);
        Path userHome = Path.of(System.getProperty("user.home"));
        Path dflt = userHome.resolve(".sevli");
        // ~/.sevli/location holds one line, the data home, for a home kept elsewhere (another drive).
        Path where = pointer(dflt.resolve("location"));
        if (where != null) return where;
        // Before the rename (2.0) the data home was found through ~/.envx. Adopt it only when it really is ours: another
        // tool also uses a ~/.envx folder. Nothing is moved; `sevli setup` writes the new pointer.
        Path legacy = userHome.resolve(".envx");
        Path old = pointer(legacy.resolve("location"));
        if (old != null && isDataHome(old)) return old;
        if (isDataHome(legacy)) return legacy;
        return dflt;
    }

    /** A data home this program wrote: its config and index are there. */
    public static boolean isDataHome(Path dir) {
        return Files.isRegularFile(dir.resolve("config.json")) && Files.isRegularFile(dir.resolve("index.sqlite"));
    }

    private static Path pointer(Path location) {
        if (!Files.isRegularFile(location)) return null;
        try {
            String where = Files.readString(location).trim();
            return where.isEmpty() ? null : Path.of(where);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Records the data home in ~/.sevli/location (used after adopting a pre-2.0 home, and when choosing one). */
    public static void writePointer(Path home) throws IOException {
        Path location = Path.of(System.getProperty("user.home"), ".sevli", "location");
        Files.createDirectories(location.getParent());
        Files.writeString(location, home.toAbsolutePath().normalize() + System.lineSeparator());
    }

    public static Config load() throws IOException {
        Path home = resolveHome(); // not created here: a fresh install stays empty until something is installed
        Path file = home.resolve("config.json");
        Config c = Files.exists(file) ? GSON.fromJson(Files.readString(file), Config.class) : new Config();
        if (c.environments == null) c.environments = new LinkedHashMap<>();
        if (c.projects == null) c.projects = new LinkedHashMap<>();
        if (c.denyRoots == null) c.denyRoots = List.of();
        c.home = home;
        return c;
    }

    public void save() throws IOException {
        Files.createDirectories(home);
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
