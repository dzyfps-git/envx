package dev.sevli.env;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** A server or instance folder: local, a mapped drive, or a UNC share such as {@code \\host\share}. */
final class FolderSource implements EnvironmentSource {
    private final Path root;

    FolderSource(Path root) {
        this.root = root;
    }

    @Override
    public String describe() {
        return root.toString();
    }

    @Override
    public void probe() throws IOException {
        if (!Files.isDirectory(root.resolve("mods"))) throw new IOException("not reachable or no mods folder: " + root);
    }

    @Override
    public List<Entry> listMods() throws IOException {
        Path mods = root.resolve("mods");
        if (!Files.isDirectory(mods)) throw new IOException("No mods folder at " + mods);
        List<Entry> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(mods)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (!p.getFileName().toString().endsWith(".jar") || !Files.isRegularFile(p)) continue;
                BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
                out.add(new Entry("mods/" + p.getFileName(), a.size(), a.lastModifiedTime().toMillis(), null));
            }
        }
        return out;
    }

    @Override
    public List<Entry> listTree(String dir, long maxFileBytes) throws IOException {
        Path base = root.resolve(dir);
        List<Entry> out = new ArrayList<>();
        if (!Files.isDirectory(base)) return out;
        try (Stream<Path> s = Files.walk(base)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (!Files.isRegularFile(p)) continue;
                BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
                if (a.size() > maxFileBytes) continue;
                out.add(new Entry(root.relativize(p).toString().replace('\\', '/'), a.size(), a.lastModifiedTime().toMillis(), null));
            }
        }
        return out;
    }

    @Override
    public byte[] read(String relPath) throws IOException {
        return Files.readAllBytes(resolve(relPath));
    }

    @Override
    public byte[] readIfExists(String relPath) throws IOException {
        Path p = resolve(relPath);
        return Files.isRegularFile(p) ? Files.readAllBytes(p) : null;
    }

    private Path resolve(String relPath) throws IOException {
        Path p = root.resolve(relPath).normalize();
        if (!p.startsWith(root.normalize())) throw new IOException("Path escapes source root: " + relPath);
        return p;
    }
}
