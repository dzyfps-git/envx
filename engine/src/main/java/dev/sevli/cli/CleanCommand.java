package dev.sevli.cli;

import dev.sevli.Config;
import dev.sevli.env.AutoSync;
import dev.sevli.query.FullDecompile;
import dev.sevli.store.Cleanup;
import dev.sevli.store.Db;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code sevli clean} and {@code sevli remove <name>} (ADR 0014): optional, previewed, confirmed, and only ever
 * Sevli's own data inside the data home. A connected server and its files are never touched.
 */
final class CleanCommand {
    private CleanCommand() {}

    /** {@code sevli clean [<name>@<label>|<name>#<id>...] [--no-check] [--free 1,2|all --yes]} */
    static int clean(Config config, List<String> args) throws Exception {
        if (busy(config)) return 1;
        int fi = args.indexOf("--free");
        String asked = fi >= 0 && fi + 1 < args.size() ? args.get(fi + 1) : null;
        String pick = asked;
        List<String> snapshots = new ArrayList<>(args.stream().filter(a -> !a.startsWith("--") && !a.equals(asked) && (a.contains("@") || a.contains("#"))).toList());
        if (pick != null) { // the app frees by item id; a named snapshot's id carries its name
            for (String t : pick.split(",")) if (t.startsWith("snapshot:")) snapshots.add(t.substring("snapshot:".length()));
        }
        try (Db db = Db.open(config.home(), false)) {
            Cleanup c = new Cleanup(config, db);
            if (!args.contains("--no-check")) System.out.println("checking which jars can be downloaded again (their hashes are looked up on Modrinth)...");
            List<Cleanup.Item> items = c.preview(snapshots, !args.contains("--no-check"));
            if (items.isEmpty()) {
                System.out.println("Nothing to free: everything in " + config.home() + " is used by a server, a kept snapshot, a baseline or a project."
                        + "\nPast versions of a connected server are kept; free one by naming it: sevli clean <name>@<label>");
                return 0;
            }
            System.out.println("Sevli can free (only its own data in " + config.home() + "; your servers and their files are never touched):\n");
            for (int i = 0; i < items.size(); i++) describe(i + 1, items.get(i));
            if (pick == null) {
                if (System.console() == null) {
                    System.out.println("\nRun `sevli clean` in a terminal to choose, or pass --free <numbers|all> --yes.");
                    return 0;
                }
                pick = ask("\nWhich to free? Numbers (e.g. 1,3), 'all', or Enter to cancel: ");
            }
            List<Cleanup.Item> chosen = choose(items, pick);
            if (chosen.isEmpty()) {
                System.out.println("Nothing freed.");
                return 0;
            }
            System.out.println("\nAbout to free:");
            long total = 0;
            for (Cleanup.Item i : chosen) {
                System.out.println("  - " + i.label() + "  " + mb(i.bytes()) + "  (" + recovery(i) + ")");
                total += i.bytes();
            }
            System.out.println("  space freed: about " + mb(total) + " (files go to the Recycle Bin where there is one)");
            if (chosen.stream().anyMatch(Cleanup.Item::historyLoss)) {
                System.out.println("  ! You lose these points in history: their mod lists, configs and diffs; env=<name>@<label> answers for them stop working.");
            }
            if (!args.contains("--yes") && !ask("Type yes to free this (anything else cancels): ").trim().equalsIgnoreCase("yes")) {
                System.out.println("Nothing freed.");
                return 0;
            }
            long freed = c.free(chosen, System.out::println);
            System.out.println("freed about " + mb(freed));
            return 0;
        }
    }

    /** {@code sevli remove <name> [--yes]}: the server's settings and Sevli's copies for it; history and jar data stay. */
    static int remove(Config config, List<String> args) throws Exception {
        String env = args.stream().filter(a -> !a.startsWith("--")).findFirst().orElse(null);
        if (env == null) throw new IllegalArgumentException("usage: sevli remove <name>   (sevli list shows them)");
        Config.EnvDef def = config.environments.get(env);
        if (def == null) throw new IllegalArgumentException("Unknown server " + env + " (sevli list shows them)");
        if (busy(config)) return 1;
        Path own = config.home().resolve("envs").resolve(env);
        List<String> projects = config.projects.entrySet().stream().filter(e -> e.getValue().equals(env)).map(java.util.Map.Entry::getKey).toList();
        System.out.println("Remove " + env + " from Sevli?");
        System.out.println("  removed: its settings" + (projects.isEmpty() ? "" : ", the links of " + String.join(", ", projects))
                + (Files.exists(own) ? ", and Sevli's copies for it in " + own + " (" + mb(sizeOf(own)) + ": current configs, logs, diffs)" : ""));
        System.out.println("  kept: its history and jar data (sevli clean shows them and frees them only if you choose)");
        System.out.println("  not touched: the server itself and every file at " + String.join(", ", def.sources));
        if (!args.contains("--yes")) {
            if (System.console() == null) {
                System.out.println("Run it in a terminal to confirm, or pass --yes.");
                return 0;
            }
            if (!ask("Type yes to remove it (anything else cancels): ").trim().equalsIgnoreCase("yes")) {
                System.out.println("Nothing removed.");
                return 0;
            }
        }
        config.environments.remove(env);
        projects.forEach(config.projects::remove);
        if (env.equals(config.defaultEnv)) config.defaultEnv = null;
        config.save();
        try (Db db = Db.open(config.home(), true)) {
            new Cleanup(config, db).trashEnvFolder(env);
        }
        System.out.println("removed " + env + (projects.isEmpty() ? "" : " and its project links"));
        return 0;
    }

    private static boolean busy(Config config) {
        if (FullDecompile.isRunning(config.home())) {
            System.out.println("a background decompile is running; stop it first (sevli stop), then try again");
            return true;
        }
        for (String env : config.environments.keySet()) {
            if (AutoSync.running(config.home(), env)) {
                System.out.println("a sync of " + env + " is running; try again when it is done");
                return true;
            }
        }
        return false;
    }

    private static void describe(int n, Cleanup.Item i) {
        System.out.printf(Locale.ROOT, "%2d. %s  %s%n", n, i.label(), mb(i.bytes()));
        System.out.println("    why: " + i.why());
        if (i.historyLoss()) System.out.println("    ! history: " + i.note());
        if (!i.jars().isEmpty()) {
            long rebuild = i.jars().stream().filter(j -> j.recovery().equals("rebuildable")).count();
            long lost = i.jars().stream().filter(j -> j.recovery().equals("unrecoverable")).count();
            long unknown = i.jars().stream().filter(j -> j.recovery().equals("not_verified")).count();
            System.out.println("    jar data only these use: " + i.jars().size() + " jars"
                    + (rebuild > 0 ? "; " + rebuild + " can be rebuilt (Modrinth serves the exact files)" : "")
                    + (lost > 0 ? "; " + lost + " cannot be recovered (" + i.jars().stream().filter(j -> j.recovery().equals("unrecoverable"))
                    .map(Cleanup.Jar::name).limit(4).reduce((a, b) -> a + ", " + b).orElse("") + (lost > 4 ? ", ..." : "") + ")" : "")
                    + (unknown > 0 ? "; " + unknown + " recovery not verified (" + i.jars().stream().filter(j -> j.recovery().equals("not_verified"))
                    .map(Cleanup.Jar::note).findFirst().orElse("") + ")" : ""));
        } else if (!i.historyLoss()) {
            System.out.println("    " + i.note());
        }
    }

    private static String recovery(Cleanup.Item i) {
        if (i.historyLoss()) return "history cannot be recovered";
        return switch (i.recovery()) {
            case "rebuildable" -> "can be rebuilt";
            case "not_verified" -> "recovery not verified";
            default -> "cannot be recovered";
        };
    }

    static List<Cleanup.Item> choose(List<Cleanup.Item> items, String pick) {
        if (pick == null || pick.isBlank()) return List.of();
        if (pick.trim().equalsIgnoreCase("all")) return items;
        List<Cleanup.Item> out = new ArrayList<>();
        for (String p : pick.split("[,\\s]+")) {
            if (p.isBlank()) continue;
            var byId = items.stream().filter(i -> i.id().equals(p.trim())).findFirst();
            if (byId.isPresent()) {
                if (!out.contains(byId.get())) out.add(byId.get());
                continue;
            }
            int n;
            try {
                n = Integer.parseInt(p.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("'" + p + "' is not an item number");
            }
            if (n < 1 || n > items.size()) throw new IllegalArgumentException("there is no item " + n);
            if (!out.contains(items.get(n - 1))) out.add(items.get(n - 1));
        }
        return out;
    }

    private static BufferedReader in;

    private static String ask(String prompt) throws IOException {
        System.out.print(prompt);
        System.out.flush();
        if (in == null) in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line = in.readLine();
        return line == null ? "" : line;
    }

    static String mb(long bytes) {
        return bytes >= 1_000_000_000L ? String.format(Locale.ROOT, "%.1f GB", bytes / 1e9)
                : bytes >= 1_000_000 ? (bytes / 1_000_000) + " MB" : bytes >= 1000 ? (bytes / 1000) + " KB" : bytes + " bytes";
    }

    private static long sizeOf(Path p) throws IOException {
        long[] total = {0};
        try (var walk = Files.walk(p)) {
            walk.filter(Files::isRegularFile).forEach(f -> {
                try {
                    total[0] += Files.size(f);
                } catch (IOException ignored) {
                    // vanished
                }
            });
        }
        return total[0];
    }
}
