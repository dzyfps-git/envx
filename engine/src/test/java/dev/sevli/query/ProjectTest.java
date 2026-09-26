package dev.sevli.query;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Project detection and version ranges; no index needed. */
class ProjectTest {
    @Test
    void projectIsFoundFromANestedFolderAndReadsItsBuild(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("build.gradle"), """
                plugins {
                    id 'fabric-loom' version "${loom_version}"
                }
                java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }
                """);
        Files.writeString(dir.resolve("gradle.properties"), "minecraft_version=1.20.1\nyarn_mappings=1.20.1+build.10\nloader_version=0.16.10\nmod_version=0.1.0\nloom_version=1.10.5\n");
        Path res = Files.createDirectories(dir.resolve("src/main/resources"));
        Files.writeString(res.resolve("fabric.mod.json"), """
                {"id":"demo","version":"${version}","depends":{"minecraft":"~1.20.1","fabricloader":">=0.16"},"mixins":["demo.mixins.json"],"suggests":{"other":"2.4"},"breaks":{"bad":"*"}}""");
        Path nested = Files.createDirectories(dir.resolve("src/main/java/dev/demo"));

        Project p = Project.find(nested);
        assertEquals("demo", p.modId());
        assertEquals("0.1.0", p.version()); // ${version} comes from gradle.properties mod_version
        assertEquals("1.10.5", p.build().get("loom"));
        assertEquals("17", p.build().get("java"));
        assertEquals("0.16.10", p.build().get("fabricloader"));
        assertEquals("~1.20.1", p.depends().get("minecraft"));
        assertEquals("demo.mixins.json", p.mixinConfigs().getFirst());
        assertEquals("2.4", p.optional().get("other"));
        assertEquals("*", p.breaks().get("bad"));
        assertNull(p.jar());
        assertNull(Project.find(dir.getParent())); // above the project: none

        dev.sevli.Config config = new dev.sevli.Config();
        assertEquals("demo", Project.find(config, nested).modId());
        config.denyRoots = java.util.List.of(dir.toString());
        assertNull(Project.find(config, nested)); // a denied root is never read
    }

    @Test
    void projectQualifierIsTakenOutOfTheQuery() {
        ProjectOverlay.Parsed p = ProjectOverlay.extract("LivingEntity.damage project: mod:lithium");
        assertTrue(p.asked());
        assertNull(p.modId());
        assertEquals("LivingEntity.damage  mod:lithium".replace("  ", " "), p.rest().replace("  ", " "));
        assertEquals("demo", ProjectOverlay.extract("project:demo").modId());
        assertFalse(ProjectOverlay.extract("myproject:x").asked());
    }

    @Test
    void fabricVersionRanges() {
        assertTrue(ProjectReport.VersionRange.satisfies("0.19.3", ">=0.16.10"));
        assertFalse(ProjectReport.VersionRange.satisfies("0.15.0", ">=0.16.10"));
        assertTrue(ProjectReport.VersionRange.satisfies("1.20.1", "~1.20.1"));
        assertFalse(ProjectReport.VersionRange.satisfies("1.21", "~1.20.1"));
        assertTrue(ProjectReport.VersionRange.satisfies("0.92.11+1.20.1", ">=0.92.11+1.20.1"));
        assertTrue(ProjectReport.VersionRange.satisfies("1.20.1", "1.20.x"));
        assertTrue(ProjectReport.VersionRange.satisfies("17", ">=17"));
        assertTrue(ProjectReport.VersionRange.satisfies("2.0", "<1.0 || >=2.0"));
        assertTrue(ProjectReport.VersionRange.satisfies("anything", "*"));
        assertTrue(ProjectReport.VersionRange.compare("1.0.0-rc1", "1.0.0") < 0);
    }
}
