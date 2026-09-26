package dev.sevli.catalog;

import dev.sevli.Config;

import java.io.IOException;
import java.io.PrintStream;
import java.util.List;
import java.util.Locale;

/** {@code sevli browse} and {@code sevli add <id>} (before 2.0: {@code catalog [list] | install <id>}): the supported baselines and packs, and installing them. */
public final class CatalogCommand {
    private CatalogCommand() {}

    public static int run(Config config, List<String> args, PrintStream out) throws IOException {
        String sub = args.isEmpty() ? "list" : args.getFirst();
        switch (sub) {
            case "list" -> list(config, out);
            case "install" -> {
                if (args.size() < 2) throw new IllegalArgumentException("usage: sevli add <id>   (ids: sevli browse)");
                return install(config, args.get(1), out);
            }
            case "keygen" -> { // the maintainer, once: the key pair that signs the supported list
                if (args.size() < 2) throw new IllegalArgumentException("usage: sevli catalog keygen <folder outside every repository>");
                java.nio.file.Path key = java.nio.file.Path.of(args.get(1)).toAbsolutePath().resolve("sevli-catalog-signing.key");
                if (java.nio.file.Files.exists(key)) throw new IllegalArgumentException(key + " already exists; a new key would invalidate every published signature");
                String[] pair;
                try {
                    pair = Supported.newKeyPair();
                } catch (java.security.GeneralSecurityException e) {
                    throw new IOException(e);
                }
                java.nio.file.Files.createDirectories(key.getParent());
                java.nio.file.Files.writeString(key, pair[1] + System.lineSeparator());
                out.println("private key: " + key + "\n  keep it secret and out of every repository; the catalog's signing workflow needs it as a secret");
                out.println("public key (goes into Sevli's code, Supported.PUBLIC_KEY):\n  " + pair[0]);
            }
            case "sign" -> { // the maintainer: <file>.sig next to a generated supported.json
                int k = args.indexOf("--key");
                if (args.size() < 2 || k < 0 || k + 1 >= args.size()) throw new IllegalArgumentException("usage: sevli catalog sign <supported.json> --key <key file>");
                java.nio.file.Path file = java.nio.file.Path.of(args.get(1));
                try {
                    String sig = Supported.sign(java.nio.file.Files.readAllBytes(file), java.nio.file.Files.readString(java.nio.file.Path.of(args.get(k + 1))));
                    java.nio.file.Files.writeString(file.resolveSibling(file.getFileName() + ".sig"), sig + "\n");
                } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
                    throw new IOException("could not sign with that key: " + e.getMessage(), e);
                }
                out.println("signed " + file + " -> " + file.getFileName() + ".sig");
            }
            default -> throw new IllegalArgumentException("usage: sevli browse, or sevli add <id>");
        }
        return 0;
    }

    static void list(Config config, PrintStream out) {
        out.println("Baselines");
        for (Baselines.Baseline b : Baselines.SUPPORTED) {
            out.println("  " + b.id() + "  " + b.name() + "  " + (b.installed(config.home()) ? "installed"
                    : "download " + mb(b.downloadBytes()) + ", uses about " + mb(b.diskBytes())));
        }
        Catalog c;
        try {
            c = Catalog.load(config.catalogUrl);
        } catch (IOException | RuntimeException e) {
            out.println("\nPacks: the catalog is not reachable right now (" + e.getMessage() + ")");
            return;
        }
        for (Catalog.CatalogBaseline b : c.baselines) {
            if (Baselines.byId(b.id()).isEmpty()) out.println("  " + b.id() + "  " + b.name() + "  needs a newer sevli");
        }
        out.println("\nPacks");
        if (c.packs.isEmpty()) out.println("  none published yet");
        for (Catalog.Pack p : c.packs) {
            Catalog.PackVersion v = p.latest();
            if (v == null) continue;
            out.println("  " + p.id() + "  " + p.name() + " " + v.version() + " (" + v.mods() + " mods, " + p.baseline() + ")  download "
                    + mb(v.downloadBytes()) + ", uses about " + mb(v.diskBytes())
                    + (Baselines.byId(p.baseline()).isEmpty() ? "  needs a newer sevli" : ""));
        }
    }

    /** Installs a baseline (downloads from the official sources, hash-checked). Packs follow in the pack milestone. */
    static int install(Config config, String id, PrintStream out) throws IOException {
        var baseline = Baselines.byId(id);
        if (baseline.isPresent()) {
            Baselines.Baseline b = baseline.get();
            if (b.installed(config.home())) {
                out.println(b.name() + " is already installed");
                return 0;
            }
            config.save(); // creates the data home: the first install is the first thing written
            out.println("installing " + b.name() + " into " + config.home());
            b.base(config.home()).provision(out::println);
            out.println("installed " + b.name());
            return 0;
        }
        throw new IllegalArgumentException("'" + id + "' is not a baseline this sevli can install; see sevli browse");
    }

    static String mb(long bytes) {
        return bytes >= 1_000_000_000L ? String.format(Locale.ROOT, "%.1f GB", bytes / 1e9) : (bytes / 1_000_000) + " MB";
    }
}
