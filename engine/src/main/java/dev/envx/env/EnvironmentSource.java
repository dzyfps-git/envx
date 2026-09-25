package dev.envx.env;

import java.io.IOException;
import java.util.List;

/**
 * Read-only access to where an environment lives (a server folder, a UNC share, an SSH host).
 * Implementations must never write to the source.
 */
public interface EnvironmentSource {
    /** A file relative to the source root. {@code sha256} is set when the source can hash remotely. */
    record Entry(String relPath, long size, long mtime, String sha256) {}

    String describe();

    /** Cheap reachability check; throws when the source is offline (e.g. a disconnected share). */
    void probe() throws IOException;

    /**
     * Returns the first source in {@code specs} that answers {@link #probe()} within the timeout.
     * A dead SMB share can block for a minute, hence the explicit timeout.
     */
    static EnvironmentSource firstReachable(List<String> specs, java.util.function.Consumer<String> log) throws IOException {
        List<String> failures = new java.util.ArrayList<>();
        for (String spec : specs) {
            EnvironmentSource s = of(spec);
            var probe = java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    s.probe();
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
            try {
                probe.get(15, java.util.concurrent.TimeUnit.SECONDS);
                return s;
            } catch (java.util.concurrent.TimeoutException e) {
                failures.add(s.describe() + ": timed out");
            } catch (java.util.concurrent.ExecutionException e) {
                failures.add(s.describe() + ": " + e.getCause().getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            log.accept("source unavailable, trying next: " + failures.getLast());
        }
        throw new IOException("No source reachable; the last snapshot stays current.\n  " + String.join("\n  ", failures));
    }

    /** Top-level mod jars ({@code mods/*.jar}), not recursive. */
    List<Entry> listMods() throws IOException;

    /** Text-ish files under {@code dir}, recursively; empty when the directory is absent. */
    List<Entry> listTree(String dir, long maxFileBytes) throws IOException;

    byte[] read(String relPath) throws IOException;

    /** Returns null when the file does not exist. */
    byte[] readIfExists(String relPath) throws IOException;

    /** Reads several files; remote sources override this to use a single round trip. */
    default java.util.Map<String, byte[]> readMany(List<String> relPaths) throws IOException {
        java.util.Map<String, byte[]> out = new java.util.LinkedHashMap<>();
        for (String p : relPaths) out.put(p, read(p));
        return out;
    }

    static EnvironmentSource of(String spec) {
        if (spec.startsWith("ssh://")) return SshSource.parse(spec);
        return new FolderSource(java.nio.file.Path.of(spec));
    }
}
