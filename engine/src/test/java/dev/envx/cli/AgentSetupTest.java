package dev.envx.cli;

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
    private static final String BLOCK = "<!-- envx:begin (managed by `envx setup`; edits inside this block are overwritten) -->\n"
            + "## Minecraft environment index (envx)\n- use envx\n<!-- envx:end -->\n";

    @Test
    void tomlTableRangeStopsAtNextTable() {
        String toml = "a = 1\n[mcp_servers.envx]\nenabled = false\ncommand = 'x'\n[other]\nb = 2\n";
        int[] r = AgentSetup.tableRange(toml, "mcp_servers.envx");
        assertEquals("[mcp_servers.envx]\nenabled = false\ncommand = 'x'\n", toml.substring(r[0], r[1]));
        assertNull(AgentSetup.tableRange(toml, "missing"));
        int[] last = AgentSetup.tableRange("[mcp_servers.envx]\nx = 1", "mcp_servers.envx");
        assertArrayEquals(new int[]{0, 24}, last);
    }

    @Test
    void removingABlockKeepsTheUsersOwnText(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("AGENTS.md");
        Files.writeString(f, "# My rules\nBe careful.\n\n" + BLOCK + "\n## More\ntext\n");
        AgentSetup.removeBlock(f, new AgentSetup.State());
        String left = Files.readString(f);
        assertFalse(left.contains("envx"));
        assertEquals("# My rules\nBe careful.\n\n## More\ntext\n", left);
    }

    @Test
    void aFileEnvxCreatedIsDeletedWhenOnlyTheBlockWasInIt(@TempDir Path dir) throws Exception {
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
        Files.writeString(app.resolve("envx.cmd"), "launcher");
        assertEquals(java.util.List.of("0.9.0", "0.2.1"), AgentSetup.pruneVersions(app, 3));
        for (String v : new String[]{"0.10.0", "0.10.2", "1.0.0"}) assertTrue(Files.isDirectory(app.resolve(v)));
        assertTrue(Files.exists(app.resolve("envx.cmd")));
    }
}
