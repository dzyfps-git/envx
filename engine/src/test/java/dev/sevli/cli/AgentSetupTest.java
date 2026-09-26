package dev.sevli.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentSetupTest {
    private static final String BLOCK = "<!-- sevli:begin (managed by `sevli setup`; edits inside this block are overwritten) -->\n"
            + "## Minecraft environment index (sevli)\n- use sevli\n<!-- sevli:end -->\n";

    @Test
    void tomlTableRangeStopsAtNextTable() {
        String toml = "a = 1\n[mcp_servers.sevli]\nenabled = false\ncommand = 'x'\n[other]\nb = 2\n";
        int[] r = AgentSetup.tableRange(toml, "mcp_servers.sevli");
        assertEquals("[mcp_servers.sevli]\nenabled = false\ncommand = 'x'\n", toml.substring(r[0], r[1]));
        assertNull(AgentSetup.tableRange(toml, "missing"));
        int[] last = AgentSetup.tableRange("[mcp_servers.sevli]\nx = 1", "mcp_servers.sevli");
        assertArrayEquals(new int[]{0, 25}, last);
    }

    @Test
    void aPreRenameCodexTableIsRenamedKeepingItsOnOffState() {
        String ours = "[mcp_servers.envx]\nenabled = false\ncommand = 'java'\nargs = ['-cp', 'lib/*', 'dev.envx.cli.Main', 'mcp']\n";
        String toml = "model = 'x'\n\n" + ours + "\n[other]\nb = 2\n";
        assertEquals(toml.replace("[mcp_servers.envx]", "[mcp_servers.sevli]"), AgentSetup.migrateCodexText(toml));
        // someone else's server that happens to be named envx is left alone
        String foreign = "[mcp_servers.envx]\ncommand = 'npx'\nargs = ['some-envx-tool']\n";
        assertEquals(foreign, AgentSetup.migrateCodexText(foreign));
        // both present: the old table is dropped, the new one kept as it is
        String both = "[mcp_servers.sevli]\nenabled = true\n" + ours + "[other]\n";
        assertEquals("[mcp_servers.sevli]\nenabled = true\n[other]\n", AgentSetup.migrateCodexText(both));
    }

    @Test
    void aPreRenameBlockIsFoundAndRemoved(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("AGENTS.md");
        Files.writeString(f, "# Mine\n\n" + BLOCK.replace("sevli:", "envx:") + "\nmore\n");
        assertTrue(AgentSetup.blockRange(Files.readString(f)) != null);
        AgentSetup.removeBlock(f, new AgentSetup.State());
        assertEquals("# Mine\n\nmore\n", Files.readString(f));
    }

    @Test
    void removingABlockKeepsTheUsersOwnText(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("AGENTS.md");
        Files.writeString(f, "# My rules\nBe careful.\n\n" + BLOCK + "\n## More\ntext\n");
        AgentSetup.removeBlock(f, new AgentSetup.State());
        String left = Files.readString(f);
        assertFalse(left.contains("sevli"));
        assertEquals("# My rules\nBe careful.\n\n## More\ntext\n", left);
    }

    @Test
    void aFileSevliCreatedIsDeletedWhenOnlyTheBlockWasInIt(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("CLAUDE.md");
        Files.writeString(f, BLOCK);
        AgentSetup.State state = new AgentSetup.State();
        state.created.add(f.toString());
        AgentSetup.removeBlock(f, state);
        assertFalse(Files.exists(f));
        assertFalse(state.created.contains(f.toString()));
    }

    @Test
    void aUserFileIsKeptEvenIfOnlyTheBlockWasInIt(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("CLAUDE.md");
        Files.writeString(f, BLOCK);
        AgentSetup.removeBlock(f, new AgentSetup.State());
        assertEquals("", Files.readString(f));
    }

    @Test
    void setupKeepsTheNewestThreeVersions(@TempDir Path app) throws Exception {
        for (String v : new String[]{"0.2.1", "0.9.0", "0.10.0", "0.10.2", "1.0.0"}) Files.createDirectories(app.resolve(v).resolve("lib"));
        Files.writeString(app.resolve("sevli.cmd"), "launcher");
        assertEquals(java.util.List.of("0.9.0", "0.2.1"), AgentSetup.pruneVersions(app, 3));
        for (String v : new String[]{"0.10.0", "0.10.2", "1.0.0"}) assertTrue(Files.isDirectory(app.resolve(v)));
        assertTrue(Files.exists(app.resolve("sevli.cmd")));
    }
}
