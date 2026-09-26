package dev.sevli.query;

import dev.sevli.Config;
import dev.sevli.store.Db;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Which environment snapshot a query runs against. Every query is scoped; nothing leaks across packs.
 * A {@code mod:} qualifier narrows the <em>results</em> to some mods ({@link #results}); targets are
 * still resolved against the whole environment ({@link #artifacts}), so
 * {@code refs ServerTickEvents mod:openpartiesandclaims} finds Fabric's class but lists only OPAC's users.
 */
public record Scope(String env, long snapshotId, String takenAt, String source, boolean loaderList, Config.EnvDef def,
                    Set<Long> only, String onlyLabel, String at, Overlay overlay) {

    /**
     * {@code project:}: the project's built jar ({@code added}: it and its nested jars) used in place of the deployed
     * copy of the same mod ({@code replaced}), so answers show where the project and pack mods meet before deploying.
     */
    public record Overlay(String modId, Set<Long> added, Set<Long> replaced, String note) {}

    /**
     * An MCP process pins the current snapshot on first use, so a background sync finishing mid-session
     * never changes answers under an agent. New sessions see the new snapshot. CLI calls are one-shot anyway.
     */
    private static final Map<String, Scope> PINNED = new ConcurrentHashMap<>();
    private static volatile boolean pinCurrent;

    public static void pinCurrentSnapshot() {
        pinCurrent = true;
    }

    /** True for a past snapshot asked for with {@code env@label}; answers then say so. */
    public boolean historical() {
        return at != null;
    }

    /** SQL predicate restricting {@code <alias>.artifact_id} to artifacts loaded in this snapshot (with the project overlay). */
    public String artifacts(String alias) {
        return alias + ".artifact_id IN (" + artifactSet() + ")";
    }

    /**
     * SQL condition on artifact alias {@code {a}}: its {@code fabric.mod.json} says {@code "environment": "client"}.
     * A dedicated server does not load such mods, although its loader log prints nested copies of them in the mod tree.
     */
    public static final String CLIENT_ONLY_SQL =
            "(CASE WHEN json_valid({a}.meta_json) THEN json_extract({a}.meta_json, '$.environment') END) = 'client'";

    /** SQL subquery of the artifact ids this scope sees (on a server, never client-only mods; weak spot 13). */
    public String artifactSet() {
        String loaded = "SELECT artifact_id FROM snapshot_artifact WHERE snapshot_id=" + snapshotId + " AND loaded=1";
        if (def == null || !"client".equals(def.side)) {
            loaded += " AND artifact_id NOT IN (SELECT id FROM artifact WHERE " + CLIENT_ONLY_SQL.replace("{a}", "artifact") + ")";
        }
        if (overlay == null) return loaded;
        return loaded + (overlay.replaced().isEmpty() ? "" : " AND artifact_id NOT IN (" + ids(overlay.replaced()) + ")")
                + " UNION SELECT id FROM artifact WHERE id IN (" + ids(overlay.added()) + ")";
    }

    private static String ids(Set<Long> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    /** Like {@link #artifacts}, further limited to the {@code mod:} selection when there is one. */
    public String results(String alias) {
        if (only == null) return artifacts(alias);
        return artifacts(alias) + " AND " + alias + ".artifact_id IN (" + ids(only) + ")";
    }

    public boolean scoped() {
        return only != null;
    }

    public boolean includes(long artifactId) {
        return only == null || only.contains(artifactId);
    }

    public Scope restrict(Set<Long> ids, String label) {
        return new Scope(env, snapshotId, takenAt, source, loaderList, def, ids, label, at, overlay);
    }

    public Scope withOverlay(Overlay o) {
        return new Scope(env, snapshotId, takenAt, source, loaderList, def, only, onlyLabel, at, o);
    }

    /** First line of an answer that is not plain current-server truth (a past snapshot, a project build), else "". */
    public String banner() {
        String b = at == null ? "" : "[" + env + "@" + at + ": past snapshot " + snapshotId + ", not the current server]\n";
        return overlay == null ? b : b + "[" + overlay.note() + "]\n";
    }

    /** Short suffix for answer headers, e.g. {@code " [mod: lithium]"}. */
    public String scopeNote() {
        return only == null ? "" : " [mod: " + onlyLabel + "]";
    }

    /** A given snapshot of {@code env}; marked past (by its label, else {@code #id}) unless it is the current one. */
    public static Scope of(Config config, Db db, String env, long snapshotId) throws SQLException {
        Config.EnvDef def = config.environments.get(env);
        Long current = db.queryLong("SELECT id FROM snapshot WHERE env=? ORDER BY kind='sync' DESC, id DESC LIMIT 1", env);
        var rows = db.query("SELECT taken_at, source, loader_list, label FROM snapshot WHERE id=? AND env=?",
                rs -> new Scope(env, snapshotId, rs.getString(1), rs.getString(2), rs.getInt(3) == 1, def, null, null,
                        current != null && current == snapshotId ? null : rs.getString(4) != null ? rs.getString(4) : "#" + snapshotId, null),
                snapshotId, env);
        if (rows.isEmpty()) throw new IllegalArgumentException("No snapshot " + snapshotId + " of " + env);
        return rows.getFirst();
    }

    /**
     * {@code envArg} is an environment name, optionally with {@code @<label>} for a past snapshot
     * ({@code myserver@4.0.5}); without it, the working directory's environment. The current snapshot is the
     * latest one from the live source; imported history is only used when asked for by label.
     */
    public static Scope resolve(Config config, Db db, String envArg, Path cwd) throws SQLException {
        String at = null;
        if (envArg != null && envArg.contains("@")) {
            at = envArg.substring(envArg.indexOf('@') + 1).trim();
            envArg = envArg.substring(0, envArg.indexOf('@')).trim();
            if (at.isEmpty() || at.equals("current")) at = null;
        }
        String env = envArg != null && !envArg.isBlank() ? envArg : config.envFor(cwd);
        if (env == null) {
            throw new IllegalArgumentException("No environment for " + cwd + ". Link it with `sevli link <dir> <env>` "
                    + "or pass env. Known: " + config.environments.keySet());
        }
        Config.EnvDef def = config.environments.get(env);
        if (def == null) throw new IllegalArgumentException("Unknown environment '" + env + "'. Known: " + config.environments.keySet());
        String cols = "SELECT id, taken_at, source, loader_list FROM snapshot WHERE env=? ";
        if (at != null) {
            String label = at;
            var rows = db.query(cols + "AND label=? ORDER BY id DESC LIMIT 1",
                    rs -> new Scope(env, rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4) == 1, def, null, null, label, null), env, label);
            if (rows.isEmpty()) {
                List<String> known = db.query("SELECT label FROM snapshot WHERE env=? AND label IS NOT NULL GROUP BY label ORDER BY min(id)",
                        rs -> rs.getString(1), env);
                throw new IllegalArgumentException("No snapshot of " + env + " labeled '" + label + "'. Known: " + known);
            }
            return rows.getFirst();
        }
        Scope pinned = pinCurrent ? PINNED.get(env) : null;
        if (pinned != null) return pinned;
        var rows = db.query(cols + "ORDER BY kind='sync' DESC, id DESC LIMIT 1",
                rs -> new Scope(env, rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4) == 1, def, null, null, null, null), env);
        if (rows.isEmpty()) throw new IllegalArgumentException("Environment '" + env + "' was never synced. Run `sevli sync " + env + "`.");
        if (pinCurrent) PINNED.put(env, rows.getFirst());
        return rows.getFirst();
    }
}
