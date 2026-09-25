package dev.envx.fabric;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.tinyremapper.NonClassCopyMode;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.TinyUtils;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Builds the base layer without a Loom cache: Minecraft's client and server jars from Mojang (checked against the
 * SHA-1s in Mojang's version metadata), Yarn from the Fabric maven (checked against the maven's published
 * checksums), then merged and remapped locally the way Loom does it: one jar with client and server classes, in
 * intermediary names (indexed) and in Yarn names (decompiled on demand). Needs network once per base.
 */
final class BaseDownload {
    private static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String FABRIC_MAVEN = "https://maven.fabricmc.net/";

    private final FabricBase base;
    private final Consumer<String> log;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20)).build();

    BaseDownload(FabricBase base, Consumer<String> log) {
        this.base = base;
        this.log = log;
    }

    void run() throws IOException {
        Path work = base.dir.resolve("download");
        Files.createDirectories(work);

        // Mojang: version metadata, then the two jars it lists with their SHA-1s.
        JsonObject manifest = JsonParser.parseString(new String(get(MANIFEST), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject entry = null;
        for (var v : manifest.getAsJsonArray("versions")) {
            if (v.getAsJsonObject().get("id").getAsString().equals(base.minecraft)) entry = v.getAsJsonObject();
        }
        if (entry == null) throw new IOException("Mojang's version manifest has no Minecraft " + base.minecraft);
        byte[] versionJson = get(entry.get("url").getAsString());
        check(versionJson, "SHA-1", entry.get("sha1").getAsString(), "version " + base.minecraft + " metadata");
        JsonObject downloads = JsonParser.parseString(new String(versionJson, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("downloads");
        Path client = fetch(downloads.getAsJsonObject("client"), work.resolve("client.jar"), "client jar");
        Path bundler = fetch(downloads.getAsJsonObject("server"), work.resolve("server-bundler.jar"), "server jar");
        List<Path> libraries = new ArrayList<>();
        Path server = unbundle(bundler, work, libraries);

        // Fabric maven: Yarn merged with intermediary (official/intermediary/named) and plain v2 (javadoc, unpick).
        String yarnBase = FABRIC_MAVEN + "net/fabricmc/yarn/" + base.yarn + "/yarn-" + base.yarn;
        Path merged = mavenFile(yarnBase + "-mergedv2.jar", work.resolve("yarn-mergedv2.jar"));
        Path v2 = mavenFile(yarnBase + "-v2.jar", work.resolve("yarn-v2.jar"));
        extract(merged, "mappings/mappings.tiny", base.mappingsFile());
        extract(v2, "mappings/mappings.tiny", base.mappingsWithDocs());
        try {
            extract(v2, "extras/definitions.unpick", base.unpickFile());
        } catch (IOException e) {
            log.accept("no unpick definitions in this Yarn build (constants stay numeric in sources)");
        }

        log.accept("merging client and server jars");
        Path official = work.resolve("merged-official.jar");
        merge(client, server, official);
        log.accept("remapping to intermediary and Yarn names");
        remap(official, "intermediary", libraries, base.intermediaryJar());
        remap(official, "named", libraries, base.namedJar());
        deleteTree(work);
        log.accept("base " + base.id() + " ready in " + base.dir);
    }

    // ---------------------------------------------------------------- downloads

    private byte[] get(String url) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5))
                .header("User-Agent", "envx (Minecraft environment index)").GET().build();
        try {
            HttpResponse<byte[]> r = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " for " + url);
            return r.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted downloading " + url, e);
        }
    }

    private Path fetch(JsonObject d, Path target, String what) throws IOException {
        log.accept("downloading " + what + " (" + d.get("size").getAsLong() / 1_000_000 + " MB)");
        byte[] bytes = get(d.get("url").getAsString());
        check(bytes, "SHA-1", d.get("sha1").getAsString(), what);
        Files.write(target, bytes);
        return target;
    }

    /** A maven artifact checked against the maven's .sha256 (else .sha1) file. */
    private Path mavenFile(String url, Path target) throws IOException {
        log.accept("downloading " + url.substring(url.lastIndexOf('/') + 1));
        byte[] bytes = get(url);
        String expected;
        String algorithm;
        try {
            expected = new String(get(url + ".sha256"), StandardCharsets.US_ASCII).trim().split("\\s+")[0];
            algorithm = "SHA-256";
        } catch (IOException e) {
            expected = new String(get(url + ".sha1"), StandardCharsets.US_ASCII).trim().split("\\s+")[0];
            algorithm = "SHA-1";
        }
        check(bytes, algorithm, expected, target.getFileName().toString());
        Files.write(target, bytes);
        return target;
    }

    static void check(byte[] bytes, String algorithm, String expected, String what) throws IOException {
        try {
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
            if (!actual.equalsIgnoreCase(expected)) {
                throw new IOException(what + ": " + algorithm + " mismatch (expected " + expected + ", got " + actual + "); download refused");
            }
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Since 1.18 the server download is a bundler: the server jar and its libraries are inside it, listed with their
     * SHA-256 in {@code META-INF/versions.list} and {@code libraries.list}. Older versions are the server jar itself.
     */
    private static Path unbundle(Path bundler, Path work, List<Path> libraries) throws IOException {
        try (ZipFile zip = new ZipFile(bundler.toFile())) {
            ZipEntry versions = zip.getEntry("META-INF/versions.list");
            if (versions == null) return bundler;
            Path server = null;
            for (String list : List.of("META-INF/versions.list", "META-INF/libraries.list")) {
                ZipEntry le = zip.getEntry(list);
                if (le == null) continue;
                String dir = list.equals("META-INF/versions.list") ? "META-INF/versions/" : "META-INF/libraries/";
                for (String line : new String(zip.getInputStream(le).readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                    String[] p = line.trim().split("\t");
                    if (p.length < 3) continue;
                    byte[] bytes = zip.getInputStream(zip.getEntry(dir + p[2])).readAllBytes();
                    check(bytes, "SHA-256", p[0], p[2]);
                    Path out = work.resolve(list.contains("versions") ? "server.jar" : "lib-" + p[2].replace('/', '_'));
                    Files.write(out, bytes);
                    if (list.contains("versions")) server = out;
                    else libraries.add(out);
                }
            }
            if (server == null) throw new IOException("server bundler lists no server jar");
            return server;
        }
    }

    private static void extract(Path jar, String entry, Path target) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry e = zip.getEntry(entry);
            if (e == null) throw new IOException(entry + " not in " + jar.getFileName());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            try (InputStream in = zip.getInputStream(e)) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
    }

    // ---------------------------------------------------------------- merge and remap

    /**
     * One jar with every class of both sides. A class on both sides keeps the client version plus any fields and
     * methods only the server has. (Loom additionally marks one-sided members with {@code @Environment}; envx
     * decides client-only code by package, so the marks are not needed.) Resources: the first copy wins.
     */
    static void merge(Path client, Path server, Path out) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        Map<String, byte[]> serverClasses = new LinkedHashMap<>();
        readJar(client, entries);
        readJar(server, serverClasses);
        for (var e : serverClasses.entrySet()) {
            byte[] c = entries.get(e.getKey());
            if (c == null) entries.put(e.getKey(), e.getValue());
            else if (e.getKey().endsWith(".class")) entries.put(e.getKey(), mergeClass(c, e.getValue()));
        }
        Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(tmp))) {
            for (var e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void readJar(Path jar, Map<String, byte[]> into) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (var it = zip.entries(); it.hasMoreElements(); ) {
                ZipEntry e = it.nextElement();
                String n = e.getName();
                if (e.isDirectory() || n.startsWith("META-INF/") && !n.equals("META-INF/MANIFEST.MF") && !n.startsWith("META-INF/services/")) continue;
                into.putIfAbsent(n, zip.getInputStream(e).readAllBytes());
            }
        }
    }

    static byte[] mergeClass(byte[] clientBytes, byte[] serverBytes) {
        ClassNode c = new ClassNode();
        new ClassReader(clientBytes).accept(c, 0);
        ClassNode s = new ClassNode();
        new ClassReader(serverBytes).accept(s, 0);
        Set<String> fields = new HashSet<>(), methods = new HashSet<>();
        for (FieldNode f : c.fields) fields.add(f.name + f.desc);
        for (MethodNode m : c.methods) methods.add(m.name + m.desc);
        boolean changed = false;
        for (FieldNode f : s.fields) if (fields.add(f.name + f.desc)) { c.fields.add(f); changed = true; }
        for (MethodNode m : s.methods) if (methods.add(m.name + m.desc)) { c.methods.add(m); changed = true; }
        if (s.interfaces != null) for (String i : s.interfaces) if (!c.interfaces.contains(i)) { c.interfaces.add(i); changed = true; }
        if (!changed) return clientBytes;
        ClassWriter w = new ClassWriter(0); // members are copied whole, frames and maxs stay valid
        c.accept(w);
        return w.toByteArray();
    }

    private static void remap(Path official, String to, List<Path> libraries, Path target) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        TinyRemapper remapper = TinyRemapper.newRemapper()
                .withMappings(TinyUtils.createTinyMappingProvider(targetMappings(target), "official", to))
                .ignoreConflicts(true)
                .renameInvalidLocals(true)
                .rebuildSourceFilenames(true)
                .build();
        PrintStreamGuard guard = new PrintStreamGuard();
        try (OutputConsumerPath out = new OutputConsumerPath.Builder(tmp).assumeArchive(true).build()) {
            out.addNonClassFiles(official, NonClassCopyMode.FIX_META_INF, remapper);
            remapper.readClassPath(libraries.toArray(Path[]::new));
            remapper.readInputs(official);
            remapper.apply(out);
        } finally {
            remapper.finish();
            guard.close();
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Both targets use the official/intermediary/named file next to them. */
    private static Path targetMappings(Path target) {
        return target.resolveSibling("mappings.tiny");
    }

    /** tiny-remapper prints warnings to stdout; they would corrupt CLI output. */
    private static final class PrintStreamGuard implements AutoCloseable {
        private final java.io.PrintStream real = System.out;

        PrintStreamGuard() {
            System.setOut(new java.io.PrintStream(OutputStream.nullOutputStream()));
        }

        @Override
        public void close() {
            System.setOut(real);
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var s = Files.walk(dir)) {
            for (Path p : s.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
