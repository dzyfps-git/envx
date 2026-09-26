package dev.sevli.fabric;

import dev.sevli.mapping.Mappings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The Minecraft/Fabric base layer for one (Minecraft version, Yarn build) pair.
 *
 * <p>Files live in {@code <home>/base/<id>/}. They are downloaded from Mojang and the Fabric maven when the user
 * installs the baseline, hash-checked, and merged and remapped locally ({@link BaseDownload}).
 */
public final class FabricBase {
    public final String minecraft;
    public final String yarn;
    public final Path dir;

    public FabricBase(Path home, String minecraft, String yarn) {
        this.minecraft = minecraft;
        this.yarn = yarn;
        this.dir = home.resolve("base").resolve("minecraft-" + minecraft + "-yarn-" + yarn);
    }

    /** Parses {@code "yarn:1.20.1+build.10"} from the environment definition. */
    public static FabricBase forEnv(Path home, String minecraft, String mappings) {
        if (mappings == null || !mappings.startsWith("yarn:")) {
            throw new IllegalArgumentException("Only yarn mappings are supported in v1, got: " + mappings);
        }
        return new FabricBase(home, minecraft, mappings.substring("yarn:".length()));
    }

    public String id() {
        return "fabric-" + minecraft + "-yarn-" + yarn;
    }

    /** Runtime-named (intermediary) merged jar: what the server actually runs; indexed. */
    public Path intermediaryJar() {
        return dir.resolve("minecraft-merged-intermediary.jar");
    }

    /** Yarn-named merged jar: decompiled on demand for readable sources. */
    public Path namedJar() {
        return dir.resolve("minecraft-merged-named.jar");
    }

    /** tiny v2 with official, intermediary and named namespaces. */
    public Path mappingsFile() {
        return dir.resolve("mappings.tiny");
    }

    /** tiny v2 intermediary->named including Yarn javadoc comments. */
    public Path mappingsWithDocs() {
        return dir.resolve("mappings-base.tiny");
    }

    public Path unpickFile() {
        return dir.resolve("mappings.unpick");
    }

    public boolean isProvisioned() {
        return Files.exists(intermediaryJar()) && Files.exists(namedJar()) && Files.exists(mappingsFile());
    }

    public Mappings loadMappings() throws IOException {
        return Mappings.load(id(), mappingsFile());
    }

    /**
     * Makes the base available by downloading it from Mojang and the Fabric maven (hash-checked, BaseDownload). Only
     * ever called when a user installs this baseline: nothing is downloaded, and no other tool's cache is read, unasked.
     */
    public void provision(java.util.function.Consumer<String> progress) throws IOException {
        if (isProvisioned()) return;
        progress.accept("downloading Minecraft " + minecraft + " and Yarn " + yarn + " from Mojang and the Fabric maven");
        new BaseDownload(this, progress).run();
    }

    private String yarnBuild() {
        // "1.20.1+build.10" -> "build.10"
        int plus = yarn.indexOf('+');
        return plus < 0 ? yarn : yarn.substring(plus + 1);
    }
}
