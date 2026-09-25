package dev.envx.env;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Which modpack and version a server folder runs, read from files packs already ship. CurseForge packs
 * usually include BetterCompatibilityChecker's {@code config/bcc.json}
 * ({@code {"projectID":466901,"modpackName":"Prominence II: Hasturian Era","modpackVersion":"v4.1.0"}}).
 * Without one, snapshots are still kept; they are just unlabeled.
 *
 * @param pack    stable pack identity: {@code curseforge:<projectID>}, else a slug of the name
 * @param name    display name
 * @param version version label without a leading "v", e.g. {@code 4.1.0}
 */
public record PackInfo(String pack, String name, String version) {
    public static final String BCC = "config/bcc.json";

    /** Null when the file is missing or says nothing useful. */
    public static PackInfo fromBcc(byte[] json) {
        if (json == null) return null;
        try {
            JsonObject o = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
            String name = str(o, "modpackName");
            String version = str(o, "modpackVersion");
            String project = str(o, "projectID");
            if (version == null && name == null) return null;
            String pack = project != null && !project.equals("0") ? "curseforge:" + project : name != null ? slug(name) : null;
            return new PackInfo(pack, name, version == null ? null : version.replaceFirst("^[vV](?=\\d)", ""));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() && !o.get(k).getAsString().isBlank() ? o.get(k).getAsString().trim() : null;
    }

    static String slug(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }
}
