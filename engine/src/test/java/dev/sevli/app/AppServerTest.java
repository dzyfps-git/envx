package dev.sevli.app;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sevli.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The desktop app's protocol (ADR 0013) on an empty install: answers, errors, and a failing install's events. */
class AppServerTest {
    @TempDir
    Path tmp;

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    private List<JsonObject> lines() {
        List<JsonObject> out = new ArrayList<>();
        for (String l : bytes.toString(StandardCharsets.UTF_8).split("\\R")) if (!l.isBlank()) out.add(JsonParser.parseString(l).getAsJsonObject());
        return out;
    }

    private JsonObject answer(int id) throws InterruptedException {
        for (int i = 0; i < 600; i++) { // installs answer from a background thread
            for (JsonObject o : lines()) if (!o.has("event") && o.get("id").getAsInt() == id) return o;
            Thread.sleep(100);
        }
        throw new AssertionError("no answer for " + id + ": " + bytes);
    }

    @Test
    void anEmptyInstallAnswersWithoutCreatingAnything() throws Exception {
        Path home = tmp.resolve("home");
        System.setProperty("sevli.home", home.toString());
        Config config;
        try {
            config = Config.load();
        } finally {
            System.clearProperty("sevli.home");
        }
        Path index = tmp.resolve("catalog.json");
        Files.writeString(index, "{\"schema\":1,\"baselines\":[],\"packs\":[{\"id\":\"p\",\"name\":\"P\",\"baseline\":\"fabric-1.20.1\","
                + "\"versions\":[{\"version\":\"1\",\"recipe\":\"r.json\",\"mods\":3}]}]}");
        config.catalogUrl = index.toUri().toString();
        AppServer s = new AppServer(config, new PrintStream(bytes, true, StandardCharsets.UTF_8));

        s.handle("{\"id\":1,\"op\":\"hello\"}");
        JsonObject hello = answer(1);
        assertTrue(hello.get("ok").getAsBoolean());
        assertEquals(1, hello.get("app").getAsInt());
        assertFalse(hello.getAsJsonObject("result").getAsJsonObject("home").get("exists").getAsBoolean());

        s.handle("{\"id\":2,\"op\":\"status\"}");
        JsonObject status = answer(2).getAsJsonObject("result");
        assertEquals(0, status.getAsJsonArray("environments").size());
        JsonObject baseline = status.getAsJsonArray("baselines").get(0).getAsJsonObject();
        assertEquals("fabric-1.20.1", baseline.get("id").getAsString());
        assertFalse(baseline.get("installed").getAsBoolean());

        s.handle("{\"id\":3,\"op\":\"catalog\"}");
        JsonObject pack = answer(3).getAsJsonObject("result").getAsJsonArray("packs").get(0).getAsJsonObject();
        assertEquals("P", pack.get("name").getAsString());
        assertTrue(pack.get("supported").getAsBoolean());
        assertFalse(pack.get("installed").getAsBoolean());

        s.handle("{\"id\":4,\"op\":\"nope\"}");
        assertEquals("unknown_op", answer(4).getAsJsonObject("error").get("code").getAsString());

        s.handle("{\"id\":5,\"op\":\"install\",\"target\":\"not-a-baseline\"}"); // runs sevli in its own process
        JsonObject failed = answer(5);
        assertFalse(failed.get("ok").getAsBoolean());
        assertEquals("install_failed", failed.getAsJsonObject("error").get("code").getAsString());
        assertTrue(failed.getAsJsonObject("error").get("message").getAsString().contains("is not a baseline this sevli can install"), failed.toString());

        assertFalse(Files.exists(home), "nothing was created: no install happened");
    }
}
