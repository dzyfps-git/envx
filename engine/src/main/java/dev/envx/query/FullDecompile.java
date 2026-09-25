package dev.envx.query;

import dev.envx.store.Packs;
import dev.envx.Config;
import dev.envx.env.AutoSync;
import dev.envx.fabric.FabricBase;
import dev.envx.store.Db;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Decompiles every loaded jar of an environment into the source cache, in the background after a sync
 * (study 2026-09: searching all decompiled code found more than the on-demand cache in 18 of 24 real searches).
 *
 * <p>It runs as its own process at idle priority, with {@link Config#decompileThreads} threads and a
 * {@link Config#decompileMemoryMb} heap, one jar at a time, so a server on the same PC keeps its CPU. Jars are
 * cached by content hash, so each is decompiled once; a pack update only adds its new jars. The cache is the one
 * {@link SourceService} fills on demand ({@code decomp/}), and like it this never writes the index.
 * {@code envx decompile --stop} ends a run; the next sync continues where it stopped.
 */
public final class FullDecompile {
    private FullDecompile() {}

    /** One jar to decompile. */
    record Job(long artifactId, String label, String kind, String sha, int classes) {}

    static Path lockFile(Path home) {
        return home.resolve("decomp").resolve(".full.lock");
    }

    /** The pid of a running decompile, if any (a lock whose process is gone is ignored). */
    static Optional<ProcessHandle> running(Path home) {
        try {
            Path lock = lockFile(home);
            if (!Files.exists(lock)) return Optional.empty();
            long pid = Long.parseLong(Files.readString(lock).trim().split(" ")[0]);
            return ProcessHandle.of(pid).filter(ProcessHandle::isAlive);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Loaded jars with classes whose cache folder is not complete and packed yet: Minecraft first, then small jars first. */
    static List<Job> pending(QueryService q, Scope s, FabricBase base) throws SQLException {
        List<Job> out = new ArrayList<>();
        for (Job j : q.db().query("""
                SELECT a.id, a.kind, a.sha256, (SELECT count(*) FROM class c WHERE c.artifact_id=a.id) n FROM artifact a
                WHERE a.id IN (""" + s.artifactSet() + ") AND n > 0 ORDER BY a.kind<>'minecraft', n, a.id",
                rs -> new Job(rs.getLong(1), null, rs.getString(2), rs.getString(3), rs.getInt(4)))) {
            Path dir = SourceService.cacheDir(q.config().home(), base, j.kind(), j.sha());
            if (Files.exists(dir.resolve(SourceService.COMPLETE)) && Files.exists(Packs.of(dir))) continue;
            out.add(new Job(j.artifactId(), q.label(j.artifactId()), j.kind(), j.sha(), j.classes()));
        }
        return out;
    }

    /**
     * Extracted-resource folders ({@code resources/<jar sha>}, any version) without a {@link Packs pack}: those indexed
     * before 1.4.1 (a sync packs new ones as it extracts them). Past versions' folders are packed too (grep's
     * past-version hint reads them).
     */
    static List<Path> unpackedResources(Path home) {
        Path root = home.resolve("resources");
        if (!Files.isDirectory(root)) return List.of();
        try (var dirs = Files.list(root)) {
            return dirs.filter(Files::isDirectory).filter(d -> !Files.exists(Packs.of(d))).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * Starts a background decompile of {@code env} when something is left to do. Returns a line for the sync's
     * output, or null when nothing was started. Never throws: the decompile is a convenience.
     */
    public static String maybeStart(Config config, String env) {
        try {
            if (running(config.home()).isPresent()) return null;
            int jars = 0;
            int classes = 0;
            int resources = unpackedResources(config.home()).size();
            try (Db db = Db.open(config.home(), true)) {
                QueryService q = new QueryService(config, db);
                Scope s = Scope.resolve(config, db, env, null);
                FabricBase base = FabricBase.forEnv(config.home(), s.def().minecraft, s.def().mappings);
                if (config.decompileAll && base.isProvisioned()) {
                    List<Job> jobs = pending(q, s, base);
                    jars = jobs.size();
                    classes = jobs.stream().mapToInt(Job::classes).sum();
                }
            }
            if (jars == 0 && resources == 0) return null;
            List<String> cmd = new ArrayList<>(AutoSync.command(config, "decompile", env));
            // JVM options: a heap cap, and a CPU count the JVM sizes its own threads by
            cmd.addAll(1, List.of("-Xmx" + config.decompileMemoryMb + "m", "-XX:ActiveProcessorCount=" + Math.max(1, config.decompileThreads)));
            Path log = config.home().resolve("logs").resolve("decompile.log");
            Files.createDirectories(log.getParent());
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
            p.getOutputStream().close();
            String work = (jars > 0 ? "decompiling " + jars + " jar(s) (" + classes + " classes)" : "")
                    + (jars > 0 && resources > 0 ? " and " : "") + (resources > 0 ? "packing " + resources + " resource folder(s)" : "");
            return work + " in the background at idle priority, " + config.decompileThreads + " threads; progress in " + log
                    + "; stop with: envx stop";
        } catch (Exception e) {
            return null;
        }
    }

    /** {@code envx decompile <env>}: lowers this process's priority, then decompiles what is pending. */
    public static int run(Config config, String env) throws Exception {
        System.out.println(Instant.now() + " decompile " + env + ": " + lowerPriority());
        return decompilePending(config, env, System.out);
    }

    /** Decompiles what is pending, one jar at a time, logging each jar; returns 0 (a failed jar is retried next time). */
    public static int decompilePending(Config config, String env, java.io.PrintStream log) throws Exception {
        Path home = config.home();
        Files.createDirectories(lockFile(home).getParent());
        if (running(home).filter(h -> h.pid() != ProcessHandle.current().pid()).isPresent()) {
            log.println(Instant.now() + " a decompile is already running; not starting another");
            return 0;
        }
        Files.writeString(lockFile(home), ProcessHandle.current().pid() + " " + Instant.now());
        try {
            List<Job> jobs;
            FabricBase base;
            try (Db db = Db.open(home, true)) {
                QueryService q = new QueryService(config, db);
                Scope s = Scope.resolve(config, db, env, null);
                base = FabricBase.forEnv(home, s.def().minecraft, s.def().mappings);
                jobs = pending(q, s, base);
            }
            if (!config.decompileAll) jobs = List.of();
            List<Path> resources = unpackedResources(home);
            log.println(Instant.now() + " " + jobs.size() + " jar(s) to decompile, " + resources.size() + " resource folder(s) to pack");
            int done = 0;
            long t0 = System.nanoTime();
            for (Job j : jobs) {
                long t = System.nanoTime();
                try {
                    Path dir = SourceService.cacheDir(home, base, j.kind(), j.sha());
                    if (Files.exists(dir.resolve(SourceService.COMPLETE))) { // decompiled by 1.3.0/1.3.1, before packs
                        SourceService.packJava(dir);
                        done++;
                        continue;
                    }
                    int n = SourceService.decompileAll(SourceService.decompileInput(home, base, j.kind(), j.sha()),
                            SourceService.libraries(base, j.kind()), dir, Math.max(1, config.decompileThreads));
                    done++;
                    log.printf(Locale.ROOT, "%s %d/%d %s: %d classes in %.1f s%n", Instant.now(), done, jobs.size(), j.label(), n, (System.nanoTime() - t) / 1e9);
                } catch (Exception | OutOfMemoryError e) { // one bad jar must not stop the rest; it is retried next time
                    log.println(Instant.now() + " " + j.label() + " failed: " + e);
                }
            }
            log.printf(Locale.ROOT, "%s done: %d of %d jar(s) in %.0f s%n", Instant.now(), done, jobs.size(), (System.nanoTime() - t0) / 1e9);
            long t1 = System.nanoTime();
            int packed = 0;
            for (Path d : resources) {
                try {
                    if (Packs.write(d, f -> true)) packed++;
                } catch (IOException | RuntimeException e) {
                    log.println(Instant.now() + " packing " + d.getFileName() + " failed: " + e);
                }
            }
            if (!resources.isEmpty()) {
                log.printf(Locale.ROOT, "%s packed %d of %d resource folder(s) in %.0f s%n", Instant.now(), packed, resources.size(), (System.nanoTime() - t1) / 1e9);
            }
            return 0;
        } finally {
            Files.deleteIfExists(lockFile(home));
        }
    }

    /** {@code envx decompile --stop}: ends a running background decompile. */
    public static String stop(Config config) {
        Optional<ProcessHandle> p = running(config.home());
        if (p.isEmpty()) return "no decompile is running";
        p.get().destroy();
        try {
            p.get().onExit().get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            p.get().destroyForcibly();
        }
        try {
            Files.deleteIfExists(lockFile(config.home()));
        } catch (IOException ignored) {
            // a lock whose process is gone is ignored anyway
        }
        return "stopped decompile (pid " + p.get().pid() + "); finished jars are kept, the next sync continues";
    }

    /** {@code envx decompile --status}: how much of an environment's code is decompiled. */
    public static String status(Config config, String env) throws SQLException {
        try (Db db = Db.open(config.home(), true)) {
            QueryService q = new QueryService(config, db);
            Scope s = Scope.resolve(config, db, env, null);
            Coverage c = coverage(q, s);
            return env + ": " + c.complete() + " of " + c.total() + " jars fully decompiled"
                    + (running(config.home()).isPresent() ? "; a decompile is running (envx decompile --stop ends it)" : "")
                    + (config.decompileAll ? "" : "; decompileAll is off in config.json");
        }
    }

    record Coverage(int complete, int total) {
        /** Empty when everything is decompiled; else a short note for grep answers. */
        String note() {
            return complete >= total ? "" : "; source: " + complete + " of " + total + " jars fully decompiled, the rest only where read before";
        }
    }

    /** How many of the scope's jars with code (after {@code mod:}) have a complete cache folder. */
    static Coverage coverage(QueryService q, Scope s) throws SQLException {
        FabricBase base = FabricBase.forEnv(q.config().home(), s.def().minecraft, s.def().mappings);
        int complete = 0;
        int total = 0;
        for (Object[] a : q.db().query("SELECT a.id, a.kind, a.sha256 FROM artifact a WHERE a.id IN (" + s.artifactSet()
                        + ") AND EXISTS (SELECT 1 FROM class c WHERE c.artifact_id=a.id)",
                rs -> new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3)})) {
            if (!s.includes((long) a[0])) continue;
            total++;
            if (Files.exists(SourceService.cacheDir(q.config().home(), base, (String) a[1], (String) a[2]).resolve(SourceService.COMPLETE))) complete++;
        }
        return new Coverage(complete, total);
    }

    /** Idle priority for this process (Windows), or the lowest nice level elsewhere. Returns what was done. */
    static String lowerPriority() {
        long pid = ProcessHandle.current().pid();
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        List<String> cmd = windows
                ? List.of("powershell", "-NoProfile", "-NonInteractive", "-Command", "(Get-Process -Id " + pid + ").PriorityClass='Idle'")
                : List.of("renice", "-n", "19", "-p", String.valueOf(pid));
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (p.waitFor(Duration.ofSeconds(30).toSeconds(), TimeUnit.SECONDS) && p.exitValue() == 0) return windows ? "idle priority" : "nice 19";
            p.destroyForcibly();
            return "priority unchanged (" + cmd.getFirst() + " failed)";
        } catch (IOException | InterruptedException e) {
            return "priority unchanged (" + e.getMessage() + ")";
        }
    }
}
