package dev.envx.traces;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.envx.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Made-up Codex and Claude Code session files, read in place into the trace report. */
class TraceReportTest {
    @TempDir
    Path tmp;

    private static String now(int seconds) {
        return Instant.now().minusSeconds(3600 - seconds).toString();
    }

    private static JsonObject codexLine(int t, String type, JsonObject payload) {
        JsonObject o = new JsonObject();
        o.addProperty("timestamp", now(t));
        o.addProperty("type", type);
        o.add("payload", payload);
        return o;
    }

    private static JsonObject codexMeta(String id, String cwd, String originator) {
        JsonObject p = new JsonObject();
        p.addProperty("id", id);
        p.addProperty("cwd", cwd);
        p.addProperty("timestamp", now(0));
        p.addProperty("originator", originator);
        return codexLine(0, "session_meta", p);
    }

    private static List<JsonObject> codexExec(int t, String callId, String js, String output) {
        JsonObject call = new JsonObject();
        call.addProperty("type", "custom_tool_call");
        call.addProperty("name", "exec");
        call.addProperty("call_id", callId);
        call.addProperty("input", js);
        JsonObject out = new JsonObject();
        out.addProperty("type", "custom_tool_call_output");
        out.addProperty("call_id", callId);
        JsonArray parts = new JsonArray();
        JsonObject part = new JsonObject();
        part.addProperty("type", "input_text");
        part.addProperty("text", "Script completed\nOutput:\n" + output);
        parts.add(part);
        out.add("output", parts);
        return List.of(codexLine(t, "response_item", call), codexLine(t + 1, "response_item", out));
    }

    private static JsonObject claudeLine(int t, String entrypoint, String cwd, String role, JsonObject content) {
        JsonObject o = new JsonObject();
        o.addProperty("type", role);
        o.addProperty("sessionId", "claude-2222");
        o.addProperty("cwd", cwd);
        o.addProperty("entrypoint", entrypoint);
        o.addProperty("timestamp", now(t));
        JsonObject msg = new JsonObject();
        JsonArray c = new JsonArray();
        c.add(content);
        msg.add("content", c);
        o.add("message", msg);
        return o;
    }

    private static void write(Path f, List<JsonObject> lines) throws Exception {
        Files.createDirectories(f.getParent());
        StringBuilder sb = new StringBuilder();
        for (JsonObject l : lines) sb.append(l).append('\n');
        Files.writeString(f, sb);
    }

    @Test
    void reportsShellWorkEnvxCouldHaveAnsweredAndAnswersThatCostAFollowUp() throws Exception {
        System.setProperty("envx.home", tmp.resolve("home").toString());
        Config config;
        try {
            config = Config.load();
        } finally {
            System.clearProperty("envx.home");
        }
        Path denied = tmp.resolve("private");
        config.denyRoots = List.of(denied.toString());
        Path codex = tmp.resolve("codex/sessions/2026/09/25");
        String proj = tmp.resolve("my-mod").toString();

        List<JsonObject> a = new ArrayList<>();
        a.add(codexMeta("c0dex-1111", proj, "Codex Desktop"));
        a.addAll(codexExec(10, "c1", "const r = await tools.exec_command({\"cmd\":\"javap -p -classpath mods/demo-1.0.0.jar dev.demo.mixin.LivingMixin\"});"
                + " await tools.mcp__envx__find({query:\"LivingEntity\"});", "public class LivingMixin {}"));
        a.addAll(codexExec(20, "c2", "await tools.mcp__envx__grep({pattern:\"vampire\",scope:\"source\"})", "No matches for /vampire/ in source."));
        a.addAll(codexExec(30, "c3", "await tools.exec_command({cmd:'unzip -l mods/other-2.0.0.jar'})", "Archive: other-2.0.0.jar"));
        write(codex.resolve("rollout-a.jsonl"), a);
        write(codex.resolve("rollout-headless.jsonl"), List.of(codexMeta("c0dex-3333", proj, "codex_exec")));
        write(codex.resolve("rollout-denied.jsonl"), List.of(codexMeta("c0dex-4444", denied.resolve("x").toString(), "Codex Desktop")));

        Path claude = tmp.resolve("claude/projects/my-mod");
        JsonObject use = new JsonObject();
        use.addProperty("type", "tool_use");
        use.addProperty("id", "u1");
        use.addProperty("name", "Bash");
        JsonObject input = new JsonObject();
        input.addProperty("command", "javap -c -classpath " + System.getProperty("user.home") + "/x.jar net.minecraft.entity.LivingEntity");
        use.add("input", input);
        JsonObject result = new JsonObject();
        result.addProperty("type", "tool_result");
        result.addProperty("tool_use_id", "u1");
        result.addProperty("content", "x".repeat(4000));
        write(claude.resolve("s.jsonl"), List.of(claudeLine(40, "claude-desktop", proj, "assistant", use), claudeLine(41, "claude-desktop", proj, "user", result)));
        write(claude.resolve("headless.jsonl"), List.of(claudeLine(40, "sdk-cli", proj, "assistant", use)));

        String report = TraceReport.run(config, 30, tmp.resolve("codex/sessions"), tmp.resolve("claude/projects"));
        assertTrue(report.contains("2 agent sessions (1 Codex, 1 Claude Code), 1 of them used envx"), report);
        assertTrue(report.contains("Left out: 2 headless runs (benchmarks, scripts), 1 in denied folders"), report);
        assertTrue(report.contains("- javap: 2 commands in 2 sessions (1 of them also used envx; 2 commands since envx was first used), ~1006 tokens of output. envx: outline / source"), report);
        assertTrue(report.contains("about: demo.jar, LivingMixin, LivingEntity") || report.contains("about: demo.jar"), report);
        assertTrue(report.contains("- grep -> reading jars: 1"), report); // shell work right after an empty envx answer
        assertTrue(report.contains("- empty answers: 1 (grep 1)"), report);
        assertFalse(report.contains(System.getProperty("user.home")), "home folder shown as ~");
    }

    @Test
    void serverLogsAreAServersOwnLogsNotAProjectsTestRun() {
        for (String s : List.of("rg x S:\\logs\\latest.log", "Get-Content 'C:\\MyServer\\logs\\latest.log'", "ls \\\\host\\share\\crash-reports")) {
            assertEquals("server logs", TraceReport.classify(s).name(), s);
        }
        for (String s : List.of(".\\integration\\candidate\\logs\\latest.log", "D:\\Projects\\my-mod\\1.5\\run\\logs\\latest.log", "tail run/logs/latest.log")) {
            assertTrue(TraceReport.classify(s) == null, s);
        }
    }

    @Test
    void codexCommandsAreReadFromTheirJsSource() {
        String js = "await tools.exec_command({cmd:\"rg -n \\\"tick\\\" src\", workdir:'x'})";
        String args = Traces.argsText(js, js.indexOf('(') + 1);
        assertEquals("rg -n \"tick\" src", Traces.jsString(args, "cmd"));
        assertEquals("javap -p X", Traces.jsString("{\"cmd\":\"javap -p X\"}", "cmd"));
    }
}
