package dev.sevli.cli;

import dev.sevli.Config;
import dev.sevli.Version;
import dev.sevli.query.FullDecompile;
import dev.sevli.store.Db;

import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;

/** {@code sevli status}: everything a person checks day to day, on one screen. Reads only. */
final class Status {
    static final String EVERYDAY = """
            Everyday
              sevli               this screen
              sevli on | off      switch Sevli on or off for Codex and Claude Code (new sessions)
              sevli sync [<name>] pull the server's mods and configs now (it also runs by itself when Sevli is used)
              sevli stop          stop background work (the next sync continues it)
              sevli help          every command
            """;

    static final String GETTING_STARTED = """
            Getting started (pick what you need; nothing is downloaded until you do)
              sevli browse                   supported baselines and packs, with sizes
              sevli add <id>                 download and index one (e.g. fabric-1.20.1)
              sevli connect <name> <source>  optional: your own server on a supported baseline, read-only
                                            (a folder, \\\\host\\share, or ssh://host/path)
              sevli link <mod project>       optional: agents working in that project use this server
              sevli setup                    register with Claude Code and Codex, and put sevli on your PATH
            """;

    private Status() {}

    static int print(Config config) throws Exception {
        System.out.println("sevli " + Version.VALUE + "  (data home " + config.home() + ")");
        String agents;
        try {
            agents = AgentSetup.summary(config);
        } catch (Exception e) {
            agents = "unknown (" + e.getMessage() + ")";
        }
        System.out.println("Agents: " + agents);
        if (config.environments.isEmpty()) {
            System.out.println();
            System.out.print(GETTING_STARTED);
        } else if (Files.exists(config.home().resolve("index.sqlite"))) {
            try (Db db = Db.open(config.home(), true)) {
                for (String env : config.environments.keySet()) {
                    System.out.println();
                    System.out.println(env + (env.equals(config.defaultEnv) ? " (default)" : "") + ": " + environment(config, db, env));
                    if (dev.sevli.env.AutoSync.running(config.home(), env)) System.out.println("  a sync is running");
                    try {
                        System.out.println("  source: " + FullDecompile.status(config, env).substring(env.length() + 2));
                    } catch (Exception e) {
                        // never synced: nothing decompiled yet
                    }
                    String error = db.meta("autosync." + env + ".error");
                    if (error != null) System.out.println("  auto-sync: " + error);
                }
            }
        }
        System.out.println();
        System.out.print(EVERYDAY);
        return 0;
    }

    private static String environment(Config config, Db db, String env) throws Exception {
        var rows = db.query("""
                SELECT s.id, coalesce(s.checked_at, s.taken_at), s.label,
                       (SELECT count(*) FROM snapshot_artifact sa JOIN artifact a ON a.id=sa.artifact_id
                        WHERE sa.snapshot_id=s.id AND sa.loaded=1 AND a.kind='mod')
                FROM snapshot s WHERE s.env=? AND s.kind='sync' ORDER BY s.id DESC LIMIT 1""",
                rs -> new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4)}, env);
        if (rows.isEmpty()) return "never synced (sevli sync " + env + ")";
        Object[] r = rows.getFirst();
        String when = ago((String) r[1]);
        String auto = config.autoSyncHours > 0 ? "auto-sync when older than " + trim(config.autoSyncHours) + " h" : "auto-sync off";
        return "checked " + when + ", snapshot " + r[0] + (r[2] != null ? " (" + r[2] + ")" : "") + ", " + r[3] + " mod jars loaded; " + auto;
    }

    static String ago(String iso) {
        try {
            Duration d = Duration.between(Instant.parse(iso), Instant.now());
            if (d.toMinutes() < 1) return "just now";
            if (d.toHours() < 1) return d.toMinutes() + " min ago";
            if (d.toDays() < 2) return d.toHours() + " h ago";
            return d.toDays() + " days ago";
        } catch (RuntimeException e) {
            return iso;
        }
    }

    private static String trim(double hours) {
        return hours == Math.rint(hours) ? String.valueOf((long) hours) : String.valueOf(hours);
    }
}
