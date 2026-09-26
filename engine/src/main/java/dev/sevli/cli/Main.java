package dev.sevli.cli;

import com.google.gson.JsonObject;
import dev.sevli.Config;
import dev.sevli.env.AutoSync;
import dev.sevli.env.Environments;
import dev.sevli.store.Db;
import dev.sevli.tools.McpServer;
import dev.sevli.tools.Tools;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Command-line entry point. Query commands are the same tools the MCP server exposes. */
public final class Main {
    // Commands are `sevli <verb> [thing]`, one per task. The forms from before 2.0 (env sync, catalog install, agents on,
    // traces, init, env-info, check_mixins, decompile --stop, setup --path) still work but are not listed.
    private static final String USAGE = """
            sevli: Minecraft/Fabric environment index for AI agents

            Everyday
              sevli                            agents on/off, how fresh each server is, background work
              sevli on | off                   switch Sevli on or off for Codex and Claude Code (new sessions)
              sevli sync [<name>]              pull a server's mods and configs now (also runs by itself when Sevli is used)
              sevli stop                       stop background work (the next sync continues it)

            Set up
              sevli setup [--claude|--codex]   register with your agents and put sevli on your PATH (a flag: only that agent)
              sevli browse                     supported baselines and packs, with sizes
              sevli add <id>                   download and index one (from the official sources, hash-checked)
              sevli connect <name> <source>    your own server on a supported baseline, read-only; syncs it once
                                               (a folder, \\\\host\\share, or ssh://host/path; more sources = fallbacks)
              sevli link <dir> [<name>]        agents working in that project use this server
              sevli default <name>             the server used outside linked projects

            Servers
              sevli list                       your servers and linked projects
              sevli history <name>             its versions; sevli diff <name> [<from> [<to>]] shows what changed
              sevli insights [--days 30]       what agents worked out by hand that Sevli could have answered

            Look things up (the agents' tools; all take --env <name> and --budget <chars>)
              sevli find <query>               sevli outline <class|mod:id>
              sevli source <Class.member>      sevli refs <Class.member>      sevli mixins <Class[.method]>
              sevli grep <regex> [--scope config,resources,source,logs] [--path x]
              sevli info [--filter errors]     sevli check [<project dir>]
              In a mod project, add project: to a query to use its built jar in place of the server's copy.

            sevli help advanced                history imports, decompiling, agent details, program interfaces
            """;

    private static final String ADVANCED = """
            Advanced
              sevli import <name> <server folder> [--label <version>]   add a past version (e.g. a backup) as history
              sevli decompile [<name>] [--status]   decompile every loaded jar now (normally starts after a sync)
              sevli project index [<dir>]      index a project's built jar (project: in a query does this by itself)
              sevli agents [status]            each agent's parts (registration, instruction files) in detail
              sevli agents bench-config [<dir>]   the MCP command and instructions a headless ON run uses (changes nothing)
              sevli stats                      index size and counts
              sevli mcp                        the MCP server on stdio (what agents run)
              sevli api [--version]            versioned JSON-lines interface for programs (docs/api.md); read-only
              sevli app-server                 the desktop app's connection
            Commands from before 2.0 (envx ..., env sync, catalog install, agents on, traces, ...) still work.
            """;

    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, StandardCharsets.UTF_8));
        if (args.length == 0) args = new String[]{"status"}; // the everyday screen; `sevli help` lists every command
        if ("envx".equals(System.getProperty("sevli.alias")) && !List.of("mcp", "api", "app-server").contains(args[0])) {
            System.err.println("envx is now sevli: the same command works as `sevli " + String.join(" ", args) + "`");
        }
        if (args[0].equals("help") || args[0].equals("--help")) {
            System.out.print(args.length > 1 && args[1].equals("advanced") ? ADVANCED : USAGE);
            return;
        }
        if (args[0].equals("mcp")) {
            McpServer.run();
            return;
        }
        if (args[0].equals("app-server")) { // the desktop app's connection (ADR 0013); reads stdin until the app closes it
            System.exit(dev.sevli.app.AppServer.run(System.in, System.out));
        }
        if (args[0].equals("api")) { // no auto-sync, no call-log text: a program's interface (docs/api.md)
            if (args.length > 1 && args[1].equals("--version")) {
                System.out.println(dev.sevli.api.Api.versionLine());
                return;
            }
            System.exit(dev.sevli.api.Api.run(Config.load(), System.in, System.out));
        }
        Config config = Config.load();
        try {
            int code = dispatch(config, args);
            System.exit(code);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(2);
        }
    }

    private static int dispatch(Config config, String[] args) throws Exception {
        String cmd = args[0];
        List<String> rest = new ArrayList<>(Arrays.asList(args).subList(1, args.length));
        switch (cmd) {
            case "status" -> {
                return Status.print(config);
            }
            case "on", "off" -> {
                return AgentSetup.agents(config, List.of(cmd));
            }
            case "sync" -> {
                String env = rest.isEmpty() ? config.envFor(Path.of("").toAbsolutePath()) : rest.getFirst();
                if (env == null) throw new IllegalArgumentException("usage: sevli sync <name> (no default environment; sevli default <name> sets one)");
                return sync(config, env, false);
            }
            case "insights", "traces" -> {
                int days = rest.contains("--days") ? Integer.parseInt(rest.get(rest.indexOf("--days") + 1)) : 30;
                System.out.print(dev.sevli.traces.TraceReport.run(config, days));
                return 0;
            }
            case "stop" -> {
                System.out.println(dev.sevli.query.FullDecompile.stop(config));
                return 0;
            }
            case "env" -> { // before 2.0: env add|list|sync|import|history|diff
                return env(config, rest);
            }
            case "browse" -> {
                return dev.sevli.catalog.CatalogCommand.run(config, List.of("list"), System.out);
            }
            case "add" -> {
                need(rest, 1, "sevli add <id>   (ids: sevli browse)");
                return dev.sevli.catalog.CatalogCommand.run(config, List.of("install", rest.getFirst()), System.out);
            }
            case "connect" -> {
                need(rest, 2, "sevli connect <name> <folder|\\\\host\\share|ssh://host/path> [<fallback>...]");
                List<String> add = new ArrayList<>(List.of("add"));
                add.addAll(rest);
                env(config, add);
                return sync(config, rest.getFirst(), false);
            }
            case "list", "history", "diff", "import" -> {
                List<String> sub = new ArrayList<>(List.of(cmd));
                sub.addAll(rest);
                return env(config, sub);
            }
            case "info" -> {
                return query(config, "env", rest);
            }
            case "check" -> {
                return query(config, "check_mixins", rest);
            }
            case "decompile" -> {
                if (rest.contains("--stop")) {
                    System.out.println(dev.sevli.query.FullDecompile.stop(config));
                    return 0;
                }
                String env = rest.stream().filter(x -> !x.startsWith("--")).findFirst().orElse(config.envFor(Path.of("").toAbsolutePath()));
                if (env == null) throw new IllegalArgumentException("usage: sevli decompile [<env>] [--status|--stop]");
                if (rest.contains("--status")) {
                    System.out.println(dev.sevli.query.FullDecompile.status(config, env));
                    return 0;
                }
                return dev.sevli.query.FullDecompile.run(config, env);
            }
            case "link" -> {
                need(rest, 1, "sevli link <project dir> [<name>]");
                Path dir = Path.of(rest.get(0)).toAbsolutePath().normalize();
                String env = rest.size() > 1 ? rest.get(1) : config.envFor(null);
                if (env == null) throw new IllegalArgumentException("usage: sevli link <project dir> <name> (no default server; sevli list shows them)");
                if (!config.environments.containsKey(env)) throw new IllegalArgumentException("Unknown server " + env + " (sevli list shows them)");
                config.projects.put(dir.toString(), env);
                config.save();
                System.out.println("linked " + dir + " -> " + env);
                return 0;
            }
            case "default" -> {
                need(rest, 1, "sevli default <env>");
                config.defaultEnv = rest.getFirst();
                config.save();
                System.out.println("default env: " + config.defaultEnv);
                return 0;
            }
            case "project" -> {
                // Run by project: queries (ProjectOverlay) so query processes never write; also usable by hand.
                need(rest, 1, "sevli project index [<project dir>] [--env <name>]");
                if (!rest.getFirst().equals("index")) throw new IllegalArgumentException("usage: sevli project index [<project dir>] [--env <name>]");
                int ei = rest.indexOf("--env");
                String envName = ei > 0 && ei + 1 < rest.size() ? rest.get(ei + 1) : null;
                Path dir = rest.size() > 1 && ei != 1 ? Path.of(rest.get(1)) : Path.of("").toAbsolutePath();
                if (envName == null) envName = config.envFor(dir);
                dev.sevli.query.Project p = dev.sevli.query.Project.find(config, dir);
                if (p == null || p.jar() == null) throw new IllegalArgumentException("no built Fabric mod project at " + dir);
                try (Db db = Db.open(config.home(), false)) {
                    long id = new Environments(config, db).indexBuild(envName, p.jar(), p.modId(), System.out::println);
                    System.out.println("indexed " + p.jar().getFileName() + " as artifact " + id);
                }
                return 0;
            }
            case "init" -> { // the default baseline; kept for scripts written before the catalog
                return dev.sevli.catalog.CatalogCommand.run(config, List.of("install", dev.sevli.catalog.Baselines.SUPPORTED.getFirst().id()), System.out);
            }
            case "catalog" -> {
                return dev.sevli.catalog.CatalogCommand.run(config, rest, System.out);
            }
            case "setup" -> { // no flags: every agent on this PC, and the PATH
                List<String> a = new ArrayList<>(rest);
                if (!a.contains("--claude") && !a.contains("--codex") && !a.contains("--project") && !a.contains("--path")) {
                    if (dev.sevli.app.Agents.claudeInstalled()) a.add("--claude");
                    if (dev.sevli.app.Agents.codexInstalled()) a.add("--codex");
                    a.add("--path");
                }
                return AgentSetup.setup(config, a);
            }
            case "agents" -> {
                return AgentSetup.agents(config, rest);
            }
            case "stats" -> {
                if (!Files.exists(config.home().resolve("index.sqlite"))) {
                    System.out.println("home: " + config.home() + "\nno index yet: sevli sync <name> creates it");
                    return 0;
                }
                try (Db db = Db.open(config.home(), true)) {
                    System.out.println("home: " + config.home());
                    long size = Files.size(config.home().resolve("index.sqlite"));
                    System.out.printf("index.sqlite: %.1f MB%n", size / 1e6);
                    for (String t : List.of("artifact", "class", "member", "class_ref", "mixin", "resource", "snapshot")) {
                        System.out.println(t + ": " + db.queryInt("SELECT count(*) FROM " + t));
                    }
                }
                return 0;
            }
            default -> {
                String tool = cmd.equals("env-info") ? "env" : cmd.replace('-', '_'); // env, check_mixins: pre-2.0 names
                if (!Tools.ALL.containsKey(tool)) {
                    System.err.println("Unknown command: " + cmd + "\n");
                    System.err.print(USAGE);
                    return 2;
                }
                return query(config, tool, rest);
            }
        }
    }

    private static int query(Config config, String toolName, List<String> rest) throws Exception {
        Tools.Tool tool = Tools.ALL.get(toolName);
        JsonObject a = new JsonObject();
        List<String> positional = new ArrayList<>();
        for (int i = 0; i < rest.size(); i++) {
            String s = rest.get(i);
            if (s.startsWith("--") && i + 1 < rest.size()) {
                String key = s.substring(2);
                String v = rest.get(++i);
                if (key.equals("budget")) a.addProperty(key, Integer.parseInt(v));
                else if (v.equals("true") || v.equals("false")) a.addProperty(key, Boolean.parseBoolean(v));
                else a.addProperty(key, v);
            } else {
                positional.add(s);
            }
        }
        if (!positional.isEmpty() && tool.primary() != null) a.addProperty(tool.primary(), String.join(" ", positional));
        try (Db db = Db.open(config.home(), true)) {
            String via = System.getProperty("sevli.via", "cli"); // bench/check.py marks its calls "bench" in the call log
            Tools.Result r = Tools.call(new Tools.Ctx(config, db, via), toolName, a, Path.of("").toAbsolutePath());
            (r.error() ? System.err : System.out).print(r.text().endsWith("\n") ? r.text() : r.text() + "\n");
            String env = a.has("env") ? a.get("env").getAsString().replaceFirst("@.*", "") : config.envFor(Path.of("").toAbsolutePath());
            AutoSync.maybeStart(config, env); // after answering; starts a background sync only if one is due
            return r.error() ? 1 : 0;
        }
    }

    private static int env(Config config, List<String> rest) throws Exception {
        need(rest, 1, "sevli env add|list|sync|import|history|diff ...");
        switch (rest.getFirst()) {
            case "add" -> {
                need(rest, 3, "sevli connect <name> <source> [<fallback>...]");
                Config.EnvDef def = new Config.EnvDef();
                def.sources = new ArrayList<>(rest.subList(2, rest.size()));
                config.environments.put(rest.get(1), def);
                if (config.defaultEnv == null) config.defaultEnv = rest.get(1);
                config.save();
                System.out.println("added env " + rest.get(1) + " sources " + def.sources + "; now run: sevli sync " + rest.get(1));
                return 0;
            }
            case "list" -> {
                try (Db db = Db.open(config.home(), false)) {
                    var envs = new Environments(config, db).latestSnapshots();
                    config.environments.forEach((name, def) -> System.out.println(name + (name.equals(config.defaultEnv) ? " (default)" : "")
                            + "  sources=" + def.sources + "  " + def.mappings + "  last snapshot=" + envs.get(name)));
                    config.projects.forEach((dir, env) -> System.out.println("  project " + dir + " -> " + env));
                }
                return 0;
            }
            case "sync" -> {
                need(rest, 2, "sevli sync <name> [--auto]");
                return sync(config, rest.get(1), rest.contains("--auto"));
            }
            case "import" -> {
                need(rest, 3, "sevli import <name> <server folder> [--label <version>]");
                int li = rest.indexOf("--label");
                String label = li > 0 && li + 1 < rest.size() ? rest.get(li + 1) : null;
                if (!AutoSync.acquire(config.home(), rest.get(1))) throw new IllegalStateException("a sync of " + rest.get(1) + " is running; try again shortly");
                long t0 = System.nanoTime();
                try (Db db = Db.open(config.home(), false)) {
                    Environments.SyncResult r = new Environments(config, db).importFolder(rest.get(1), Path.of(rest.get(2)), label, System.err::println);
                    System.out.print(r.summary());
                } finally {
                    AutoSync.release(config.home(), rest.get(1));
                }
                System.out.printf("import took %.1f s%n", (System.nanoTime() - t0) / 1e9);
                return 0;
            }
            case "history" -> {
                need(rest, 2, "sevli history <name>");
                try (Db db = Db.open(config.home(), true)) {
                    Long current = new Environments(config, db).latestSnapshot(rest.get(1));
                    for (Object[] r : db.query("""
                            SELECT s.id, s.kind, coalesce(s.label, '-'), coalesce(s.pack_name, s.pack, '-'), s.taken_at, coalesce(s.checked_at, s.taken_at),
                                   (SELECT count(*) FROM snapshot_file f WHERE f.snapshot_id=s.id), s.source
                            FROM snapshot s WHERE s.env=? ORDER BY s.id""",
                            rs -> new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getInt(7), rs.getString(8)},
                            rest.get(1))) {
                        System.out.printf("%s%4d  %-6s %-18s %-30s taken %s  checked %s  %d jars  %s%n", r[0].equals(current) ? "*" : " ",
                                r[0], r[1], r[2], r[3], ((String) r[4]).substring(0, 16), ((String) r[5]).substring(0, 16), r[6], r[7]);
                    }
                    System.out.println("* = current (what queries use by default); ask for a past one with env=" + rest.get(1) + "@<label>");
                }
                return 0;
            }
            case "diff" -> {
                need(rest, 2, "sevli diff <name> [<from> [<to>]]   (labels, snapshot ids or 'current')");
                try (Db db = Db.open(config.home(), true)) {
                    Environments envs = new Environments(config, db);
                    if (rest.size() >= 3) {
                        String to = rest.size() >= 4 ? rest.get(3) : "current";
                        System.out.print(envs.diff(envs.resolve(rest.get(1), rest.get(2)), envs.resolve(rest.get(1), to)));
                    } else {
                        System.out.print(envs.diffWithPrevious(rest.get(1), envs.resolve(rest.get(1), "current")));
                    }
                }
                return 0;
            }
            default -> throw new IllegalArgumentException("sevli env add|list|sync|import|history|diff");
        }
    }

    /**
     * One sync at a time per environment (lock file). With --auto (started in the background by
     * {@link AutoSync}) a held lock or an unreachable source is not an error: the attempt and any
     * failure are recorded so `env` can say why the snapshot is older than expected.
     */
    private static int sync(Config config, String env, boolean auto) throws Exception {
        if (!AutoSync.acquire(config.home(), env)) {
            if (auto) return 0;
            throw new IllegalStateException("a sync of " + env + " is already running (" + config.home().resolve("envs").resolve(env).resolve("sync.lock") + ")");
        }
        long t0 = System.nanoTime();
        try (Db db = Db.open(config.home(), false)) {
            if (auto) {
                System.out.println(java.time.Instant.now() + " auto-sync " + env);
                db.setMeta("autosync." + env + ".attempted_at", java.time.Instant.now().toString());
            }
            try {
                Environments.SyncResult r = new Environments(config, db).sync(env, auto ? System.out::println : System.err::println);
                System.out.print(r.summary());
                System.out.print(r.diff());
                db.update("DELETE FROM meta WHERE key=?", "autosync." + env + ".error");
                String logs = dev.sevli.env.LogMirror.refresh(config, env, java.time.Duration.ZERO); // runtime evidence, outside snapshots
                if (logs == null) dev.sevli.query.LogService.warm(config.home(), env);
                else System.out.println("server logs not copied: " + logs);
                String decompile = dev.sevli.query.FullDecompile.maybeStart(config, env); // separate process, idle priority
                if (decompile != null) System.out.println(decompile);
            } catch (Exception e) {
                if (!auto) throw e;
                db.setMeta("autosync." + env + ".error", "last attempt " + java.time.Instant.now().toString().substring(0, 16) + "Z failed (" + e.getMessage() + ")");
                System.out.println("auto-sync failed: " + e);
                return 0;
            }
        } finally {
            AutoSync.release(config.home(), env);
        }
        System.out.printf("sync took %.1f s%n", (System.nanoTime() - t0) / 1e9);
        return 0;
    }

    private static void need(List<String> args, int n, String usage) {
        if (args.size() < n) throw new IllegalArgumentException("usage: " + usage);
    }
}
