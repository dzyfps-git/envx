package dev.envx.cli;

import com.google.gson.JsonObject;
import dev.envx.Config;
import dev.envx.env.AutoSync;
import dev.envx.env.Environments;
import dev.envx.store.Db;
import dev.envx.tools.McpServer;
import dev.envx.tools.Tools;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Command-line entry point. Query commands are the same tools the MCP server exposes. */
public final class Main {
    private static final String USAGE = """
            envx: Minecraft/Fabric environment index for AI agents

            Setup
              envx init [<minecraft> <yarn>]    Minecraft + Yarn base (from the Loom cache, else downloaded and hash-checked)

            Environments
              envx env add <name> <source> [<fallback source>...]   source: folder, \\\\host\\share, or ssh://host/path
              envx env list
              envx env sync <name>              pull mods/configs (read-only) and index what changed
                                                also runs by itself in the background when envx is used (autoSyncHours)
              envx env import <name> <server folder> [--label <version>]   add a past version (e.g. a backup) as history
              envx env history <name>           snapshots, labels, which one is current
              envx env diff <name> [<from> [<to>]]   mod changes between versions (labels or snapshot ids)
              envx link <project dir> <env>      queries from that dir use this env
              envx default <env>

            Queries (identical to the MCP tools)
              envx find <query>                 envx outline <class|mod:id> [--filter x]   (mod:<id> scopes any query)
              envx source <Class.member> [--lines a-b]
              envx refs <Class.member>          envx mixins <Class[.method]>
              envx check_mixins [<project dir>] envx grep <regex> [--scope config,resources,source,logs] [--path x]
              envx env-info [--filter x]        (all accept --env <name>, --budget <chars>)
              envx env-info --filter errors[:text]   server log errors, failed mixins and crashes, grouped
              In a mod project folder, add project: to a query to use its built jar in place of the server's copy
              envx project index [<project dir>]   index the project's built jar (project: does this by itself)

            Agents
              envx mcp                          run the MCP server on stdio
              envx setup [--claude] [--codex] [--project <dir>]...   install this version; register / write agent instructions
              envx agents on|off|status         switch envx on/off in Codex + Claude Code + AGENTS.md/CLAUDE.md (A/B tests)
              envx agents bench-config [<dir>]  the MCP command and instructions a headless ON run uses (changes nothing)
              envx stats                        index size and counts

            Programs
              envx api                          versioned JSON-lines interface on stdin/stdout (docs/api.md); read-only
              envx api --version
            """;

    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, StandardCharsets.UTF_8));
        if (args.length == 0 || args[0].equals("help") || args[0].equals("--help")) {
            System.out.print(USAGE);
            return;
        }
        if (args[0].equals("mcp")) {
            McpServer.run();
            return;
        }
        if (args[0].equals("api")) { // no auto-sync, no call-log text: a program's interface (docs/api.md)
            if (args.length > 1 && args[1].equals("--version")) {
                System.out.println(dev.envx.api.Api.versionLine());
                return;
            }
            System.exit(dev.envx.api.Api.run(Config.load(), System.in, System.out));
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
            case "env" -> {
                return env(config, rest);
            }
            case "link" -> {
                need(rest, 2, "envx link <project dir> <env>");
                Path dir = Path.of(rest.get(0)).toAbsolutePath().normalize();
                if (!config.environments.containsKey(rest.get(1))) throw new IllegalArgumentException("Unknown env " + rest.get(1));
                config.projects.put(dir.toString(), rest.get(1));
                config.save();
                System.out.println("linked " + dir + " -> " + rest.get(1));
                return 0;
            }
            case "default" -> {
                need(rest, 1, "envx default <env>");
                config.defaultEnv = rest.getFirst();
                config.save();
                System.out.println("default env: " + config.defaultEnv);
                return 0;
            }
            case "project" -> {
                // Run by project: queries (ProjectOverlay) so query processes never write; also usable by hand.
                need(rest, 1, "envx project index [<project dir>] [--env <name>]");
                if (!rest.getFirst().equals("index")) throw new IllegalArgumentException("usage: envx project index [<project dir>] [--env <name>]");
                int ei = rest.indexOf("--env");
                String envName = ei > 0 && ei + 1 < rest.size() ? rest.get(ei + 1) : null;
                Path dir = rest.size() > 1 && ei != 1 ? Path.of(rest.get(1)) : Path.of("").toAbsolutePath();
                if (envName == null) envName = config.envFor(dir);
                dev.envx.query.Project p = dev.envx.query.Project.find(config, dir);
                if (p == null || p.jar() == null) throw new IllegalArgumentException("no built Fabric mod project at " + dir);
                try (Db db = Db.open(config.home(), false)) {
                    long id = new Environments(config, db).indexBuild(envName, p.jar(), p.modId(), System.out::println);
                    System.out.println("indexed " + p.jar().getFileName() + " as artifact " + id);
                }
                return 0;
            }
            case "init" -> {
                // The base layer (Minecraft + Yarn) for each configured environment, or for the versions given.
                List<String[]> bases = new ArrayList<>();
                if (!rest.isEmpty()) {
                    need(rest, 2, "envx init [<minecraft> <yarn>]   e.g. envx init 1.20.1 1.20.1+build.10");
                    bases.add(new String[]{rest.get(0), "yarn:" + rest.get(1)});
                } else {
                    for (Config.EnvDef d : config.environments.values()) bases.add(new String[]{d.minecraft, d.mappings});
                    if (bases.isEmpty()) {
                        Config.EnvDef d = new Config.EnvDef();
                        bases.add(new String[]{d.minecraft, d.mappings});
                    }
                }
                for (String[] b : bases.stream().map(Arrays::asList).distinct().map(l -> l.toArray(String[]::new)).toList()) {
                    var base = dev.envx.fabric.FabricBase.forEnv(config.home(), b[0], b[1]);
                    base.provision(Path.of(System.getProperty("user.home"), ".gradle"), System.out::println);
                    System.out.println("base " + base.id() + ": ready (" + base.dir + ")");
                }
                if (config.environments.isEmpty()) {
                    System.out.println("next: envx env add <name> <server folder | \\\\host\\share | ssh://host/path>, then envx env sync <name>,"
                            + " then envx setup --claude --codex");
                }
                return 0;
            }
            case "setup" -> {
                return AgentSetup.setup(config, rest);
            }
            case "agents" -> {
                return AgentSetup.agents(config, rest);
            }
            case "stats" -> {
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
                String tool = cmd.equals("env-info") ? "env" : cmd.replace('-', '_');
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
            String via = System.getProperty("envx.via", "cli"); // bench/check.py marks its calls "bench" in the call log
            Tools.Result r = Tools.call(new Tools.Ctx(config, db, via), toolName, a, Path.of("").toAbsolutePath());
            (r.error() ? System.err : System.out).print(r.text().endsWith("\n") ? r.text() : r.text() + "\n");
            String env = a.has("env") ? a.get("env").getAsString().replaceFirst("@.*", "") : config.envFor(Path.of("").toAbsolutePath());
            AutoSync.maybeStart(config, env); // after answering; starts a background sync only if one is due
            return r.error() ? 1 : 0;
        }
    }

    private static int env(Config config, List<String> rest) throws Exception {
        need(rest, 1, "envx env add|list|sync|import|history|diff ...");
        switch (rest.getFirst()) {
            case "add" -> {
                need(rest, 3, "envx env add <name> <source> [<fallback>...]");
                Config.EnvDef def = new Config.EnvDef();
                def.sources = new ArrayList<>(rest.subList(2, rest.size()));
                config.environments.put(rest.get(1), def);
                if (config.defaultEnv == null) config.defaultEnv = rest.get(1);
                config.save();
                System.out.println("added env " + rest.get(1) + " sources " + def.sources + "; now run: envx env sync " + rest.get(1));
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
                need(rest, 2, "envx env sync <name> [--auto]");
                return sync(config, rest.get(1), rest.contains("--auto"));
            }
            case "import" -> {
                need(rest, 3, "envx env import <name> <server folder> [--label <version>]");
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
                need(rest, 2, "envx env history <name>");
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
                need(rest, 2, "envx env diff <name> [<from> [<to>]]   (labels, snapshot ids or 'current')");
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
            default -> throw new IllegalArgumentException("envx env add|list|sync|import|history|diff");
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
                String logs = dev.envx.env.LogMirror.refresh(config, env, java.time.Duration.ZERO); // runtime evidence, outside snapshots
                if (logs == null) dev.envx.query.LogService.warm(config.home(), env);
                else System.out.println("server logs not copied: " + logs);
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
