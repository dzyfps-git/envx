package dev.sevli.env;

import dev.sevli.Config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * A local copy of the live server's recent logs ({@code logs/latest.log}, {@code logs/*.log.gz}) and crash reports,
 * read-only from the environment's source, so log questions never scan megabytes on the server share per call.
 * Rotated logs and crash reports never change and are copied once; {@code latest.log} is copied when its size or
 * time changed. Only the last {@link Config#logDays} days are kept.
 *
 * <p>Logs are runtime evidence, not pack content: they are not part of snapshots or their fingerprints, and the
 * mirror is a file cache like decompiled sources (no index writes), so query processes may refresh it.
 */
public final class LogMirror {
    /** How old the mirror may be before a query refreshes it. */
    public static final Duration QUERY_MAX_AGE = Duration.ofMinutes(2);
    static final int CRASH_DAYS = 60;

    private LogMirror() {}

    public static Path dir(Path home, String env) {
        return home.resolve("envs").resolve(env).resolve("runtime");
    }

    /** When the mirror was last brought up to date, or null. */
    public static Instant checkedAt(Path home, String env) {
        try {
            return Files.getLastModifiedTime(dir(home, env).resolve(".checked")).toInstant();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Brings the mirror up to date unless it was checked within {@code maxAge}. Returns null on success, else why
     * it could not (the source is unreachable); the existing copy stays usable either way.
     */
    public static String refresh(Config config, String env, Duration maxAge) {
        Path root = dir(config.home(), env);
        Instant checked = checkedAt(config.home(), env);
        if (checked != null && checked.plus(maxAge).isAfter(Instant.now())) return null;
        Config.EnvDef def = config.environments.get(env);
        if (def == null || def.sources.isEmpty()) return "no source";
        if (!def.logs) return "copying server logs is switched off for " + env + " (sevli logs " + env + " on)";
        try {
            EnvironmentSource source = EnvironmentSource.firstReachable(def.sources, m -> {});
            long since = Instant.now().minus(Duration.ofDays(config.logDays)).toEpochMilli();
            // Crash reports are rare, small and the best evidence there is: kept longer than logs.
            long crashSince = Instant.now().minus(Duration.ofDays(Math.max(config.logDays, CRASH_DAYS))).toEpochMilli();
            List<EnvironmentSource.Entry> wanted = new ArrayList<>();
            for (var e : source.listTree("logs", 512L << 20)) {
                String name = e.relPath().substring(e.relPath().lastIndexOf('/') + 1);
                if (e.relPath().chars().filter(c -> c == '/').count() != 1) continue; // logs/<file> only
                if ((name.equals("latest.log") || name.endsWith(".log.gz")) && e.mtime() >= since) wanted.add(e);
            }
            for (var e : source.listTree("crash-reports", 64L << 20)) {
                if (e.relPath().endsWith(".txt") && e.mtime() >= crashSince) wanted.add(e);
            }
            for (var e : wanted) {
                Path local = root.resolve(e.relPath()).normalize();
                if (!local.startsWith(root)) continue;
                if (Files.exists(local) && Files.size(local) == e.size()
                        && Files.getLastModifiedTime(local).toMillis() / 1000 == e.mtime() / 1000) continue;
                byte[] data = source.read(e.relPath());
                Files.createDirectories(local.getParent());
                Path tmp = local.resolveSibling(local.getFileName() + "." + ProcessHandle.current().pid() + ".tmp");
                Files.write(tmp, data);
                Files.setLastModifiedTime(tmp, FileTime.fromMillis(e.mtime()));
                Files.move(tmp, local, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            prune(root.resolve("logs"), since);
            prune(root.resolve("crash-reports"), crashSince);
            Files.createDirectories(root);
            Path marker = root.resolve(".checked");
            if (!Files.exists(marker)) Files.writeString(marker, "");
            Files.setLastModifiedTime(marker, FileTime.from(Instant.now()));
            return null;
        } catch (IOException | RuntimeException e) {
            return e.getMessage() == null ? e.toString() : e.getMessage().lines().findFirst().orElse(e.toString());
        }
    }

    private static void prune(Path d, long since) throws IOException {
        if (!Files.isDirectory(d)) return;
        try (Stream<Path> files = Files.list(d)) {
            for (Path f : files.toList()) {
                if (Files.getLastModifiedTime(f).toMillis() < since) Files.deleteIfExists(f);
            }
        }
    }
}
