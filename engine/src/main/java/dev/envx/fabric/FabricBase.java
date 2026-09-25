package dev.envx.fabric;

import dev.envx.mapping.Mappings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The Minecraft/Fabric base layer for one (Minecraft version, Yarn build) pair.
 *
 * <p>Files live in {@code <home>/base/<id>/}. They are copied from Fabric Loom's Gradle cache when it has them (fast,
 * no network, and a Loom cache cleanup never breaks the index); otherwise they are downloaded from Mojang and the
 * Fabric maven, hash-checked, and merged and remapped locally ({@link BaseDownload}).
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

    /** Makes the base available: from the Loom cache if it has it, else downloaded. */
    /** Gradle's user home, where Loom keeps its cache: {@code GRADLE_USER_HOME}, else {@code ~/.gradle}. */
    public static Path gradleHome() {
        String g = System.getenv("GRADLE_USER_HOME");
        return g != null && !g.isBlank() ? Path.of(g) : Path.of(System.getProperty("user.home"), ".gradle");
    }

    public void provision(Path gradleHome, java.util.function.Consumer<String> progress) throws IOException {
        if (isProvisioned()) return;
        try {
            provisionFromLoomCache(gradleHome).forEach(progress);
        } catch (IOException notInLoom) {
            progress.accept("Minecraft " + minecraft + " / Yarn " + yarn + " is not in a Loom cache; downloading it from Mojang and the Fabric maven");
            new BaseDownload(this, progress).run();
        }
    }

    /** Copies the base files out of the Loom cache. Returns human-readable lines describing what happened. */
    public List<String> provisionFromLoomCache(Path gradleHome) throws IOException {
        List<String> log = new ArrayList<>();
        String yarnKey = "net.fabricmc.yarn." + minecraft.replace('.', '_') + "." + minecraft + "+" + yarnBuild() + "-v2";
        Path loom = gradleHome.resolve("caches").resolve("fabric-loom");
        Path maven = loom.resolve("minecraftMaven").resolve("net").resolve("minecraft");
        String ver = minecraft + "-" + yarnKey;
        List<Path[]> copies = List.of(
                new Path[]{maven.resolve("minecraft-merged-intermediary").resolve(ver).resolve("minecraft-merged-intermediary-" + ver + ".jar"), intermediaryJar()},
                new Path[]{maven.resolve("minecraft-merged").resolve(ver).resolve("minecraft-merged-" + ver + ".jar"), namedJar()},
                new Path[]{loom.resolve(minecraft).resolve(yarnKey).resolve("mappings.tiny"), mappingsFile()},
                new Path[]{loom.resolve(minecraft).resolve(yarnKey).resolve("mappings-base.tiny"), mappingsWithDocs()},
                new Path[]{loom.resolve(minecraft).resolve(yarnKey).resolve("mappings.unpick"), unpickFile()});
        Files.createDirectories(dir);
        List<String> missing = new ArrayList<>();
        for (Path[] c : copies) {
            if (Files.exists(c[1])) {
                log.add("have " + c[1].getFileName());
            } else if (Files.exists(c[0])) {
                Files.copy(c[0], c[1], StandardCopyOption.COPY_ATTRIBUTES);
                log.add("copied " + c[1].getFileName() + " from Loom cache");
            } else {
                missing.add(c[0].toString());
            }
        }
        if (!missing.isEmpty() && !isProvisioned()) {
            throw new IOException("Minecraft " + minecraft + " / Yarn " + yarn + " not found in the Loom cache. "
                    + "Build any Fabric project using these versions once (./gradlew build), then retry. Missing:\n  "
                    + String.join("\n  ", missing));
        }
        missing.forEach(m -> log.add("optional file missing: " + m));
        return log;
    }

    private String yarnBuild() {
        // "1.20.1+build.10" -> "build.10"
        int plus = yarn.indexOf('+');
        return plus < 0 ? yarn : yarn.substring(plus + 1);
    }
}
