package dev.sevli.query;

import dev.sevli.Config;
import dev.sevli.catalog.Supported;
import dev.sevli.store.Db;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Supported-only indexing at query time (ADR 0014). Syncs index only supported jars, but an index may hold jars from
 * before that rule, or jars whose support was revoked since; queries hide those too, in the current snapshot and in
 * history alike. Hidden data is not deleted. The maintainer's install ({@link Config#indexesEverything}) sees all.
 */
final class Visibility {
    private Visibility() {}

    /** What an environment's queries may not see, and what they cannot see because it was never indexed. */
    record Env(Set<Long> hidden, Map<Long, String> hiddenTop) {}

    private static final Env ALL = new Env(Set.of(), Map.of());
    private static final Map<String, Object[]> CACHE = new ConcurrentHashMap<>();

    /** Top-level jars shown only when supported: active and accepted for this environment, or retired. Never revoked. */
    static boolean visible(Supported list, String sha, int accepted) {
        Supported.Jar j = list.get(sha);
        if (j == null || j.revoked()) return false;
        return j.retired() || j.since() <= accepted;
    }

    /** Artifact ids hidden for {@code env}: unsupported top-level jars and the jars nested only in them. */
    static Env of(Config config, Db db, String env) throws SQLException {
        if (config.indexesEverything()) return ALL;
        Supported list = Supported.cached(config);
        Config.EnvDef def = config.environments.get(env);
        int accepted = def != null && def.supportAccepted != null ? def.supportAccepted : list.catalogVersion;
        Long last = db.queryLong("SELECT max(id) FROM snapshot WHERE env=?", env);
        String key = last + "|" + list.catalogVersion + "|" + accepted + "|" + System.identityHashCode(db);
        Object[] hit = CACHE.get(env);
        if (hit != null && hit[0].equals(key)) return (Env) hit[1];

        Set<Long> visibleTop = new HashSet<>();
        Map<Long, String> hiddenTop = new HashMap<>();
        for (Object[] r : db.query("""
                SELECT DISTINCT a.id, a.sha256, a.kind, coalesce(a.mod_id || ' ' || coalesce(a.mod_version, ''), a.file_name)
                FROM snapshot_file f JOIN snapshot s ON s.id=f.snapshot_id JOIN artifact a ON a.sha256=f.sha256 WHERE s.env=?""",
                rs -> new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)}, env)) {
            if (visible(list, (String) r[1], accepted)) visibleTop.add((long) r[0]);
            else hiddenTop.put((long) r[0], ((String) r[3]).trim());
        }
        Env result;
        if (hiddenTop.isEmpty()) {
            result = ALL;
        } else {
            Set<Long> hidden = closure(db, hiddenTop.keySet());
            hidden.removeAll(closure(db, visibleTop)); // a library nested in a supported jar too stays visible
            hidden.removeIf(id -> {
                try {
                    return "minecraft".equals(db.queryString("SELECT kind FROM artifact WHERE id=?", id)); // the baseline
                } catch (SQLException e) {
                    return false;
                }
            });
            result = new Env(Set.copyOf(hidden), Map.copyOf(hiddenTop));
        }
        CACHE.put(env, new Object[]{key, result});
        return result;
    }

    /** The ids and every jar nested in them, however deep. */
    private static Set<Long> closure(Db db, Set<Long> roots) throws SQLException {
        Set<Long> seen = new HashSet<>(roots);
        ArrayDeque<Long> todo = new ArrayDeque<>(roots);
        while (!todo.isEmpty()) {
            for (long child : db.query("SELECT child_id FROM artifact_nested WHERE parent_id=?", rs -> rs.getLong(1), todo.poll())) {
                if (seen.add(child)) todo.add(child);
            }
        }
        return seen;
    }

    /**
     * The note for answers that claim completeness (no callers, no mixins, no matches, counts) when jars on the server
     * in this snapshot are not indexed; null when everything is.
     */
    static Scope.Gap gap(Db db, long snapshotId, Env env) throws SQLException {
        List<String> names = new ArrayList<>();
        int jars = db.queryInt("SELECT count(*) FROM snapshot_file WHERE snapshot_id=?", snapshotId);
        if (!env.hiddenTop().isEmpty()) {
            for (long id : db.query("""
                    SELECT a.id FROM snapshot_file f JOIN artifact a ON a.sha256=f.sha256 WHERE f.snapshot_id=?""",
                    rs -> rs.getLong(1), snapshotId)) {
                String n = env.hiddenTop().get(id);
                if (n != null) names.add(n);
            }
        }
        int unindexedRows = 0;
        if (db.queryInt("SELECT count(*) FROM sqlite_master WHERE name='snapshot_unindexed'") > 0) {
            List<String> rows = db.query("""
                    SELECT coalesce(mod_id || ' ' || coalesce(version, ''), rel_path) FROM snapshot_unindexed WHERE snapshot_id=? ORDER BY 1""",
                    rs -> rs.getString(1).trim(), snapshotId);
            unindexedRows = rows.size();
            names.addAll(rows);
        }
        if (names.isEmpty()) return null;
        names.sort(null);
        return new Scope.Gap(names.size(), jars + unindexedRows, List.copyOf(names));
    }
}
