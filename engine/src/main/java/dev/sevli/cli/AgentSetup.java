package dev.sevli.cli;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sevli.Config;
import dev.sevli.Version;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/**
 * Wires sevli into Claude Code and Codex, and switches it on or off for clean A/B comparisons.
 * All CLI-specific config handling lives here, so format changes in either tool touch one file.
 *
 * <ul>
 *   <li>{@code setup} installs a stable copy of the jars into {@code <home>/app/<version>} (running MCP
 *       servers keep the old copy) and points registrations at it <em>without changing whether each
 *       tool has sevli on or off</em>.</li>
 *   <li>{@code agents off} disables Codex's server ({@code enabled = false}), removes the Claude Code
 *       registration, and removes every sevli instruction block from AGENTS.md <em>and</em> CLAUDE.md.
 *       Codex reads CLAUDE.md too; a block left there once contaminated a baseline run.</li>
 *   <li>{@code agents on} restores all three; {@code agents status} reports each part and any stray block.</li>
 * </ul>
 * Instruction blocks go to linked projects ({@code sevli link}) plus any {@code setup --project} dirs.
 */
public final class AgentSetup {
    private static final String BEGIN = "<!-- sevli:begin (managed by `sevli setup`; edits inside this block are overwritten) -->";
    private static final String BEGIN_PREFIX = "<!-- sevli:begin";
    private static final String END = "<!-- sevli:end -->";
    /**
     * Before 2.0 sevli was called envx: agents registered a server named {@code envx} and projects hold envx blocks.
     * They are recognised as ours (by the {@code dev.envx.} main class) and rewritten under the new name, keeping on/off.
     */
    private static final String LEGACY = "envx";
    private static final String LEGACY_BEGIN_PREFIX = "<!-- envx:begin";
    private static final String LEGACY_END = "<!-- envx:end -->";
    private static final String LEGACY_MAIN = "dev.envx.";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private AgentSetup() {}

    /** Persistent bookkeeping in {@code <home>/agents.json}. */
    static final class State {
        /** Extra instruction targets besides linked projects. */
        Set<String> projects = new TreeSet<>();
        /** Instruction files sevli created itself; deleted again when their only content was the block. */
        Set<String> created = new TreeSet<>();
        /** Whether instruction blocks should currently exist. */
        boolean instructions = true;
    }

    // ---------------------------------------------------------------- commands

    static int setup(Config config, List<String> args) throws IOException, InterruptedException {
        boolean claude = args.contains("--claude");
        boolean codex = args.contains("--codex");
        if (args.contains("--path")) {
            addToPath(config);
            if (!claude && !codex && !args.contains("--project")) return 0;
        }
        List<Path> projects = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) if (args.get(i).equals("--project") && i + 1 < args.size()) projects.add(Path.of(args.get(++i)));
        if (!claude && !codex && projects.isEmpty()) {
            System.out.println("usage: sevli setup [--claude] [--codex] [--project <dir>]...");
            return 2;
        }
        Install inst = install(config.home());
        // Record where the data lives under the new name, so it is found without the pre-2.0 ~/.envx pointer.
        if (Config.fixedHome() == null && Config.isDataHome(config.home())) Config.writePointer(config.home());
        if (Files.exists(config.home().resolve("index.sqlite"))) { // bring the index schema up to date before agents read it
            try (dev.sevli.store.Db db = dev.sevli.store.Db.open(config.home(), false)) {
                System.out.println("index schema up to date");
            } catch (java.sql.SQLException e) {
                throw new IOException("could not update the index schema: " + e.getMessage(), e);
            }
        }
        State state = loadState(config);
        if (claude) { // update an existing registration; do not switch Claude back on while sevli is off
            if (claudeRegistered() || state.instructions) registerClaude(inst);
            else System.out.println("Claude Code: sevli is off, registration not added (use `sevli on`)");
        }
        if (codex) writeCodex(inst, null); // null keeps Codex's current enabled/disabled state
        for (Path p : projects) {
            state.projects.add(p.toAbsolutePath().normalize().toString());
            if (!state.instructions) System.out.println("sevli is off: " + p + " recorded; its block is written by `sevli on`");
        }
        // Existing blocks get this version's text too; while sevli is off there are none, and none are added.
        if (state.instructions) for (Path p : instructionTargets(config, state)) writeInstructions(config, state, p, inst);
        saveState(config, state);
        return 0;
    }

    static int agents(Config config, List<String> args) throws IOException, InterruptedException {
        String sub = args.isEmpty() ? "status" : args.getFirst();
        State state = loadState(config);
        switch (sub) {
            case "off" -> {
                if (codexBlock() != null) writeCodex(null, false);
                if (claudeRegistered()) {
                    try {
                        if (claudeHas("sevli")) exec(List.of(claudeExe(), "mcp", "remove", "--scope", "user", "sevli"), false);
                        if (claudeLegacy()) exec(List.of(claudeExe(), "mcp", "remove", "--scope", "user", LEGACY), false);
                        System.out.println("Claude Code: removed MCP server 'sevli'");
                    } catch (IOException e) { // keep going: the other parts must still be switched off
                        System.out.println("Claude Code: could not remove sevli (" + e.getMessage() + "); run: claude mcp remove --scope user sevli");
                    }
                }
                int removed = 0;
                for (Path f : blockFiles(config, state)) {
                    removeBlock(f, state);
                    removed++;
                }
                System.out.println("instruction blocks removed: " + removed);
                state.instructions = false;
                saveState(config, state);
            }
            case "on" -> {
                Install inst = install(config.home());
                writeCodex(inst, true);
                state.instructions = true;
                for (Path p : instructionTargets(config, state)) writeInstructions(config, state, p, inst);
                saveState(config, state);
                try {
                    registerClaude(inst);
                } catch (IOException e) {
                    System.out.println("Claude Code: could not register sevli (" + e.getMessage() + ")");
                }
            }
            case "status" -> {
                return status(config, state);
            }
            case "bench-config" -> { // for bench/agents.py: the MCP command and instruction block of an ON run, changing nothing
                Path dir = Path.of(args.size() > 1 ? args.get(1) : ".").toAbsolutePath().normalize();
                Install inst = running(config.home());
                com.google.gson.JsonObject o = new com.google.gson.JsonObject();
                com.google.gson.JsonArray cmd = new com.google.gson.JsonArray();
                inst.mcpCommand().forEach(cmd::add);
                o.add("command", cmd);
                o.addProperty("instructions", blockText(config, dir, inst));
                o.addProperty("version", Version.VALUE);
                System.out.println(o);
                return 0;
            }
            default -> {
                System.out.println("usage: sevli on | off, or sevli agents [status]");
                return 2;
            }
        }
        System.out.println("(start new agent sessions for this to take effect)");
        return status(config, state);
    }

    /**
     * Puts the launcher folder ({@code <data home>/app}, which always holds the current version's launcher) on the
     * user's PATH, so `sevli` works in any new terminal. Windows: the user PATH (no admin rights, no system PATH).
     * Elsewhere shell profiles differ, so the line to add is printed instead of editing one.
     */
    static void addToPath(Config config) throws IOException, InterruptedException {
        Path app = config.home().resolve("app").toAbsolutePath();
        if (!WINDOWS) {
            System.out.println("add this line to your shell profile (~/.bashrc, ~/.zshrc):\n  export PATH=\"" + app + ":$PATH\"");
            return;
        }
        // The raw value keeps %VARIABLES% unexpanded (reading it through [Environment] would expand them for good);
        // setting and clearing a user variable afterwards tells Windows to hand new terminals the new PATH.
        String script = "$d = '" + app.toString().replace("'", "''") + "'; "
                + "$k = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Environment', $true); "
                + "$p = [string]$k.GetValue('Path', '', 'DoNotExpandEnvironmentNames'); "
                + "if (($p -split ';') -contains $d) { 'already on PATH' } else { "
                + "$k.SetValue('Path', $(if ($p) { $p.TrimEnd(';') + ';' + $d } else { $d }), 'ExpandString'); "
                + "[Environment]::SetEnvironmentVariable('SEVLI_PATH_REFRESH', '1', 'User'); "
                + "[Environment]::SetEnvironmentVariable('SEVLI_PATH_REFRESH', $null, 'User'); 'added' }";
        String out = exec(List.of("powershell", "-NoProfile", "-NonInteractive", "-Command", script), false).trim();
        System.out.println(app + ": " + out + (out.equals("added") ? " to your user PATH; open a new terminal and type: sevli" : ""));
    }

    /** {@link #summary} for callers outside this package (the app server). */
    /** Each agent's sevli state for the desktop app: Codex null = not registered; instruction files with an sevli block. */
    public record AgentState(boolean claudeOn, Boolean codexOn, int instructionFiles, int projects) {}

    public static AgentState state(Config config) throws IOException {
        State state = loadState(config);
        return new AgentState(claudeRegistered(), codexEnabled(), blockFiles(config, state).size(), instructionTargets(config, state).size());
    }

    public static String summaryLine(Config config) throws IOException {
        return summary(config);
    }

    /** One line for `sevli status`: ON, OFF, or which parts are on. */
    static String summary(Config config) throws IOException {
        Boolean codexOn = codexEnabled();
        boolean claudeOn = claudeRegistered();
        State state = loadState(config);
        boolean blocks = !blockFiles(config, state).isEmpty();
        boolean expectBlocks = !instructionTargets(config, state).isEmpty(); // none before a project is linked
        if (codexOn == null && !claudeOn && !blocks) return "not set up yet: sevli setup";
        if (Boolean.TRUE.equals(codexOn) && claudeOn && (blocks || !expectBlocks)) {
            return "ON for Codex and Claude Code" + (expectBlocks ? "" : "; no mod project linked yet (sevli link <dir> <env> tells agents there to use sevli)");
        }
        if (!Boolean.TRUE.equals(codexOn) && !claudeOn && !blocks) return "OFF (clean baseline)";
        return "MIXED: Codex " + (Boolean.TRUE.equals(codexOn) ? "on" : "off") + ", Claude Code " + (claudeOn ? "on" : "off")
                + ", instruction blocks " + (blocks ? "present" : "none") + " (sevli on | sevli off fixes it)";
    }

    private static int status(Config config, State state) throws IOException {
        Boolean codexOn = codexEnabled();
        boolean claudeOn = claudeRegistered();
        List<Path> blocks = blockFiles(config, state);
        System.out.println("Codex:       " + (codexOn == null ? "not registered" : codexOn ? "ON" : "off (enabled = false)")
                + versionNote(codexBlock()));
        System.out.println("Claude Code: " + (claudeOn ? "ON (user scope)" : "off (not registered)"));
        System.out.println("Instruction blocks (AGENTS.md / CLAUDE.md): " + (blocks.isEmpty() ? "none" : blocks.size()));
        List<Path> targets = instructionTargets(config, state);
        for (Path b : blocks) {
            System.out.println("  " + b + (targets.contains(b.getParent()) ? "" : "  (stray: not a target; `sevli setup --project <dir>` adopts it)"));
        }
        System.out.println("Instruction targets when on: " + (targets.isEmpty() ? "none (sevli link <dir> <env>)" : targets));
        boolean allOn = Boolean.TRUE.equals(codexOn) && claudeOn && !blocks.isEmpty();
        boolean allOff = !Boolean.TRUE.equals(codexOn) && !claudeOn && blocks.isEmpty();
        System.out.println("=> " + (allOn ? "ON" : allOff ? "OFF (clean baseline)" : "MIXED: use `sevli on` or `sevli off`"));
        return 0;
    }

    private static String versionNote(String block) {
        if (block == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("app[\\\\/]([^\\\\/]+)[\\\\/]lib").matcher(block);
        if (!m.find()) return "";
        String v = m.group(1);
        return v.isEmpty() || v.equals(Version.VALUE) ? "" : "  (points at sevli " + v + "; `sevli setup` updates it)";
    }

    // ---------------------------------------------------------------- install

    record Install(Path java, Path libDir, Path launcher) {
        List<String> mcpCommand() {
            return List.of(java.toString(), "-Xss4m", "-XX:+UseSerialGC", "-cp", libDir + File.separator + "*", "dev.sevli.cli.Main", "mcp");
        }
    }

    /** Installed versions kept in {@code app/}: the new one and two before it, for agent sessions still running them. */
    static final int KEPT_VERSIONS = 3;

    /** Deletes all but the newest {@code keep} version folders; one still in use (locked jars) is left for next time. */
    static List<String> pruneVersions(Path app, int keep) throws IOException {
        List<Path> versions;
        try (var s = Files.list(app)) {
            versions = s.filter(p -> Files.isDirectory(p.resolve("lib")) && p.getFileName().toString().matches("\\d+\\.\\d+\\.\\d+.*"))
                    .sorted(java.util.Comparator.comparing((Path p) -> versionKey(p.getFileName().toString())).reversed()).toList();
        }
        List<String> removed = new ArrayList<>();
        for (Path old : versions.subList(Math.min(keep, versions.size()), versions.size())) {
            try (var s = Files.walk(old)) {
                for (Path p : s.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
                removed.add(old.getFileName().toString());
            } catch (IOException inUse) {
                // a running session still has this version's jars open
            }
        }
        if (!removed.isEmpty()) System.out.println("removed old sevli versions: " + String.join(", ", removed) + " (keeps the newest " + keep + ")");
        return removed;
    }

    /** "0.10.2" sorts after "0.9.0". */
    static String versionKey(String v) {
        StringBuilder sb = new StringBuilder();
        for (String part : v.split("[.-]")) sb.append(part.matches("\\d+") ? String.format("%06d", Integer.parseInt(part)) : part).append('.');
        return sb.toString();
    }

    /** The jars this process runs from, without installing anything. */
    private static Install running(Path home) {
        Path sevliJar = null;
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            if (Path.of(entry).getFileName().toString().startsWith("sevli")) sevliJar = Path.of(entry).toAbsolutePath();
        }
        if (sevliJar == null) throw new IllegalStateException("sevli jar not on the class path");
        return new Install(javaExe(), sevliJar.getParent(), launcher(home));
    }

    /**
     * Copies the running jars to {@code <home>/app/<version>/lib}. A new version gets a new folder,
     * so sessions still running the old one are unaffected. Old folders are kept: a running JVM may
     * still open one of their jars lazily.
     */
    private static Install install(Path home) throws IOException {
        Path lib = home.resolve("app").resolve(Version.VALUE).resolve("lib");
        Files.createDirectories(lib);
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            Path src = Path.of(entry);
            if (!Files.isRegularFile(src) || !entry.endsWith(".jar")) continue;
            Path dst = lib.resolve(src.getFileName());
            if (!Files.exists(dst) || Files.size(dst) != Files.size(src) || src.getFileName().toString().startsWith("sevli")) {
                try {
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    System.out.println("could not replace " + dst.getFileName() + " (in use?): " + e.getMessage());
                }
            }
        }
        Path java = javaExe();
        Path launcher = launcher(home);
        if (WINDOWS) {
            Files.writeString(launcher, "@echo off\r\n\"" + java + "\" -Xss4m -cp \"" + lib + "\\*\" dev.sevli.cli.Main %*\r\n");
        } else {
            Files.writeString(launcher, "#!/bin/sh\nexec '" + java + "' -Xss4m -cp '" + lib + "/*' dev.sevli.cli.Main \"$@\"\n");
            launcher.toFile().setExecutable(true);
        }
        // `envx`, the name before 2.0, keeps working for the 2.x line: it runs sevli and says so once (Main).
        Path alias = launcher.resolveSibling(WINDOWS ? "envx.cmd" : "envx");
        if (WINDOWS) {
            Files.writeString(alias, "@echo off\r\n\"" + java + "\" -Xss4m -Dsevli.alias=envx -cp \"" + lib + "\\*\" dev.sevli.cli.Main %*\r\n");
        } else {
            Files.writeString(alias, "#!/bin/sh\nexec '" + java + "' -Xss4m -Dsevli.alias=envx -cp '" + lib + "/*' dev.sevli.cli.Main \"$@\"\n");
            alias.toFile().setExecutable(true);
        }
        System.out.println("installed sevli " + Version.VALUE + " to " + lib.getParent() + " (shell launcher: " + launcher + ")");
        pruneVersions(home.resolve("app"), KEPT_VERSIONS);
        return new Install(java, lib, launcher);
    }

    static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows");

    private static Path javaExe() {
        return Path.of(System.getProperty("java.home"), "bin", WINDOWS ? "java.exe" : "java");
    }

    /** The shell launcher: {@code app/sevli.cmd} on Windows, {@code app/sevli} elsewhere. */
    static Path launcher(Path home) {
        return home.resolve("app").resolve(WINDOWS ? "sevli.cmd" : "sevli");
    }

    // ---------------------------------------------------------------- Claude Code

    private static String claudeExe() {
        Path exe = Path.of(System.getProperty("user.home"), ".local", "bin", WINDOWS ? "claude.exe" : "claude");
        return Files.exists(exe) ? exe.toString() : "claude";
    }

    /** User-scope registration under either name, read from ~/.claude.json (only the mcpServers key is looked at). */
    static boolean claudeRegistered() {
        return claudeHas("sevli") || claudeLegacy();
    }

    /** A pre-2.0 registration named envx that runs our program (another tool could use that name too). */
    private static boolean claudeLegacy() {
        JsonElement e = claudeServer(LEGACY);
        return e != null && e.toString().contains(LEGACY_MAIN);
    }

    private static boolean claudeHas(String name) {
        return claudeServer(name) != null;
    }

    private static JsonElement claudeServer(String name) {
        try {
            Path f = Path.of(System.getProperty("user.home"), ".claude.json");
            if (!Files.exists(f)) return null;
            JsonObject root = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            JsonElement servers = root.get("mcpServers");
            return servers != null && servers.isJsonObject() ? servers.getAsJsonObject().get(name) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static void registerClaude(Install inst) throws IOException, InterruptedException {
        String claude = claudeExe();
        if (claudeLegacy()) {
            exec(List.of(claude, "mcp", "remove", "--scope", "user", LEGACY), false);
            System.out.println("Claude Code: removed the pre-2.0 registration 'envx' (replaced by 'sevli')");
        }
        exec(List.of(claude, "mcp", "remove", "--scope", "user", "sevli"), true);
        List<String> cmd = new ArrayList<>(List.of(claude, "mcp", "add", "--scope", "user", "sevli", "--"));
        cmd.addAll(inst.mcpCommand());
        exec(cmd, false);
        System.out.println("Claude Code: registered MCP server 'sevli' (user scope) -> sevli " + Version.VALUE);
    }

    // ---------------------------------------------------------------- Codex

    private static Path codexConfig() {
        String codexHome = System.getenv("CODEX_HOME");
        if (codexHome != null && !codexHome.isBlank()) return Path.of(codexHome, "config.toml");
        return Path.of(System.getProperty("user.home"), ".codex", "config.toml");
    }

    /** The {@code [mcp_servers.sevli]} table text (or the pre-2.0 {@code [mcp_servers.envx]} one), or null. */
    static String codexBlock() throws IOException {
        Path cfg = codexConfig();
        if (!Files.exists(cfg)) return null;
        String text = Files.readString(cfg);
        int[] r = tableRange(text, "mcp_servers.sevli");
        if (r == null) r = legacyCodexRange(text);
        return r == null ? null : text.substring(r[0], r[1]);
    }

    /** The pre-2.0 {@code [mcp_servers.envx]} table, only when it runs our program. */
    private static int[] legacyCodexRange(String text) {
        int[] r = tableRange(text, "mcp_servers." + LEGACY);
        return r != null && text.substring(r[0], r[1]).contains(LEGACY_MAIN) ? r : null;
    }

    /**
     * Renames a pre-2.0 {@code [mcp_servers.envx]} table to {@code [mcp_servers.sevli]} in place, so its
     * {@code enabled} value is kept exactly (or drops it when a sevli table already exists). Backup first.
     */
    static String migrateCodexText(String text) {
        int[] legacy = legacyCodexRange(text);
        if (legacy == null) return text;
        String header = "[mcp_servers." + LEGACY + "]";
        if (tableRange(text, "mcp_servers.sevli") == null) {
            int h = text.indexOf(header, legacy[0]);
            return text.substring(0, h) + "[mcp_servers.sevli]" + text.substring(h + header.length());
        }
        return text.substring(0, legacy[0]) + text.substring(legacy[1]);
    }

    private static void migrateCodex(Path cfg) throws IOException {
        if (!Files.exists(cfg)) return;
        String text = Files.readString(cfg);
        String updated = migrateCodexText(text);
        if (updated.equals(text)) return;
        // its own backup: the write that follows replaces config.toml.sevli-backup
        Files.copy(cfg, cfg.resolveSibling("config.toml.envx-backup"), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(cfg, updated, StandardCharsets.UTF_8);
        System.out.println("Codex: renamed the pre-2.0 server 'envx' to 'sevli' in " + cfg + " (on/off unchanged; backup: config.toml.envx-backup)");
    }

    /** null = not registered; otherwise the {@code enabled} value (absent means enabled). */
    static Boolean codexEnabled() throws IOException {
        String block = codexBlock();
        if (block == null) return null;
        for (String line : block.split("\n")) {
            String t = line.trim().replace(" ", "");
            if (t.startsWith("enabled=")) return !t.startsWith("enabled=false");
        }
        return true;
    }

    /**
     * Writes the sevli table in place (other tables untouched, backup first). {@code inst == null} keeps
     * the existing command; {@code enabled == null} keeps the existing on/off state.
     */
    private static void writeCodex(Install inst, Boolean enabled) throws IOException {
        Path cfg = codexConfig();
        migrateCodex(cfg);
        String text = Files.exists(cfg) ? Files.readString(cfg) : "";
        int[] range = tableRange(text, "mcp_servers.sevli");
        String old = range == null ? null : text.substring(range[0], range[1]);
        Boolean current = codexEnabled();
        boolean on = enabled != null ? enabled : current == null || current;
        String commandLines;
        if (inst != null) {
            List<String> c = inst.mcpCommand();
            StringBuilder sb = new StringBuilder("command = '").append(c.getFirst()).append("'\nargs = [");
            for (int i = 1; i < c.size(); i++) sb.append(i > 1 ? ", " : "").append('\'').append(c.get(i)).append('\'');
            commandLines = sb.append("]\n").toString();
        } else if (old != null) {
            StringBuilder sb = new StringBuilder();
            for (String line : old.split("\n")) {
                String t = line.trim();
                if (t.startsWith("command") || t.startsWith("args")) sb.append(line).append('\n');
            }
            commandLines = sb.toString();
        } else {
            return; // nothing registered and nothing to register
        }
        String block = "[mcp_servers.sevli]\nenabled = " + on + "\n" + commandLines + "startup_timeout_sec = 30\n";
        if (Files.exists(cfg)) Files.copy(cfg, cfg.resolveSibling("config.toml.sevli-backup"), StandardCopyOption.REPLACE_EXISTING);
        String updated = range == null
                ? text.stripTrailing() + "\n\n" + block
                : text.substring(0, range[0]) + block + (range[1] < text.length() ? "\n" : "") + text.substring(range[1]).replaceFirst("^\\s*\n", "");
        Files.writeString(cfg, updated, StandardCharsets.UTF_8);
        System.out.println("Codex: sevli " + (on ? "enabled" : "disabled") + " in " + cfg + " (backup: config.toml.sevli-backup)");
    }

    /** [start, end) of a TOML table including its header, up to the next table header; null if absent. */
    static int[] tableRange(String toml, String name) {
        int pos = 0;
        int start = -1;
        for (String line : toml.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("[")) {
                if (start >= 0) return new int[]{start, pos};
                if (t.equals("[" + name + "]")) start = pos;
            }
            pos += line.length() + 1;
        }
        return start >= 0 ? new int[]{start, toml.length()} : null;
    }

    // ---------------------------------------------------------------- instruction blocks

    private static List<Path> instructionTargets(Config config, State state) {
        Set<String> dirs = new LinkedHashSet<>(config.projects.keySet());
        dirs.addAll(state.projects);
        List<Path> out = new ArrayList<>();
        for (String d : dirs) if (Files.isDirectory(Path.of(d))) out.add(Path.of(d));
        return out;
    }

    /**
     * Every AGENTS.md/CLAUDE.md containing an sevli block among the targets, their sibling folders and
     * one level below those, so a block left in any nearby project is found. Denied roots are skipped.
     */
    static List<Path> blockFiles(Config config, State state) throws IOException {
        Set<Path> dirs = new LinkedHashSet<>();
        for (Path t : instructionTargets(config, state)) {
            dirs.add(t);
            Path parent = t.getParent();
            if (parent == null) continue;
            for (Path sibling : children(parent)) {
                dirs.add(sibling);
                dirs.addAll(children(sibling));
            }
        }
        for (String c : state.created) dirs.add(Path.of(c).getParent());
        List<Path> out = new ArrayList<>();
        for (Path d : dirs) {
            if (config.isDenied(d)) continue;
            for (String name : List.of("AGENTS.md", "CLAUDE.md")) {
                Path f = d.resolve(name);
                try {
                    if (Files.isRegularFile(f) && blockRange(Files.readString(f)) != null) out.add(f);
                } catch (IOException | RuntimeException ignored) {
                    // unreadable file: not ours to judge
                }
            }
        }
        return out;
    }

    private static List<Path> children(Path dir) {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, Files::isDirectory)) {
            for (Path p : ds) if (!p.getFileName().toString().startsWith(".") && !p.getFileName().toString().startsWith("$")) out.add(p);
        } catch (IOException | RuntimeException ignored) {
            // inaccessible folder
        }
        return out;
    }

    /** The managed instruction block for {@code project}. */
    static String blockText(Config config, Path project, Install inst) {
        String env = config.envFor(project);
        Config.EnvDef def = env == null ? null : config.environments.get(env);
        String envLine = def == null ? "the environment linked with `sevli link`"
                : "the **" + env + "** environment (Minecraft " + def.minecraft + " " + def.platform + ", " + def.mappings.replace("yarn:", "Yarn ") + ")";
        String block = BEGIN + "\n"
                + "## Minecraft environment index (sevli)\n"
                + "This project targets " + envLine + ", indexed locally from the live server's mods.\n"
                + "- For Minecraft/mod classes, mappings, mixins, mod versions and server configs use the `sevli` MCP tools "
                + "(find, outline, source, refs, mixins, check_mixins, grep, env) instead of unzipping jars, running javap, or searching Gradle caches.\n"
                + "- Add `mod:<id>` to a query to search inside specific mods; `source Class.a,b` returns several members at once.\n"
                + "- Add `project:` to a query to see this project's built jar in place of the server's copy (`mixins LivingEntity.tick project:`).\n"
                + "- Without MCP the same queries work in a shell: `" + (WINDOWS ? "& " : "") + "'" + inst.launcher + "' find LivingEntity.tick`.\n"
                + "- After building, run `check_mixins` to validate mixin targets and see other mods injecting at the same methods.\n"
                + "- Server crashes, log errors and failed mixins: `env filter=errors` (add `:<mod or text>` to narrow); after a pack update, "
                + "`env filter=diff:<old version>..` shows what changed for this project.\n"
                + END + "\n";
        return block;
    }

    /** [start, end) of the managed block (a pre-2.0 envx block too), or null. */
    static int[] blockRange(String text) {
        for (String[] m : new String[][]{{BEGIN_PREFIX, END}, {LEGACY_BEGIN_PREFIX, LEGACY_END}}) {
            int b = text.indexOf(m[0]);
            int e = text.indexOf(m[1]);
            if (b >= 0 && e > b) return new int[]{b, e + m[1].length()};
        }
        return null;
    }

    private static void writeInstructions(Config config, State state, Path project, Install inst) throws IOException {
        String block = blockText(config, project, inst);
        for (String file : List.of("CLAUDE.md", "AGENTS.md")) {
            Path f = project.resolve(file);
            boolean existed = Files.exists(f);
            String existing = existed ? Files.readString(f) : "";
            int[] r = blockRange(existing);
            String updated = r != null
                    ? existing.substring(0, r[0]) + block + existing.substring(r[1]).replaceFirst("^\r?\n", "")
                    : (existing.isEmpty() ? "" : existing.stripTrailing() + "\n\n") + block;
            Files.writeString(f, updated);
            if (!existed) state.created.add(f.toString());
            System.out.println("wrote sevli block to " + f);
        }
    }

    /** Removes the sevli block; deletes the file if sevli created it and nothing else is left. */
    static void removeBlock(Path f, State state) throws IOException {
        String text = Files.readString(f);
        int[] r = blockRange(text);
        if (r == null) return;
        String rest = (text.substring(0, r[0]).stripTrailing() + "\n\n" + text.substring(r[1]).stripLeading()).strip();
        if (rest.isEmpty() && state.created.contains(f.toString())) {
            Files.delete(f);
            state.created.remove(f.toString());
            System.out.println("removed " + f + " (created by sevli)");
        } else {
            Files.writeString(f, rest.isEmpty() ? "" : rest + "\n");
            System.out.println("removed sevli block from " + f);
        }
    }

    // ---------------------------------------------------------------- state

    static State loadState(Config config) throws IOException {
        Path f = config.home().resolve("agents.json");
        if (!Files.exists(f)) return new State();
        State s = GSON.fromJson(Files.readString(f), State.class);
        if (s.projects == null) s.projects = new TreeSet<>();
        if (s.created == null) s.created = new TreeSet<>();
        return s;
    }

    static void saveState(Config config, State state) throws IOException {
        Files.writeString(config.home().resolve("agents.json"), GSON.toJson(state));
    }

    /** Runs {@code cmd} and returns its output. */
    private static String exec(List<String> cmd, boolean ignoreFailure) throws IOException, InterruptedException {
        Process p;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        } catch (IOException e) {
            if (ignoreFailure) return "";
            throw e;
        }
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(60, TimeUnit.SECONDS)) p.destroyForcibly();
        if (p.exitValue() != 0 && !ignoreFailure) throw new IOException(String.join(" ", cmd.subList(0, 3)) + " failed: " + out.trim());
        return out;
    }
}
