package dev.envx.env;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Redacts secret-looking values from text files before they are stored, so nothing downstream
 * (grep results, agent context) can leak an RCON password, bot token or webhook URL.
 * Deliberately over-eager: a redacted harmless value costs little, a leaked secret costs a lot.
 */
public final class SecretFilter {
    private static final String KEY = "[\\w.\\-]*(?:password|passwd|secret|token|api[_\\-]?key|webhook|private[_\\-]?key|credential)[\\w.\\-]*";
    /** key=value, key: value, "key": "value" (properties, toml, yaml, json). */
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?i)(\"?" + KEY + "\"?\\s*[:=]\\s*)(\"[^\"\\n]*\"|'[^'\\n]*'|[^\\s,}\\n]+)");
    private static final Pattern DISCORD_WEBHOOK = Pattern.compile("https://(?:\\w+\\.)?discord(?:app)?\\.com/api/webhooks/\\S+");
    private static final Pattern DISCORD_TOKEN = Pattern.compile("[MNO][A-Za-z\\d_-]{23,25}\\.[A-Za-z\\d_-]{6}\\.[A-Za-z\\d_-]{27,}");

    private SecretFilter() {}

    public static String redact(String text) {
        Matcher m = ASSIGNMENT.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value = m.group(2);
            String replacement = value.startsWith("\"") ? "\"<redacted>\"" : value.startsWith("'") ? "'<redacted>'" : "<redacted>";
            // Keep empty values and booleans visible: "require_password=false" is useful and not secret.
            String bare = value.replaceAll("^[\"']|[\"']$", "").toLowerCase(Locale.ROOT);
            if (bare.isEmpty() || bare.equals("true") || bare.equals("false")) replacement = value;
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + replacement));
        }
        m.appendTail(sb);
        String out = DISCORD_WEBHOOK.matcher(sb.toString()).replaceAll("<redacted-webhook>");
        return DISCORD_TOKEN.matcher(out).replaceAll("<redacted-token>");
    }

    /** Files that are never copied at all. */
    public static boolean isSecretFile(String relPath) {
        String n = relPath.toLowerCase(Locale.ROOT);
        String file = n.substring(n.lastIndexOf('/') + 1);
        return file.endsWith(".env") || file.startsWith(".env") || file.contains("secret") || file.contains("credential")
                || file.contains("password") || file.endsWith(".pem") || file.endsWith(".key") || file.endsWith(".jks")
                || file.equals("ops.json") || file.equals("whitelist.json") || file.equals("banned-ips.json")
                || file.equals("usercache.json");
    }
}
