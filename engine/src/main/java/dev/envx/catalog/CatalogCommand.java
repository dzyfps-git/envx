package dev.envx.catalog;

import dev.envx.Config;

import java.io.IOException;
import java.io.PrintStream;
import java.util.List;
import java.util.Locale;

/** {@code envx catalog [list] | show <id> | install <id>}: the supported baselines and packs, and installing them. */
public final class CatalogCommand {
    private CatalogCommand() {}

    public static int run(Config config, List<String> args, PrintStream out) throws IOException {
        String sub = args.isEmpty() ? "list" : args.getFirst();
        switch (sub) {
            case "list" -> list(config, out);
            case "install" -> {
                if (args.size() < 2) throw new IllegalArgumentException("usage: envx catalog install <id>   (ids: envx catalog list)");
                return install(config, args.get(1), out);
            }
            default -> throw new IllegalArgumentException("usage: envx catalog [list] | install <id>");
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
            if (Baselines.byId(b.id()).isEmpty()) out.println("  " + b.id() + "  " + b.name() + "  needs a newer envx");
        }
        out.println("\nPacks");
        if (c.packs.isEmpty()) out.println("  none published yet");
        for (Catalog.Pack p : c.packs) {
            Catalog.PackVersion v = p.latest();
            if (v == null) continue;
            out.println("  " + p.id() + "  " + p.name() + " " + v.version() + " (" + v.mods() + " mods, " + p.baseline() + ")  download "
                    + mb(v.downloadBytes()) + ", uses about " + mb(v.diskBytes())
                    + (Baselines.byId(p.baseline()).isEmpty() ? "  needs a newer envx" : ""));
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
        throw new IllegalArgumentException("'" + id + "' is not a baseline this envx can install; see envx catalog list");
    }

    static String mb(long bytes) {
        return bytes >= 1_000_000_000L ? String.format(Locale.ROOT, "%.1f GB", bytes / 1e9) : (bytes / 1_000_000) + " MB";
    }
}
