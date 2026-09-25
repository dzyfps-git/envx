package dev.envx.tools;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The agent-facing surface (tool names, parameters, descriptions, server instructions) is a 1.0 contract and costs
 * tokens on every agent request. A change must be deliberate: update {@code tool-contract.txt} in the same commit.
 */
class ToolContractTest {
    /** Characters of all tool schemas plus the server instructions, as sent to agents. Raising it needs a reason. */
    private static final int MAX_CHARS = 3600; // 3417 at 0.8

    static String render() {
        StringBuilder sb = new StringBuilder("instructions:\n").append(McpServer.INSTRUCTIONS.strip()).append("\n");
        for (Tools.Tool t : Tools.ALL.values()) {
            sb.append("\ntool ").append(t.name()).append(": ").append(t.description()).append("\n");
            for (Tools.Param p : t.params()) {
                sb.append("  ").append(p.name()).append(" (").append(p.type()).append(p.required() ? ", required" : "").append("): ")
                        .append(p.description()).append("\n");
            }
        }
        return sb.toString();
    }

    @Test
    void theToolContractChangesOnlyOnPurpose() throws IOException {
        String actual = render();
        String expected;
        try (InputStream in = ToolContractTest.class.getResourceAsStream("/tool-contract.txt")) {
            expected = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
        if (!actual.equals(expected)) Files.writeString(Path.of("build", "tool-contract.actual.txt"), actual);
        assertEquals(expected, actual, "tool contract changed; if intended, copy build/tool-contract.actual.txt to src/test/resources/tool-contract.txt");
    }

    @Test
    void theToolSurfaceStaysSmall() {
        int chars = render().length();
        assertTrue(chars <= MAX_CHARS, "tool schemas + instructions grew to " + chars + " chars (limit " + MAX_CHARS + ")");
    }
}
