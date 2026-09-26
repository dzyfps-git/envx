package dev.sevli.catalog;

import dev.sevli.Config;
import dev.sevli.env.Environments;
import dev.sevli.env.EnvironmentSource;
import dev.sevli.store.Db;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The catalog, supported baselines and the own-server gate (ADR 0013), without network. */
class CatalogTest {
    @TempDir
    Path tmp;

    private Path server(String log) throws Exception {
        Path s = tmp.resolve("server-" + Math.abs(log == null ? 0 : log.hashCode()));
        Files.createDirectories(s.resolve("mods"));
        if (log != null) {
            Files.createDirectories(s.resolve("logs"));
            Files.writeString(s.resolve("logs/latest.log"), log);
        }
        return s;
    }

    private EnvironmentSource source(String log) throws Exception {
        return EnvironmentSource.of(server(log).toString());
    }

    @Test
    void ownServersMustRunASupportedBaseline() throws Exception {
        Config.EnvDef def = new Config.EnvDef();
        EnvironmentSource fabric = source("[main/INFO]: Loading Minecraft 1.20.1 with Fabric Loader 0.16.10\n");
        assertDoesNotThrow(() -> ServerCheck.require(fabric, def));
        EnvironmentSource noLog = source(null);
        assertDoesNotThrow(() -> ServerCheck.require(noLog, def), "no log yet: allowed");
        EnvironmentSource neoforge = source("ModLauncher running: args [--launchTarget, forgeserver, --fml.neoForgeVersion, 21.1.77, --fml.mcVersion, 1.21.1, --fml.neoFormVersion]\n");
        var neo = assertThrows(IllegalArgumentException.class, () -> ServerCheck.require(neoforge, def));
        assertTrue(neo.getMessage().startsWith("This server runs Minecraft 1.21.1 with NeoForge"), neo.getMessage());
        assertTrue(neo.getMessage().contains("nothing was indexed"), neo.getMessage());
        EnvironmentSource newerFabric = source("Loading Minecraft 1.21.1 with Fabric Loader 0.16.10\n");
        var newer = assertThrows(IllegalArgumentException.class, () -> ServerCheck.require(newerFabric, def));
        assertTrue(newer.getMessage().contains("Minecraft 1.21.1 with Fabric"), newer.getMessage());
    }

    @Test
    void nothingIsDownloadedUnasked() throws Exception {
        System.setProperty("sevli.home", tmp.resolve("home").toString());
        Config config;
        try {
            config = Config.load();
        } finally {
            System.clearProperty("sevli.home");
        }
        assertFalse(Files.exists(config.home()), "loading the config creates nothing");
        Config.EnvDef def = new Config.EnvDef();
        def.sources.add(server("Loading Minecraft 1.20.1 with Fabric Loader 0.16.10\n").toString());
        config.environments.put("mine", def);
        config.save();
        try (Db db = Db.open(config.home(), false)) {
            var e = assertThrows(IllegalStateException.class, () -> new Environments(config, db).sync("mine", m -> {}));
            assertTrue(e.getMessage().contains("sevli add fabric-1.20.1"), e.getMessage());
        }
        assertFalse(Files.exists(config.home().resolve("base")), "the baseline was not fetched on its own");
    }

    @Test
    void theCatalogListsBaselinesAndPacks() throws Exception {
        Path index = tmp.resolve("catalog/index.json");
        Files.createDirectories(index.getParent());
        Files.writeString(index, """
                {"schema": 1,
                 "baselines": [{"id": "fabric-1.20.1", "name": "Minecraft 1.20.1 · Fabric", "downloadBytes": 75000000, "diskBytes": 150000000},
                               {"id": "neoforge-1.21.1", "name": "Minecraft 1.21.1 · NeoForge"}],
                 "packs": [{"id": "example-pack", "name": "Example Pack", "baseline": "fabric-1.20.1", "source": "modrinth",
                            "versions": [{"version": "1.0", "recipe": "recipes/example-pack/1.0.json", "mods": 120,
                                          "downloadBytes": 400000000, "diskBytes": 2500000000}]}]}
                """);
        Catalog c = Catalog.load(index.toUri().toString());
        assertEquals("1.0", c.packs.getFirst().latest().version());
        assertEquals(index.getParent().resolve("recipes/example-pack/1.0.json"),
                Path.of(java.net.URI.create(Catalog.resolve(index.toUri().toString(), "recipes/example-pack/1.0.json"))));

        System.setProperty("sevli.home", tmp.resolve("home2").toString());
        Config config;
        try {
            config = Config.load();
        } finally {
            System.clearProperty("sevli.home");
        }
        config.catalogUrl = index.toUri().toString();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CatalogCommand.list(config, new PrintStream(out, true, StandardCharsets.UTF_8));
        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("fabric-1.20.1  Minecraft 1.20.1 · Fabric (Yarn build.10)  download 75 MB, uses about 150 MB"), text);
        assertTrue(text.contains("neoforge-1.21.1  Minecraft 1.21.1 · NeoForge  needs a newer sevli"), text);
        assertTrue(text.contains("example-pack  Example Pack 1.0 (120 mods, fabric-1.20.1)  download 400 MB, uses about 2.5 GB"), text);
        assertFalse(Files.exists(config.home()), "listing the catalog creates nothing");
    }
}
