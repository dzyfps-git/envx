package dev.envx.query;

import dev.envx.index.JarParser;

import java.io.IOException;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The project section of {@code env}: how the project in the working directory builds, compared with what the
 * server actually runs (loader log and mod jars), whether this build is the deployed one, and whether the
 * project's declared dependencies are satisfied by the server.
 */
final class ProjectReport {
    private final QueryService q;

    ProjectReport(QueryService q) {
        this.q = q;
    }

    List<String> lines(Scope s, Project p) throws SQLException, IOException {
        List<String> out = new ArrayList<>();
        out.add("project " + p.modId() + " " + p.version() + " (" + p.dir() + ")");

        // Build settings vs the server's runtime. Yarn is a development-time mapping; the server runs intermediary.
        List<String> cmp = new ArrayList<>();
        for (String k : List.of("minecraft", "fabricloader", "fabric-api", "java")) {
            String mine = p.build().get(k);
            if (mine == null) continue;
            String server = serverVersion(s, k);
            if (server == null) cmp.add(k + " " + mine);
            else if (sameVersion(mine, server)) cmp.add(k + " " + mine + " =");
            else cmp.add(k + " " + mine + " (server " + server + ")");
        }
        String yarn = p.build().get("yarn");
        if (yarn != null) {
            String envYarn = s.def().mappings.startsWith("yarn:") ? s.def().mappings.substring(5) : null;
            // Yarn is build-time only: the built jar is remapped to intermediary, which is what the server runs. "= envx"
            // alone read like a server match (Q15, Codex 1d: "the server is indexed with the same Yarn mappings").
            cmp.add("yarn " + yarn + " (build-time; the server runs intermediary names" + (envYarn == null ? "" : yarn.equals(envYarn)
                    ? "; envx displays the same Yarn" : "; envx displays " + envYarn) + ")");
        }
        if (p.build().containsKey("loom")) cmp.add("loom " + p.build().get("loom") + " (build-time)");
        if (p.build().containsKey("gradle")) cmp.add("gradle " + p.build().get("gradle"));
        out.add("  builds with: " + String.join("; ", cmp));

        // Is this build what the server runs?
        var deployed = q.db().query("""
                SELECT a.mod_version, a.sha256, a.file_name FROM snapshot_artifact sa JOIN artifact a ON a.id=sa.artifact_id
                WHERE sa.snapshot_id=? AND sa.loaded=1 AND a.mod_id=? ORDER BY a.kind='mod' DESC LIMIT 1""",
                rs -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)}, s.snapshotId(), p.modId());
        String built = p.jar() == null ? "not built (no jar in build/libs)"
                : "built " + p.jar().getFileName() + (p.jarStale() ? " (STALE: sources changed after this build)" : "");
        if (deployed.isEmpty()) {
            out.add("  on server: not deployed" + wasIn(s, p.modId()));
        } else {
            String[] d = deployed.getFirst();
            String same = p.jar() != null && JarParser.sha256(Files.readAllBytes(p.jar())).equals(d[1]) ? " (the same jar as this build)"
                    : d[0] != null && d[0].equals(p.version()) ? " (same version, different jar)" : "";
            out.add("  on server: " + p.modId() + " " + d[0] + " as " + d[2] + same);
        }
        out.add("  " + built + (p.jar() == null ? "" : "; add project: to a find/refs/mixins/source/grep query to use this build instead of the server's copy"));

        // Declared dependencies vs the server.
        List<String> deps = new ArrayList<>();
        for (var e : p.depends().entrySet()) {
            String server = serverVersion(s, e.getKey());
            Boolean ok = server == null ? Boolean.FALSE : VersionRange.satisfies(server, e.getValue());
            deps.add(e.getKey() + " " + e.getValue() + (server == null ? " MISSING on server"
                    : ok == null ? " (server " + server + ")" : ok ? " ok" : " NOT satisfied (server " + server + ")"));
        }
        if (!deps.isEmpty()) out.add("  depends: " + String.join("; ", deps));
        // Soft relations: a compat mod whose target mod left the server is dead weight; a present "breaks" is fatal.
        List<String> soft = new ArrayList<>();
        for (var e : p.optional().entrySet()) {
            String server = serverVersion(s, e.getKey());
            soft.add(e.getKey() + " " + e.getValue() + (server != null ? " (server " + server + ")" : " NOT on server" + wasIn(s, e.getKey())));
        }
        if (!soft.isEmpty()) out.add("  suggests: " + String.join("; ", soft));
        for (var e : p.breaks().entrySet()) {
            String server = serverVersion(s, e.getKey());
            if (server != null) out.add("  breaks: " + e.getKey() + " " + e.getValue() + " but the server has " + server);
        }
        if (!p.mixinConfigs().isEmpty()) out.add("  mixins: " + String.join(", ", p.mixinConfigs()) + " (check_mixins validates targets and competing injections)");
        return out;
    }

    /** "; was in <mod> (<versions>)" when past versions had the mod, else "". */
    private String wasIn(Scope s, String modId) {
        String prefix = "Not in the current server, but in past versions: ";
        for (String l : q.pastMods(s, modId).lines().toList()) {
            if (l.startsWith(prefix)) return "; was in " + l.substring(prefix.length()).replaceFirst("\\. Ask with .*$", "");
        }
        return "";
    }

    /** What the server runs for an id: the loader's list first (java, minecraft, loader), then mod jars. */
    String serverVersion(Scope s, String id) throws SQLException {
        String v = q.db().queryString("SELECT version FROM snapshot_loaded WHERE snapshot_id=? AND lower(mod_id)=lower(?) LIMIT 1", s.snapshotId(), id);
        if (v != null) return v;
        return q.db().queryString("""
                SELECT a.mod_version FROM snapshot_artifact sa JOIN artifact a ON a.id=sa.artifact_id
                WHERE sa.snapshot_id=? AND sa.loaded=1 AND lower(a.mod_id)=lower(?) LIMIT 1""", s.snapshotId(), id);
    }

    static boolean sameVersion(String a, String b) {
        return a.equalsIgnoreCase(b) || VersionRange.compare(a, b) == 0;
    }

    /** Fabric-style version ranges: *, x wildcards, >=, >, <=, <, =, ~, ^, space = and, || = or. Null when unparseable. */
    static final class VersionRange {
        private VersionRange() {}

        static Boolean satisfies(String version, String range) {
            try {
                for (String alt : range.split("\\|\\|")) {
                    boolean all = true;
                    for (String c : alt.trim().split("\\s+")) {
                        if (!c.isEmpty() && !one(version, c)) {
                            all = false;
                            break;
                        }
                    }
                    if (all) return true;
                }
                return false;
            } catch (RuntimeException e) {
                return null;
            }
        }

        private static boolean one(String v, String c) {
            if (c.equals("*")) return true;
            for (String op : List.of(">=", "<=", ">", "<", "=", "~", "^")) {
                if (c.startsWith(op)) {
                    String t = c.substring(op.length());
                    int cmp = compare(v, t.replace(".x", ".0").replace(".X", ".0"));
                    return switch (op) {
                        case ">=" -> cmp >= 0;
                        case "<=" -> cmp <= 0;
                        case ">" -> cmp > 0;
                        case "<" -> cmp < 0;
                        case "=" -> cmp == 0;
                        case "~" -> cmp >= 0 && prefix(v, t, 2);
                        default -> cmp >= 0 && prefix(v, t, 1); // ^
                    };
                }
            }
            if (c.contains("x") || c.contains("X") || c.endsWith("*")) return prefix(v, c.replaceAll("[.][xX*].*$", ""), 9);
            return compare(v, c) == 0;
        }

        /** Same first {@code n} numeric components as {@code t}. */
        private static boolean prefix(String v, String t, int n) {
            long[] a = nums(v), b = nums(t);
            for (int i = 0; i < Math.min(n, b.length); i++) if (i >= a.length || a[i] != b[i]) return false;
            return true;
        }

        /** Compares leading numeric components; build metadata (+...) is ignored, a pre-release (-...) sorts first. */
        static int compare(String a, String b) {
            long[] x = nums(a), y = nums(b);
            for (int i = 0; i < Math.max(x.length, y.length); i++) {
                long p = i < x.length ? x[i] : 0, r = i < y.length ? y[i] : 0;
                if (p != r) return Long.compare(p, r);
            }
            boolean preA = core(a).contains("-"), preB = core(b).contains("-");
            return preA == preB ? 0 : preA ? -1 : 1;
        }

        private static String core(String v) {
            int plus = v.indexOf('+');
            return (plus < 0 ? v : v.substring(0, plus)).toLowerCase(Locale.ROOT);
        }

        private static long[] nums(String v) {
            String c = core(v);
            int dash = c.indexOf('-');
            if (dash >= 0) c = c.substring(0, dash);
            String[] parts = c.split("\\.");
            List<Long> out = new ArrayList<>();
            for (String part : parts) {
                String d = part.replaceAll("[^0-9].*$", "");
                if (d.isEmpty()) break;
                out.add(Long.parseLong(d));
            }
            return out.stream().mapToLong(Long::longValue).toArray();
        }
    }
}
