package dev.sevli.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sevli.Config;
import dev.sevli.query.GrepService;
import dev.sevli.query.MixinCheck;
import dev.sevli.query.ModFilter;
import dev.sevli.query.Out;
import dev.sevli.query.ProjectOverlay;
import dev.sevli.query.QueryService;
import dev.sevli.query.Scope;
import dev.sevli.query.SourceService;
import dev.sevli.store.Db;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The query surface shared by the MCP server and the CLI, so both always behave identically.
 * Descriptions are one line on purpose: every tool schema is sent with every agent request.
 */
public final class Tools {
    public record Param(String name, String description, boolean required, String type) {}

    public record Tool(String name, String description, List<Param> params, Handler handler) {
        /** The parameter a CLI positional argument fills. */
        public String primary() {
            return params.isEmpty() ? null : params.getFirst().name();
        }
    }

    @FunctionalInterface
    public interface Handler {
        String run(Ctx ctx, Scope scope, JsonObject args) throws Exception;
    }

    /** Lazily opened services; one per process. */
    public static final class Ctx {
        final Config config;
        final QueryService q;
        final SourceService source;
        final MixinCheck mixinCheck;
        final GrepService grep;
        /** "mcp" or "cli" for the local call log; null disables logging (tests). */
        final String via;

        public Ctx(Config config, Db db, String via) {
            this.config = config;
            this.via = via;
            this.q = new QueryService(config, db);
            this.source = new SourceService(q);
            this.mixinCheck = new MixinCheck(q);
            this.grep = new GrepService(q);
        }
    }

    private static final Param ENV = new Param("env", "Environment; default: this directory's, current. name@<version> queries a past snapshot", false, "string");
    /** Tools whose main argument may carry a {@code mod:<id>[,<id>]} qualifier that narrows the results. */
    private static final Set<String> MOD_SCOPED = Set.of("find", "outline", "source", "refs", "mixins", "grep");

    public static final Map<String, Tool> ALL = new LinkedHashMap<>();

    static {
        add(new Tool("env", "Environment summary (versions, snapshot age, history); filter=a,b describes mods; diff:A..B compares pack versions and what changed for the current project; errors[:text] summarizes server log errors and crashes.",
                List.of(new Param("filter", "Mod id, name or jar file name substrings, comma-separated; or diff:<from>..<to>; or errors[:text]", false, "string"), ENV),
                (c, s, a) -> c.q.env(s, str(a, "filter"), budget(a), a.has("_cwd") ? Path.of(str(a, "_cwd")) : null)));
        add(new Tool("find", "Find classes/members by Yarn, intermediary or stack-frame name (LivingEntity.tick, class_1309.method_5773) or wildcard (*Tick*); mod:<id> alone describes a mod.",
                List.of(new Param("query", "Name, wildcard or keyword; add mod:<id>[,<id>] to search inside mods", true, "string"), ENV),
                (c, s, a) -> c.q.find(s, str(a, "query"), budget(a))));
        add(new Tool("outline", "Members of a class incl. inherited matches for filter (Yarn + runtime names, signatures, lines), or mod:<id> details.",
                List.of(new Param("target", "Class name or mod:<id>", true, "string"), new Param("filter", "Only members containing this (searches superclasses too)", false, "string"), ENV),
                (c, s, a) -> c.q.outline(s, str(a, "target"), str(a, "filter"), budget(a))));
        add(new Tool("source", "Decompiled Yarn-named source of Class.member or Class.a,b,c (Minecraft or any mod), or lines=a-b of the class.",
                List.of(new Param("target", "Class.member[,member...] or Class", true, "string"), new Param("lines", "Line range a-b", false, "string"), ENV),
                (c, s, a) -> c.source.source(s, str(a, "target"), str(a, "lines"), budget(a))));
        add(new Tool("refs", "Call sites of Class.member across Minecraft and all mods, plus overriders; for a class, which classes use it and how.",
                List.of(new Param("target", "Class.member or Class; add mod:<id> to limit to users in those mods", true, "string"), ENV),
                (c, s, a) -> c.q.refs(s, str(a, "target"), budget(a))));
        add(new Tool("mixins", "Mixins declared on a class or Class.method (kind, @At, priority, cancellable, mod version); mod:<id> alone lists that mod's injections.",
                List.of(new Param("target", "Class or Class.method; add mod:<id> to limit to those mods", true, "string"),
                        new Param("includeAccessors", "Also list @Accessor/@Invoker", false, "boolean"), ENV),
                (c, s, a) -> c.q.mixins(s, str(a, "target"), a.has("includeAccessors") && a.get("includeAccessors").getAsBoolean(), budget(a))));
        add(new Tool("check_mixins", "Validate a project's built mixins against the environment and list other mods injecting at the same methods.",
                List.of(new Param("project", "Project directory; default: working directory", false, "string"), ENV),
                (c, s, a) -> c.mixinCheck.check(s, a.has("project") ? Path.of(str(a, "project")) : Path.of(str(a, "_cwd")), budget(a))));
        add(new Tool("grep", "Regex search over the server's configs/datapacks and loaded mods' data files (scope: config, resources, source, logs).",
                List.of(new Param("pattern", "Java regex, case-insensitive; add mod:<id> to limit to those mods", true, "string"),
                        new Param("scope", "config,resources,source,logs", false, "string"),
                        new Param("path", "Only paths containing this", false, "string"), ENV),
                (c, s, a) -> c.grep.grep(s, str(a, "pattern"), str(a, "scope"), str(a, "path"), budget(a))));
    }

    private static void add(Tool t) {
        ALL.put(t.name(), t);
    }

    /** Runs a tool; errors come back as text so an agent can read and recover from them. Every call is logged locally. */
    public static Result call(Ctx ctx, String name, JsonObject args, Path cwd) {
        long t0 = System.nanoTime();
        String[] env = {null};
        JsonObject asked = args.deepCopy(); // run() rewrites args (e.g. strips mod:); log what the agent sent
        Result r = run(ctx, name, args, cwd, env);
        if (ctx.via != null) {
            CallLog.record(ctx.config.home(), ctx.via, name, asked, env[0], cwd, r, (System.nanoTime() - t0) / 1_000_000);
        }
        return r;
    }

    private static Result run(Ctx ctx, String name, JsonObject args, Path cwd, String[] envOut) {
        Tool t = ALL.get(name);
        if (t == null) return new Result("Unknown tool " + name + ". Tools: " + ALL.keySet(), true);
        for (Param p : t.params()) {
            if (p.required() && (!args.has(p.name()) || str(args, p.name()).isBlank())) {
                return new Result("Missing required parameter '" + p.name() + "'", true);
            }
        }
        args.addProperty("_cwd", cwd.toString());
        Scope[] resolved = {null};
        try {
            Scope scope = Scope.resolve(ctx.config, ctx.q.db(), str(args, "env"), cwd);
            resolved[0] = scope;
            envOut[0] = scope.env();
            if (MOD_SCOPED.contains(name)) {
                String primary = t.primary();
                ProjectOverlay.Parsed proj = ProjectOverlay.extract(str(args, primary));
                if (proj.asked()) { // project: uses this folder's build in place of the deployed mod
                    scope = ProjectOverlay.apply(ctx.q, scope, cwd, proj.modId());
                    // alone, it means the project itself, like mod:<its id>
                    args.addProperty(primary, proj.rest().isBlank() ? "mod:" + scope.overlay().modId() : proj.rest());
                }
                ModFilter.Parsed p = ModFilter.extract(str(args, primary));
                if (!p.terms().isEmpty()) {
                    if (!p.onlyMods()) {
                        scope = ModFilter.apply(ctx.q.db(), scope, p.terms());
                        args.addProperty(primary, p.rest());
                    } else if (name.equals("mixins")) {
                        return new Result(scope.banner() + ctx.q.mixinsOfMods(ModFilter.apply(ctx.q.db(), scope, p.terms()), budget(args)), false);
                    } else if (!name.equals("find") && !name.equals("outline")) {
                        return new Result(name + " needs a class, member or pattern next to mod:<id>", true);
                    }
                }
            }
            // history and project builds are never mistaken for the current server
            return new Result(scope.banner() + t.handler().run(ctx, scope, args), false);
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage();
            var gone = java.util.regex.Pattern.compile("No loaded mod matches 'mod:([^']+)'").matcher(msg == null ? "" : msg);
            if (gone.find() && resolved[0] != null) msg += ctx.q.pastMods(resolved[0], gone.group(1)); // a removed mod: say where it was
            return new Result(msg, true);
        } catch (Exception e) {
            return new Result("sevli error: " + e, true);
        }
    }

    public record Result(String text, boolean error) {}

    public static JsonObject schema(Tool t) {
        JsonObject props = new JsonObject();
        com.google.gson.JsonArray required = new com.google.gson.JsonArray();
        for (Param p : t.params()) {
            JsonObject o = new JsonObject();
            o.addProperty("type", p.type());
            o.addProperty("description", p.description());
            props.add(p.name(), o);
            if (p.required()) required.add(p.name());
        }
        JsonObject s = new JsonObject();
        s.addProperty("type", "object");
        s.add("properties", props);
        s.add("required", required);
        return s;
    }

    static String str(JsonObject a, String k) {
        return a.has(k) && !a.get(k).isJsonNull() ? a.get(k).getAsString() : null;
    }

    static int budget(JsonObject a) {
        return a.has("budget") ? a.get("budget").getAsInt() : Out.DEFAULT_BUDGET;
    }

    public static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
