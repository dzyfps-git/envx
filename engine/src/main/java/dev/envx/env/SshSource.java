package dev.envx.env;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Read-only access over Windows OpenSSH ({@code ssh.exe}) using the user's existing key/agent.
 * {@code BatchMode=yes} means it never prompts and never handles a password. Only read commands
 * ({@code find}, {@code sha256sum}, {@code cat}, {@code tar -c}) are ever sent.
 *
 * <p>Spec: {@code ssh://user@host[:port]/absolute/server/path}
 */
final class SshSource implements EnvironmentSource {
    private final String target;
    private final int port;
    private final String root;

    private SshSource(String target, int port, String root) {
        this.target = target;
        this.port = port;
        this.root = root;
    }

    static SshSource parse(String spec) {
        URI u = URI.create(spec);
        String user = u.getUserInfo();
        String target = (user == null ? "" : user + "@") + u.getHost();
        String path = u.getPath();
        if (path == null || path.isEmpty()) throw new IllegalArgumentException("ssh spec needs a path: " + spec);
        return new SshSource(target, u.getPort(), path);
    }

    @Override
    public String describe() {
        return "ssh://" + target + (port > 0 ? ":" + port : "") + root;
    }

    @Override
    public void probe() throws IOException {
        run("test -d " + q(root + "/mods"));
    }

    @Override
    public List<Entry> listMods() throws IOException {
        // size, mtime and sha256 in one round trip; hashing ~1 GB server-side is far cheaper than downloading it
        String out = text(run("cd " + q(root) + " && for f in mods/*.jar; do [ -f \"$f\" ] && "
                + "printf '%s %s %s\\n' \"$(stat -c '%s %Y' \"$f\")\" \"$(sha256sum \"$f\" | cut -d' ' -f1)\" \"$f\"; done"));
        List<Entry> entries = new ArrayList<>();
        for (String line : out.split("\n")) {
            String[] p = line.trim().split(" ", 4);
            if (p.length < 4) continue;
            entries.add(new Entry(p[3], Long.parseLong(p[0]), Long.parseLong(p[1]) * 1000, p[2]));
        }
        if (entries.isEmpty()) throw new IOException("No mods/*.jar found at " + describe());
        return entries;
    }

    @Override
    public List<Entry> listTree(String dir, long maxFileBytes) throws IOException {
        String out = text(run("cd " + q(root) + " && [ -d " + q(dir) + " ] && find " + q(dir) + " -type f -size -"
                + (maxFileBytes + 1) + "c -printf '%s %T@ %p\\n' || true"));
        List<Entry> entries = new ArrayList<>();
        for (String line : out.split("\n")) {
            String[] p = line.trim().split(" ", 3);
            if (p.length < 3) continue;
            entries.add(new Entry(p[2], Long.parseLong(p[0]), (long) (Double.parseDouble(p[1]) * 1000), null));
        }
        return entries;
    }

    @Override
    public byte[] read(String relPath) throws IOException {
        return run("cat " + q(root + "/" + relPath));
    }

    @Override
    public byte[] readIfExists(String relPath) throws IOException {
        String path = q(root + "/" + relPath);
        byte[] data = run("[ -f " + path + " ] && cat " + path + " || true");
        return data.length == 0 ? null : data;
    }

    @Override
    public Map<String, byte[]> readMany(List<String> relPaths) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        // Batches keep the remote command line well under ARG_MAX.
        for (int i = 0; i < relPaths.size(); i += 400) {
            List<String> batch = relPaths.subList(i, Math.min(relPaths.size(), i + 400));
            StringBuilder cmd = new StringBuilder("cd " + q(root) + " && tar -cf -");
            for (String p : batch) cmd.append(' ').append(q(p));
            out.putAll(Tar.read(run(cmd.toString())));
        }
        return out;
    }

    private byte[] run(String remoteCommand) throws IOException {
        List<String> cmd = new ArrayList<>(List.of("ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10"));
        if (port > 0) cmd.addAll(List.of("-p", String.valueOf(port))); // otherwise ~/.ssh/config decides
        cmd.add(target);
        cmd.add(remoteCommand);
        Process p = new ProcessBuilder(cmd).start();
        p.getOutputStream().close();
        CompletableFuture<byte[]> err = CompletableFuture.supplyAsync(() -> readQuietly(p.getErrorStream()));
        byte[] out = p.getInputStream().readAllBytes();
        try {
            if (!p.waitFor(10, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                throw new IOException("ssh timed out: " + describe());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
        if (p.exitValue() != 0) {
            throw new IOException("ssh failed (exit " + p.exitValue() + "): " + text(err.join()).trim());
        }
        return out;
    }

    private static byte[] readQuietly(InputStream in) {
        try {
            return in.readAllBytes();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private static String text(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    /** POSIX single-quote escaping. */
    static String q(String s) {
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }

    /** Minimal reader for the ustar output of {@code tar -cf -} (regular files only). */
    static final class Tar {
        static Map<String, byte[]> read(byte[] tar) {
            Map<String, byte[]> files = new LinkedHashMap<>();
            int pos = 0;
            String longName = null;
            while (pos + 512 <= tar.length) {
                if (tar[pos] == 0) break; // end-of-archive blocks
                String name = field(tar, pos, 100);
                String prefix = field(tar, pos + 345, 155);
                long size = Long.parseLong(field(tar, pos + 124, 12).trim().isEmpty() ? "0" : field(tar, pos + 124, 12).trim(), 8);
                char type = (char) tar[pos + 156];
                int dataStart = pos + 512;
                if (type == 'L') { // GNU long name
                    longName = new String(tar, dataStart, (int) size, StandardCharsets.UTF_8).replace("\0", "");
                } else if (type == '0' || type == '\0') {
                    String full = longName != null ? longName : (prefix.isEmpty() ? name : prefix + "/" + name);
                    ByteArrayOutputStream bos = new ByteArrayOutputStream((int) size);
                    bos.write(tar, dataStart, (int) size);
                    files.put(full, bos.toByteArray());
                    longName = null;
                } else {
                    longName = null;
                }
                pos = dataStart + (int) ((size + 511) / 512 * 512);
            }
            return files;
        }

        private static String field(byte[] b, int off, int len) {
            int end = off;
            while (end < off + len && b[end] != 0) end++;
            return new String(b, off, end - off, StandardCharsets.UTF_8);
        }
    }
}
