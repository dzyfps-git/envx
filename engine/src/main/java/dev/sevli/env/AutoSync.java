package dev.sevli.env;

import dev.sevli.Config;
import dev.sevli.store.Db;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps history without an OS scheduler: when sevli is used (an agent session starts it, or a CLI query
 * runs) and the environment was last checked more than {@link Config#autoSyncHours} ago, a separate
 * {@code sevli sync --auto} process is started in the background. The caller never waits and never
 * writes (MCP processes stay read-only, ADR 0002). A sync that finds nothing new only records the check,
 * so frequent use costs little. PCs that are off simply sync on next use; history is lost only if the
 * server changes twice with no sevli use in between.
 */
public final class AutoSync {
    /** A lock older than this belongs to a sync that died; it is ignored. */
    static final Duration STALE_LOCK = Duration.ofHours(2);

    private AutoSync() {}

    /** Starts a background sync of {@code env} if one is due. Never throws; any failure just means no sync now. */
    public static void maybeStart(Config config, String env) {
        try {
            if (env == null || config.autoSyncHours <= 0) return;
            Config.EnvDef def = config.environments.get(env);
            if (def == null || def.sources.isEmpty()) return;
            if (lockHeld(config.home(), env)) return;
            Duration every = Duration.ofMinutes((long) (config.autoSyncHours * 60));
            try (Db db = Db.open(config.home(), true)) {
                String last = db.queryString("SELECT max(coalesce(checked_at, taken_at)) FROM snapshot WHERE env=? AND kind='sync'", env);
                String attempted = db.meta("autosync." + env + ".attempted_at"); // also throttles retries when the source is down
                if (recent(last, every) || recent(attempted, every)) return;
            }
            List<String> cmd = command(config, "env", "sync", env, "--auto");
            Path log = config.home().resolve("logs").resolve("autosync.log");
            Files.createDirectories(log.getParent());
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
            p.getOutputStream().close(); // no input; the process outlives this one and is never waited for
        } catch (Exception ignored) {
            // auto-sync is a convenience; a query must never fail because of it
        }
    }

    /** An sevli command run by this same installation (same java, same jars, same data home) as a separate process. */
    public static List<String> command(Config config, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(ProcessHandle.current().info().command().orElse("java"));
        cmd.addAll(List.of("-Xss4m", "-XX:+UseSerialGC", "-Dsevli.home=" + config.home(),
                "-cp", System.getProperty("java.class.path"), "dev.sevli.cli.Main"));
        cmd.addAll(List.of(args));
        return cmd;
    }

    private static boolean recent(String iso, Duration every) {
        if (iso == null) return false;
        try {
            return Instant.parse(iso).plus(every).isAfter(Instant.now());
        } catch (RuntimeException e) {
            return false;
        }
    }

    static Path lockFile(Path home, String env) {
        return home.resolve("envs").resolve(env).resolve("sync.lock");
    }

    /** True while a sync of {@code env} holds its lock. */
    public static boolean running(Path home, String env) {
        try {
            return lockHeld(home, env);
        } catch (IOException e) {
            return false;
        }
    }

    static boolean lockHeld(Path home, String env) throws IOException {
        Path lock = lockFile(home, env);
        return Files.exists(lock) && Files.getLastModifiedTime(lock).toInstant().plus(STALE_LOCK).isAfter(Instant.now());
    }

    /** Takes the per-environment sync lock; returns false if another live sync holds it. */
    public static boolean acquire(Path home, String env) throws IOException {
        Path lock = lockFile(home, env);
        Files.createDirectories(lock.getParent());
        if (lockHeld(home, env)) return false;
        Files.deleteIfExists(lock); // stale
        try {
            Files.writeString(lock, ProcessHandle.current().pid() + " " + Instant.now(), java.nio.file.StandardOpenOption.CREATE_NEW);
            return true;
        } catch (java.nio.file.FileAlreadyExistsException e) {
            return false;
        }
    }

    public static void release(Path home, String env) {
        try {
            Files.deleteIfExists(lockFile(home, env));
        } catch (IOException ignored) {
            // a leftover lock goes stale on its own
        }
    }
}
