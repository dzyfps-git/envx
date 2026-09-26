package dev.sevli.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sevli.Config;
import dev.sevli.Version;
import dev.sevli.env.LoaderLog;
import dev.sevli.index.JarParser;
import dev.sevli.query.LogService;
import dev.sevli.query.Scope;
import dev.sevli.store.Db;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code sevli api}: the versioned machine interface ({@code docs/api.md}). JSON lines in, one JSON line out per
 * request, in order. Opens the index read-only and never starts a sync; nothing a caller sends is stored.
 * Within version 1, operations and fields are only added.
 */
public final class Api {
    public static final int VERSION = 1;
    static final int MAX_ITEMS = 5000;
    private static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    /** Mixin's names for handlers merged into the target class: {@code <kind>$<hash>$<modid>$<handler>}. */
    private static final Pattern MERGED = Pattern.compile("^([A-Za-z]+)\\$[0-9a-z]+\\$([a-z0-9_.\\-]+)\\$(.+)$");

    private final Config config;
    private final Db db;
    private final Map<Long, Snap> snaps = new HashMap<>();
    private Map<String, java.time.LocalDateTime> failed;
    private Set<String> clientOnly;

    Api(Config config, Db db) {
        this.config = config;
        this.db = db;
    }

    /** Reads requests from {@code in} until end of input; returns 0. */
    public static int run(Config config, InputStream in, PrintStream out) throws IOException, SQLException {
        try (Db db = Db.open(config.home(), true)) {
            Api api = new Api(config, db);
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = r.readLine()) != null; ) {
                if (line.isBlank()) continue;
                out.println(GSON.toJson(api.handle(line)));
                out.flush();
            }
        }
        return 0;
    }

    public static String versionLine() {
        JsonObject o = new JsonObject();
        o.addProperty("api", VERSION);
        o.addProperty("sevli", Version.VALUE);
        return GSON.toJson(o);
    }

    /** One request line to one response object. */
    JsonObject handle(String line) {
        JsonObject resp = new JsonObject();
        resp.addProperty("api", VERSION);
        JsonObject req;
        try {
            JsonElement e = JsonParser.parseString(line);
            if (!e.isJsonObject()) throw new Fail("bad_request", "a request is a JSON object");
            req = e.getAsJsonObject();
        } catch (Fail f) {
            return error(resp, JsonNull.INSTANCE, f);
        } catch (RuntimeException bad) {
            return error(resp, JsonNull.INSTANCE, new Fail("bad_request", "not valid JSON: " + bad.getMessage()));
        }
        JsonElement id = req.has("id") ? req.get("id") : JsonNull.INSTANCE;
        try {
            String op = str(req, "op");
            if (op == null) throw new Fail("bad_request", "missing \"op\"");
            JsonObject result = switch (op) {
                case "snapshots" -> snapshots(req);
                case "match" -> match(req);
                case "owner" -> owner(req);
                case "mixins" -> mixins(req);
                case "diff" -> diff(req);
                default -> throw new Fail("unknown_op", "unknown op '" + op + "'; ops: snapshots, match, owner, mixins, diff");
            };
            resp.add("id", id);
            resp.addProperty("ok", true);
            resp.add("result", result);
            return resp;
        } catch (Fail f) {
            return error(resp, id, f);
        } catch (SQLException | RuntimeException e) {
            return error(resp, id, new Fail("internal", String.valueOf(e.getMessage())));
        }
    }

    private static JsonObject error(JsonObject resp, JsonElement id, Fail f) {
        resp.add("id", id);
        resp.addProperty("ok", false);
        JsonObject err = new JsonObject();
        err.addProperty("code", f.code);
        err.addProperty("message", f.getMessage());
        resp.add("error", err);
        return resp;
    }

    static final class Fail extends RuntimeException {
        final String code;

        Fail(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    // ---- snapshots and mod sets ----------------------------------------------------------------------------

    /** A snapshot with what every operation needs: its environment, loaded artifacts and all artifacts. */
    private record Snap(long id, String env, Config.EnvDef def, String kind, boolean current,
                        Set<Long> loaded, Set<Long> all) {}

    private String env(JsonObject req) {
        String env = str(req, "env");
        if (env == null) env = config.defaultEnv;
        if (env == null && config.environments.size() == 1) env = config.environments.keySet().iterator().next();
        if (env == null) throw new Fail("unknown_env", "no \"env\" and no default environment; known: " + config.environments.keySet());
        if (!config.environments.containsKey(env)) throw new Fail("unknown_env", "unknown environment '" + env + "'; known: " + config.environments.keySet());
        return env;
    }

    /** The snapshot a request names ({@code snapshot} id or {@code fingerprint}), else the environment's current one. */
    private Snap snap(JsonObject req, String idKey, String fpKey) throws SQLException {
        Long id = null;
        if (req.has(idKey) && !req.get(idKey).isJsonNull()) {
            JsonElement v = req.get(idKey);
            if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) id = v.getAsLong();
            else if (v.isJsonPrimitive() && v.getAsString().matches("[0-9a-f]{64}")) id = byFingerprint(v.getAsString());
            else throw new Fail("bad_request", "\"" + idKey + "\" is a snapshot id or a fingerprint");
            if (db.queryLong("SELECT id FROM snapshot WHERE id=?", id) == null) throw new Fail("unknown_snapshot", "no snapshot " + v);
        } else if (fpKey != null && str(req, fpKey) != null) {
            id = byFingerprint(str(req, fpKey));
        } else {
            String env = env(req);
            id = db.queryLong("SELECT id FROM snapshot WHERE env=? ORDER BY kind='sync' DESC, id DESC LIMIT 1", env);
            if (id == null) throw new Fail("unknown_snapshot", "environment '" + env + "' was never synced");
        }
        return load(id);
    }

    private long byFingerprint(String fp) throws SQLException {
        Long id = db.queryLong("SELECT max(id) FROM snapshot WHERE fingerprint=?", fp);
        if (id == null) throw new Fail("unknown_snapshot", "no snapshot with fingerprint " + fp);
        return id;
    }

    private Snap load(long id) throws SQLException {
        Snap s = snaps.get(id);
        if (s != null) return s;
        String env = db.queryString("SELECT env FROM snapshot WHERE id=?", id);
        Config.EnvDef def = config.environments.get(env);
        if (def == null) throw new Fail("unknown_env", "snapshot " + id + " belongs to '" + env + "', which is not configured");
        String kind = db.queryString("SELECT kind FROM snapshot WHERE id=?", id);
        Long current = db.queryLong("SELECT id FROM snapshot WHERE env=? ORDER BY kind='sync' DESC, id DESC LIMIT 1", env);
        Scope scope = Scope.of(config, db, env, id);
        Set<Long> loaded = new HashSet<>(db.query(scope.artifactSet(), rs -> rs.getLong(1)));
        Set<Long> all = new HashSet<>(db.query("SELECT artifact_id FROM snapshot_artifact WHERE snapshot_id=?", rs -> rs.getLong(1), id));
        s = new Snap(id, env, def, kind, current != null && current == id, loaded, all);
        snaps.put(id, s);
        return s;
    }

    /** {@code id version} (normalized) of every mod jar that declares {@code "environment": "client"}. */
    private Set<String> clientOnly() throws SQLException {
        if (clientOnly == null) {
            clientOnly = new HashSet<>(db.query("SELECT mod_id, mod_version FROM artifact WHERE mod_id IS NOT NULL AND "
                            + Scope.CLIENT_ONLY_SQL.replace("{a}", "artifact"),
                    rs -> rs.getString(1) + " " + LoaderLog.normalizeVersion(rs.getString(2))));
        }
        return clientOnly;
    }

    /**
     * The mods the loader actually loaded in this snapshot, as sorted {@code id@version} lines: its printed list minus
     * the nested client-only copies a dedicated server shows in the tree but does not load. Empty without a loader list.
     */
    private TreeSet<String> modLines(long snapshotId, Config.EnvDef def) throws SQLException {
        boolean server = def == null || !"client".equals(def.side);
        TreeSet<String> out = new TreeSet<>();
        for (String[] r : db.query("SELECT mod_id, version, nested FROM snapshot_loaded WHERE snapshot_id=?",
                rs -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)}, snapshotId)) {
            if (server && r[2].equals("1") && clientOnly().contains(r[0] + " " + LoaderLog.normalizeVersion(r[1]))) continue;
            out.add(r[0].toLowerCase(Locale.ROOT) + "@" + r[1]);
        }
        return out;
    }

    static String modset(Set<String> lines) {
        return lines.isEmpty() ? null : JarParser.sha256(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
    }

    private JsonObject snapshots(JsonObject req) throws SQLException {
        String env = env(req);
        Long current = db.queryLong("SELECT id FROM snapshot WHERE env=? ORDER BY kind='sync' DESC, id DESC LIMIT 1", env);
        JsonArray arr = new JsonArray();
        for (Object[] r : db.query("SELECT id, fingerprint, label, kind, taken_at, checked_at FROM snapshot WHERE env=? ORDER BY id DESC",
                rs -> new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)}, env)) {
            long id = (long) r[0];
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("fingerprint", (String) r[1]);
            o.addProperty("modset", modset(modLines(id, config.environments.get(env))));
            o.addProperty("label", (String) r[2]);
            o.addProperty("kind", (String) r[3]);
            o.addProperty("taken_at", (String) r[4]);
            o.addProperty("checked_at", (String) r[5]);
            o.addProperty("current", current != null && current == id);
            arr.add(o);
        }
        JsonObject res = new JsonObject();
        res.addProperty("env", env);
        res.add("snapshots", arr);
        return res;
    }

    private JsonObject match(JsonObject req) throws SQLException {
        String env = env(req);
        JsonArray mods = array(req, "mods");
        TreeSet<String> want = new TreeSet<>();
        for (JsonElement m : mods) {
            String id, version;
            if (m.isJsonArray() && m.getAsJsonArray().size() == 2) {
                id = m.getAsJsonArray().get(0).getAsString();
                version = m.getAsJsonArray().get(1).getAsString();
            } else if (m.isJsonObject() && m.getAsJsonObject().has("id") && m.getAsJsonObject().has("version")) {
                id = m.getAsJsonObject().get("id").getAsString();
                version = m.getAsJsonObject().get("version").getAsString();
            } else throw new Fail("bad_request", "each of \"mods\" is [id, version] or {\"id\":..,\"version\":..}");
            want.add(id.toLowerCase(Locale.ROOT) + "@" + version);
        }
        String modset = modset(want);
        JsonArray matched = new JsonArray();
        long bestId = -1;
        TreeSet<String> best = null;
        int bestScore = Integer.MAX_VALUE;
        for (long id : db.query("SELECT id FROM snapshot WHERE env=? ORDER BY id DESC", rs -> rs.getLong(1), env)) {
            TreeSet<String> have = modLines(id, config.environments.get(env));
            if (have.isEmpty()) continue;
            if (have.equals(want)) {
                matched.add(id);
                continue;
            }
            Set<String> diff = new HashSet<>(have);
            diff.addAll(want);
            Set<String> common = new HashSet<>(have);
            common.retainAll(want);
            int score = diff.size() - common.size();
            if (score < bestScore) {
                bestScore = score;
                bestId = id;
                best = have;
            }
        }
        JsonObject res = new JsonObject();
        res.addProperty("modset", modset);
        if (!matched.isEmpty()) {
            res.addProperty("status", "match");
            res.add("snapshots", matched);
            return res;
        }
        res.addProperty("status", "none");
        if (best != null) {
            JsonObject c = new JsonObject();
            c.addProperty("snapshot", bestId);
            TreeSet<String> onlyReq = new TreeSet<>(want);
            onlyReq.removeAll(best);
            TreeSet<String> onlySnap = new TreeSet<>(best);
            onlySnap.removeAll(want);
            c.addProperty("only_in_request_count", onlyReq.size());
            c.add("only_in_request", pairs(onlyReq));
            c.addProperty("only_in_snapshot_count", onlySnap.size());
            c.add("only_in_snapshot", pairs(onlySnap));
            res.add("closest", c);
        }
        return res;
    }

    /** Up to 50 {@code id@version} lines as [id, version] pairs. */
    private static JsonArray pairs(Set<String> lines) {
        JsonArray a = new JsonArray();
        for (String l : lines) {
            if (a.size() == 50) break;
            JsonArray p = new JsonArray();
            int at = l.indexOf('@');
            p.add(l.substring(0, at));
            p.add(l.substring(at + 1));
            a.add(p);
        }
        return a;
    }

    // ---- owner ----------------------------------------------------------------------------------------------

    private record ArtifactInfo(long id, String kind, String modId, String version, String sha256, String file) {}

    private final Map<Long, ArtifactInfo> artifacts = new HashMap<>();

    private ArtifactInfo artifact(long id) throws SQLException {
        ArtifactInfo a = artifacts.get(id);
        if (a == null) {
            a = db.query("SELECT id, kind, mod_id, mod_version, sha256, file_name FROM artifact WHERE id=?",
                    rs -> new ArtifactInfo(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)), id).getFirst();
            artifacts.put(id, a);
        }
        return a;
    }

    /** {mod, version, sha256, file} of an artifact; Minecraft is "minecraft" at the environment's version. */
    private JsonObject jar(ArtifactInfo a, Snap s) {
        JsonObject o = new JsonObject();
        boolean mc = "minecraft".equals(a.kind());
        o.addProperty("mod", mc ? "minecraft" : a.modId());
        o.addProperty("version", mc ? s.def().minecraft : a.version());
        o.addProperty("sha256", a.sha256());
        o.addProperty("file", a.file());
        return o;
    }

    private JsonArray nestedIn(long artifactId, Snap s) throws SQLException {
        JsonArray arr = new JsonArray();
        for (long p : db.query("SELECT parent_id FROM artifact_nested WHERE child_id=? ORDER BY parent_id", rs -> rs.getLong(1), artifactId)) {
            if (!s.all().contains(p)) continue;
            ArtifactInfo a = artifact(p);
            JsonObject o = new JsonObject();
            o.addProperty("mod", a.modId());
            o.addProperty("version", a.version());
            o.addProperty("sha256", a.sha256());
            arr.add(o);
        }
        return arr;
    }

    private JsonObject owner(JsonObject req) throws SQLException {
        Snap s = snap(req, "snapshot", "fingerprint");
        JsonArray keys = array(req, "keys");
        JsonArray results = new JsonArray();
        for (JsonElement k : keys) {
            if (!k.isJsonObject()) throw new Fail("bad_request", "each key is {\"class\":..,\"method\":..,\"desc\":..}");
            results.add(ownerOf(s, k.getAsJsonObject()));
        }
        JsonObject res = new JsonObject();
        res.add("snapshot", snapRef(s));
        res.add("results", results);
        return res;
    }

    private JsonObject ownerOf(Snap s, JsonObject key) throws SQLException {
        String raw = str(key, "class");
        if (raw == null) throw new Fail("bad_request", "a key needs \"class\"");
        String method = str(key, "method");
        String desc = str(key, "desc");
        String cls = raw.replace('.', '/');
        boolean hidden = false;
        int lambda = cls.indexOf("$$Lambda");
        if (lambda > 0) {
            cls = cls.substring(0, lambda);
            hidden = true;
        }
        JsonObject out = new JsonObject();
        List<long[]> classes = db.query("SELECT id, artifact_id FROM class WHERE name=?", rs -> new long[]{rs.getLong(1), rs.getLong(2)}, cls);
        classes.removeIf(c -> !s.all().contains(c[1]));
        String yarnClass = null, yarnMethod = null, yarnDesc = null;
        boolean memberFound = false;
        JsonArray candidates = new JsonArray();
        int loadedCount = 0;
        for (long[] c : classes) {
            ArtifactInfo a = artifact(c[1]);
            JsonObject cand = jar(a, s);
            boolean loaded = s.loaded().contains(c[1]);
            cand.addProperty("loaded", loaded);
            cand.add("nested_in", nestedIn(c[1], s));
            candidates.add(cand);
            if (loaded) loadedCount++;
            if (yarnClass == null || loaded) yarnClass = db.queryString("SELECT named FROM class WHERE id=?", c[0]);
            if (method != null) {
                List<String[]> m = desc != null
                        ? db.query("SELECT named, named_desc FROM member WHERE class_id=? AND kind='m' AND name=? AND descriptor=?",
                        rs -> new String[]{rs.getString(1), rs.getString(2)}, c[0], method, desc)
                        : db.query("SELECT named, named_desc FROM member WHERE class_id=? AND kind='m' AND name=? LIMIT 1",
                        rs -> new String[]{rs.getString(1), rs.getString(2)}, c[0], method);
                if (!m.isEmpty()) {
                    memberFound = true;
                    yarnMethod = m.getFirst()[0];
                    yarnDesc = m.getFirst()[1];
                }
            }
        }
        JsonObject mixin = null;
        if (method != null && !memberFound) {
            Matcher mm = MERGED.matcher(method);
            if (mm.matches()) {
                List<JsonObject> hits = mergedMixin(s, cls, mm.group(2), mm.group(3));
                if (!hits.isEmpty()) {
                    // The frame runs the mixin's code: its jar is the owner.
                    candidates = new JsonArray();
                    loadedCount = 0;
                    for (JsonObject h : hits) {
                        JsonObject cand = h.getAsJsonObject("jar").deepCopy();
                        cand.addProperty("loaded", true);
                        cand.add("nested_in", h.get("nested_in"));
                        candidates.add(cand);
                        loadedCount++;
                    }
                    mixin = hits.getFirst().deepCopy();
                    mixin.remove("jar");
                    mixin.remove("nested_in");
                    JsonObject j = hits.getFirst().getAsJsonObject("jar");
                    mixin.addProperty("mod", j.get("mod").isJsonNull() ? null : j.get("mod").getAsString());
                    mixin.addProperty("version", j.get("version").isJsonNull() ? null : j.get("version").getAsString());
                    mixin.addProperty("sha256", j.get("sha256").getAsString());
                    yarnMethod = mm.group(3);
                    yarnDesc = desc;
                }
            }
        }
        out.addProperty("status", loadedCount == 1 ? "probable" : loadedCount > 1 ? "ambiguous" : "none");
        out.add("candidates", candidates);
        out.addProperty("class_found", !classes.isEmpty());
        out.addProperty("member_found", method == null ? null : memberFound || mixin != null);
        if (hidden) out.addProperty("hidden_lambda", true);
        JsonObject yarn = new JsonObject();
        yarn.addProperty("class", yarnClass == null ? null : yarnClass.replace('/', '.'));
        yarn.addProperty("method", yarnMethod);
        yarn.addProperty("desc", yarnDesc);
        out.add("yarn", yarn);
        out.add("mixin", mixin == null ? JsonNull.INSTANCE : mixin);
        return out;
    }

    /** Declared mixins whose handler was merged into {@code cls} under this name, preferring the named mod. */
    private List<JsonObject> mergedMixin(Snap s, String cls, String modId, String handler) throws SQLException {
        List<JsonObject> all = new ArrayList<>();
        for (MixinRow r : mixinRows(s, cls, null, null)) {
            if (handler.equals(r.handler())) all.add(r.json(this, s));
        }
        List<JsonObject> named = all.stream().filter(o -> {
            JsonElement m = o.getAsJsonObject("jar").get("mod");
            return !m.isJsonNull() && m.getAsString().equalsIgnoreCase(modId);
        }).toList();
        return named.isEmpty() && all.size() == 1 ? all : named;
    }

    // ---- mixins ---------------------------------------------------------------------------------------------

    private record MixinRow(long artifactId, String config, String mixinClass, String kind, String handler, String targetName,
                            String targetDesc, String atValue, String atTarget, int priority, boolean cancellable, String side) {
        JsonObject json(Api api, Snap s) throws SQLException {
            JsonObject o = new JsonObject();
            ArtifactInfo a = api.artifact(artifactId);
            o.add("jar", api.jar(a, s));
            o.add("nested_in", api.nestedIn(artifactId, s));
            o.addProperty("mixin_class", mixinClass.replace('/', '.'));
            o.addProperty("config", config);
            o.addProperty("kind", kind);
            o.addProperty("handler", handler);
            o.addProperty("target", targetName == null ? null : targetName + (targetDesc == null ? "" : targetDesc));
            o.addProperty("at", atValue == null ? null : atValue + (atTarget == null ? "" : " " + atTarget));
            o.addProperty("priority", priority);
            o.addProperty("cancellable", cancellable);
            o.addProperty("side", side);
            o.addProperty("failed", api.failed(s, config, mixinClass));
            return o;
        }
    }

    private List<MixinRow> mixinRows(Snap s, String cls, String method, String desc) throws SQLException {
        String sql = "SELECT artifact_id, config, mixin_class, kind, handler, target_name, target_desc, at_value, at_target, priority, "
                + "cancellable, env_side FROM mixin WHERE target_class=?" + (method == null ? "" : " AND target_name=?");
        Object[] args = method == null ? new Object[]{cls} : new Object[]{cls, method};
        List<MixinRow> rows = db.query(sql, rs -> new MixinRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getInt(10),
                rs.getInt(11) == 1, rs.getString(12)), args);
        rows.removeIf(r -> !s.loaded().contains(r.artifactId()) || s.def().hides(r.side())
                || (desc != null && r.targetDesc() != null && !r.targetDesc().equals(desc)));
        return rows;
    }

    /** True/false from the current server's log; null for past snapshots (no log evidence for them). */
    private Boolean failed(Snap s, String cfg, String mixinClass) {
        if (!s.current() || !"sync".equals(s.kind())) return null;
        if (failed == null) failed = LogService.failedMixins(config.home(), s.env());
        String cls = mixinClass.replace('/', '.');
        for (String k : failed.keySet()) { // config:relative.Class
            int colon = k.indexOf(':');
            if (colon > 0 && k.substring(0, colon).equals(cfg) && cls.endsWith("." + k.substring(colon + 1))) return true;
        }
        return false;
    }

    private JsonObject mixins(JsonObject req) throws SQLException {
        Snap s = snap(req, "snapshot", "fingerprint");
        JsonArray targets = array(req, "targets");
        JsonArray results = new JsonArray();
        for (JsonElement t : targets) {
            if (!t.isJsonObject() || str(t.getAsJsonObject(), "class") == null) throw new Fail("bad_request", "each target is {\"class\":..[,\"method\":..,\"desc\":..]}");
            JsonObject target = t.getAsJsonObject();
            String cls = str(target, "class").replace('.', '/');
            JsonArray list = new JsonArray();
            for (MixinRow r : mixinRows(s, cls, str(target, "method"), str(target, "desc"))) {
                JsonObject o = r.json(this, s);
                JsonObject j = o.getAsJsonObject("jar");
                o.remove("jar");
                JsonObject flat = new JsonObject();
                flat.add("mod", j.get("mod"));
                flat.add("version", j.get("version"));
                flat.add("sha256", j.get("sha256"));
                o.entrySet().forEach(e -> flat.add(e.getKey(), e.getValue()));
                list.add(flat);
            }
            JsonObject one = new JsonObject();
            one.add("mixins", list);
            results.add(one);
        }
        JsonObject res = new JsonObject();
        res.add("snapshot", snapRef(s));
        res.add("results", results);
        return res;
    }

    // ---- diff -----------------------------------------------------------------------------------------------

    private JsonObject diff(JsonObject req) throws SQLException {
        if (!req.has("from") || !req.has("to")) throw new Fail("bad_request", "diff needs \"from\" and \"to\" (snapshot ids or fingerprints)");
        Snap a = snap(req, "from", null), b = snap(req, "to", null);
        Map<String, ArtifactInfo> from = mods(a), to = mods(b);
        JsonArray added = new JsonArray(), removed = new JsonArray(), changed = new JsonArray();
        for (var e : to.entrySet()) {
            ArtifactInfo old = from.get(e.getKey());
            if (old == null) added.add(jar(e.getValue(), b));
            else if (!old.sha256().equals(e.getValue().sha256())) {
                JsonObject c = new JsonObject();
                c.addProperty("mod", e.getKey());
                c.addProperty("from", old.version());
                c.addProperty("to", e.getValue().version());
                c.addProperty("from_sha256", old.sha256());
                c.addProperty("to_sha256", e.getValue().sha256());
                changed.add(c);
            }
        }
        for (var e : from.entrySet()) if (!to.containsKey(e.getKey())) removed.add(jar(e.getValue(), a));
        JsonObject res = new JsonObject();
        res.add("from", snapRef(a));
        res.add("to", snapRef(b));
        res.add("added", added);
        res.add("removed", removed);
        res.add("changed", changed);
        return res;
    }

    /** Loaded jars with a mod id, by mod id (the newest artifact when a snapshot has two loaded copies). */
    private Map<String, ArtifactInfo> mods(Snap s) throws SQLException {
        Map<String, ArtifactInfo> out = new TreeMap<>();
        for (long id : s.loaded()) {
            ArtifactInfo a = artifact(id);
            if (a.modId() == null) continue;
            out.merge(a.modId(), a, (x, y) -> x.id() > y.id() ? x : y);
        }
        return out;
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    private JsonObject snapRef(Snap s) throws SQLException {
        JsonObject o = new JsonObject();
        o.addProperty("id", s.id());
        o.addProperty("env", s.env());
        o.addProperty("fingerprint", db.queryString("SELECT fingerprint FROM snapshot WHERE id=?", s.id()));
        o.addProperty("modset", modset(modLines(s.id(), s.def())));
        return o;
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static JsonArray array(JsonObject req, String key) {
        JsonElement e = req.get(key);
        if (e == null || !e.isJsonArray()) throw new Fail("bad_request", "\"" + key + "\" must be an array");
        if (e.getAsJsonArray().size() > MAX_ITEMS) throw new Fail("too_many", "at most " + MAX_ITEMS + " items per request; split the batch");
        return e.getAsJsonArray();
    }
}
