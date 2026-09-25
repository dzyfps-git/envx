package dev.envx.catalog;

import dev.envx.Config;
import dev.envx.env.EnvironmentSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a user's own server runs, so envx only indexes servers on a supported baseline (ADR 0013). Read from the
 * server's log (the loader names itself and the Minecraft version on startup), falling back to files only
 * Forge/NeoForge installs have. Reads nothing but those files.
 */
public final class ServerCheck {
    /** loader: fabric, quilt, neoforge, forge or null when unknown; minecraft: version or null. */
    public record Detected(String loader, String minecraft, String evidence) {}

    private static final Pattern FABRIC = Pattern.compile("Loading Minecraft (\\S+) with (Fabric|Quilt) Loader");
    private static final Pattern FML_MC = Pattern.compile("--fml\\.mcVersion,? (\\S+?)[,\\]\\s]");
    private static final Pattern NEOFORGE = Pattern.compile("--fml\\.neoForgeVersion|net\\.neoforged");
    private static final Pattern FORGE = Pattern.compile("--fml\\.forgeVersion|MinecraftForge v");

    private ServerCheck() {}

    public static Detected detect(EnvironmentSource source) throws IOException {
        byte[] log = source.readIfExists("logs/latest.log");
        if (log != null) {
            String head = new String(log, 0, Math.min(log.length, 512 * 1024), StandardCharsets.UTF_8);
            Matcher f = FABRIC.matcher(head);
            if (f.find()) return new Detected(f.group(2).toLowerCase(java.util.Locale.ROOT), f.group(1), "logs/latest.log");
            Matcher mc = FML_MC.matcher(head);
            String version = mc.find() ? mc.group(1) : null;
            if (NEOFORGE.matcher(head).find()) return new Detected("neoforge", version, "logs/latest.log");
            if (FORGE.matcher(head).find()) return new Detected("forge", version, "logs/latest.log");
        }
        if (source.readIfExists("user_jvm_args.txt") != null) return new Detected("forge", null, "user_jvm_args.txt (a Forge/NeoForge install)");
        return new Detected(null, null, null);
    }

    /**
     * Throws with a clear message when the server is known to run something other than {@code def}'s baseline.
     * An undetectable server (no log yet) is allowed; its mods are indexed on the environment's baseline.
     */
    public static void require(EnvironmentSource source, Config.EnvDef def) throws IOException {
        Detected d = detect(source);
        if (d.loader() == null) return;
        String name = switch (d.loader()) {
            case "neoforge" -> "NeoForge";
            case "forge" -> "Forge";
            case "quilt" -> "Quilt";
            default -> "Fabric";
        };
        boolean loaderOk = d.loader().equals(def.platform);
        boolean versionOk = d.minecraft() == null || d.minecraft().equals(def.minecraft);
        if (loaderOk && versionOk) return;
        throw new IllegalArgumentException("This server runs " + (d.minecraft() != null ? "Minecraft " + d.minecraft() + " with " : "") + name
                + " (from " + d.evidence() + "). envx supports " + String.join(", ", Baselines.SUPPORTED.stream().map(Baselines.Baseline::name).toList())
                + " so far; nothing was indexed.");
    }
}
