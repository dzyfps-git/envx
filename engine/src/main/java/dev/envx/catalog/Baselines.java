package dev.envx.catalog;

import dev.envx.Config;
import dev.envx.fabric.FabricBase;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The baselines (Minecraft version + loader + mappings) this version of envx can index. The published catalog lists
 * baselines too; one this build does not know is shown as needing a newer envx. A baseline is only downloaded when a
 * user installs it (ADR 0013): nothing is fetched, and no other tool's cache is read, unasked.
 */
public final class Baselines {
    /** One supported baseline. Sizes are estimates shown before a download. */
    public record Baseline(String id, String name, String minecraft, String loader, String mappings, long downloadBytes, long diskBytes) {
        public FabricBase base(Path home) {
            return FabricBase.forEnv(home, minecraft, mappings);
        }

        public boolean installed(Path home) {
            return base(home).isProvisioned();
        }
    }

    public static final List<Baseline> SUPPORTED = List.of(
            new Baseline("fabric-1.20.1", "Minecraft 1.20.1 · Fabric (Yarn build.10)", "1.20.1", "fabric", "yarn:1.20.1+build.10",
                    75_000_000L, 150_000_000L));

    private Baselines() {}

    public static Optional<Baseline> byId(String id) {
        return SUPPORTED.stream().filter(b -> b.id().equals(id)).findFirst();
    }

    /** The baseline an environment definition needs, if this envx supports it. */
    public static Optional<Baseline> of(Config.EnvDef def) {
        return SUPPORTED.stream().filter(b -> b.minecraft().equals(def.minecraft) && b.mappings().equals(def.mappings)
                && b.loader().equals(def.platform)).findFirst();
    }

    /** "needs baseline X: install it first" for an environment whose baseline is missing or unsupported. */
    public static String missing(Config.EnvDef def) {
        return of(def).map(b -> "needs the baseline " + b.name() + ", which is not installed: install it in the app, or run envx catalog install " + b.id())
                .orElse("Minecraft " + def.minecraft + " with " + def.platform + " (" + def.mappings + ") is not a supported baseline; supported: "
                        + String.join(", ", SUPPORTED.stream().map(Baseline::name).toList()));
    }
}
