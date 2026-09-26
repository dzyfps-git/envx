package dev.sevli.catalog;

import dev.sevli.Config;
import dev.sevli.store.Db;

import java.io.PrintStream;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * {@code sevli review}: on the maintainer's install (which indexes everything), what each environment runs that the
 * public supported list does not cover yet (ADR 0014). Nothing is published from here: publishing stays a catalog
 * change the maintainer makes and signs.
 */
public final class Review {
    private Review() {}

    /** One jar on a server compared with the public list. {@code kind}: new_mod | new_version | retired | revoked. */
    public record Item(String env, String file, String modId, String version, String kind, String publishedVersions) {}

    public static List<Item> items(Config config, Db db, Supported list) throws SQLException {
        Map<String, TreeSet<String>> published = new HashMap<>(); // mod id -> supported versions
        list.jars.values().forEach(j -> {
            if (j.modId() != null && !j.revoked()) published.computeIfAbsent(j.modId(), k -> new TreeSet<>()).add(j.version());
        });
        List<Item> out = new ArrayList<>();
        for (String env : config.environments.keySet()) {
            Long snap = db.queryLong("SELECT id FROM snapshot WHERE env=? AND kind='sync' ORDER BY id DESC LIMIT 1", env);
            if (snap == null) continue;
            for (Object[] r : db.query("""
                    SELECT f.rel_path, f.sha256, a.mod_id, a.mod_version FROM snapshot_file f LEFT JOIN artifact a ON a.sha256=f.sha256
                    WHERE f.snapshot_id=? ORDER BY a.mod_id, f.rel_path""",
                    rs -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)}, snap)) {
                String file = ((String) r[0]).substring(((String) r[0]).lastIndexOf('/') + 1);
                Supported.Jar j = list.get((String) r[1]);
                String modId = (String) r[2];
                String kind;
                if (j == null) kind = modId != null && published.containsKey(modId) ? "new_version" : "new_mod";
                else if (j.revoked()) kind = "revoked";
                else if (j.retired()) kind = "retired";
                else continue; // publicly supported as it is
                TreeSet<String> versions = modId == null ? null : published.get(modId);
                out.add(new Item(env, file, modId, (String) r[3], kind, versions == null ? null : String.join(", ", versions)));
            }
        }
        return out;
    }

    public static int run(Config config, Db db, PrintStream out) throws SQLException {
        if (!config.indexesEverything()) {
            out.println("sevli review compares the maintainer's install with the public list; this install indexes only supported jars");
            return 2;
        }
        Supported list = Supported.current(config, true);
        out.println("public supported list: " + (list == Supported.NONE ? "none published (or it could not be verified)" : "version " + list.catalogVersion
                + ", " + list.jars.size() + " jars"));
        List<Item> items = items(config, db, list);
        for (String env : config.environments.keySet()) {
            List<Item> mine = items.stream().filter(i -> i.env().equals(env)).toList();
            out.println("\n" + env + ": " + (mine.isEmpty() ? "everything it runs is publicly supported" : mine.size() + " jars not publicly supported as they are"));
            for (String kind : List.of("new_version", "new_mod", "retired", "revoked")) {
                List<Item> k = mine.stream().filter(i -> i.kind().equals(kind)).toList();
                if (k.isEmpty()) continue;
                out.println("  " + switch (kind) {
                    case "new_version" -> "new versions of supported mods";
                    case "new_mod" -> "mods not published yet";
                    case "retired" -> "retired (still work where accepted, not offered to new servers)";
                    default -> "revoked (hidden on public installs)";
                } + " (" + k.size() + "):");
                for (Item i : k.subList(0, Math.min(k.size(), 60))) {
                    out.println("    " + (i.modId() != null ? i.modId() + " " + i.version() : i.file())
                            + (kind.equals("new_version") ? "  (published: " + i.publishedVersions() + ")" : ""));
                }
                if (k.size() > 60) out.println("    ... " + (k.size() - 60) + " more");
            }
        }
        out.println("\nNothing here is published: support changes only when you publish a pack version to the catalog.");
        return 0;
    }
}
