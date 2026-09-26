package dev.sevli.env;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Fabric Loader's resolved mod list from {@code logs/latest.log}.
 *
 * <p>The loader prints every mod it actually loaded, including jar-in-jar mods after it picked the
 * highest version of each id. Using this list avoids re-implementing loader resolution.
 * <pre>
 * [main/INFO]: Loading 452 mods:
 * 	- fabric-api 0.92.2+1.20.1
 * 	   |-- fabric-api-base 0.4.31+1802ada577
 * 	   \-- fabric-api-lookup-api-v1 1.6.36+1802ada577
 * </pre>
 */
public final class LoaderLog {
    private static final Pattern HEADER = Pattern.compile("Loading (\\d+) mods:");
    private static final Pattern TOP = Pattern.compile("^\\t- (\\S+) (\\S+)");
    /** Nested entries use {@code |--} or {@code \--} (last child), indented with {@code |} for deeper levels. */
    private static final Pattern NESTED = Pattern.compile("^\\t[\\s|]*[|\\\\]-- (\\S+) (\\S+)");

    public record LoadedMod(String id, String version, boolean nested) {}

    private LoaderLog() {}

    /** The loader prints parsed versions ({@code 25.1.31}) while jars may say {@code 25.01.31}. */
    public static String normalizeVersion(String v) {
        return v == null ? null : v.replaceAll("(?<=^|[.\\-+])0+(?=\\d)", "");
    }

    /** Returns the loaded mods, or an empty list when the log has no loader mod list. */
    public static List<LoadedMod> parse(String log) {
        List<LoadedMod> out = new ArrayList<>();
        String[] lines = log.split("\\r?\\n");
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            Matcher h = HEADER.matcher(lines[i]);
            if (h.find()) start = i + 1; // last occurrence wins if the log has several
        }
        if (start < 0) return out;
        for (int i = start; i < lines.length; i++) {
            String l = lines[i];
            Matcher top = TOP.matcher(l);
            Matcher nested = NESTED.matcher(l);
            if (top.find()) out.add(new LoadedMod(top.group(1), top.group(2), false));
            else if (nested.find()) out.add(new LoadedMod(nested.group(1), nested.group(2), true));
            else if (!l.startsWith("\t")) break;
        }
        return out;
    }
}
