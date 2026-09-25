package dev.envx.query;

import dev.envx.store.Db;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code mod:<id>[,<id>...]} qualifier. It can appear in any query string, so no tool needs an
 * extra schema parameter. Each term matches, in order of preference: a mod id exactly, the initials of
 * a mod's name ({@code mod:opac} is Open Parties and Claims), or a substring of id, name or file name. A
 * selected mod brings its jar-in-jar children with it (Fabric API's classes live in nested modules).
 */
public final class ModFilter {
    private static final Pattern TOKEN = Pattern.compile("(?:^|\\s)mod:([\\w.,+\\-]+)(?=\\s|$)");

    public record Parsed(String rest, List<String> terms) {
        public boolean onlyMods() {
            return !terms.isEmpty() && rest.isBlank();
        }
    }

    private ModFilter() {}

    public static Parsed extract(String query) {
        if (query == null) return new Parsed(null, List.of());
        Matcher m = TOKEN.matcher(query);
        List<String> terms = new ArrayList<>();
        StringBuilder rest = new StringBuilder();
        while (m.find()) {
            for (String t : m.group(1).split(",")) if (!t.isBlank()) terms.add(t.trim().toLowerCase(Locale.ROOT));
            m.appendReplacement(rest, " ");
        }
        m.appendTail(rest);
        return new Parsed(rest.toString().trim(), terms);
    }

    /** Restricts {@code s} to the mods named by {@code terms}; fails with the closest names when nothing matches. */
    public static Scope apply(Db db, Scope s, List<String> terms) throws SQLException {
        Set<Long> ids = new LinkedHashSet<>();
        Set<String> labels = new TreeSet<>();
        for (String term : terms) {
            List<Object[]> hits = matches(db, s, term);
            if (hits.isEmpty()) {
                throw new IllegalArgumentException("No loaded mod matches 'mod:" + term + "' in " + s.env()
                        + ". Use env with a filter to list mods.");
            }
            for (Object[] h : hits) {
                ids.add((long) h[0]);
                labels.add((String) h[1]);
            }
        }
        // nested children (jar-in-jar) belong to the selected mod
        List<Long> frontier = new ArrayList<>(ids);
        while (!frontier.isEmpty()) {
            List<Long> next = new ArrayList<>();
            for (long parent : frontier) {
                for (long child : db.query("SELECT n.child_id FROM artifact_nested n WHERE n.parent_id=? AND n.child_id IN (" + s.artifactSet() + ")",
                        rs -> rs.getLong(1), parent)) {
                    if (ids.add(child)) next.add(child);
                }
            }
            frontier = next;
        }
        String label = labels.size() <= 4 ? String.join(",", labels) : labels.size() + " mods";
        return s.restrict(ids, label);
    }

    private static List<Object[]> matches(Db db, Scope s, String term) throws SQLException {
        String base = "SELECT a.id, a.mod_id FROM artifact a WHERE a.id IN (" + s.artifactSet() + ") AND a.mod_id IS NOT NULL";
        List<Object[]> exact = db.query(base + " AND lower(a.mod_id)=?", rs -> new Object[]{rs.getLong(1), rs.getString(2)}, term);
        if (!exact.isEmpty()) return exact;
        // Common abbreviations are initials of the display name: "Open Parties and Claims" -> opac.
        List<Object[]> initials = new ArrayList<>();
        for (Object[] r : db.query(base.replace("SELECT a.id, a.mod_id", "SELECT a.id, a.mod_id, a.mod_name") + " AND a.mod_name IS NOT NULL",
                rs -> new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3)})) {
            if (initials((String) r[2]).equals(term)) initials.add(new Object[]{r[0], r[1]});
        }
        if (!initials.isEmpty()) return initials;
        String like = "%" + term + "%";
        return db.query(base + " AND (lower(a.mod_id) LIKE ? OR lower(coalesce(a.mod_name,'')) LIKE ? OR lower(a.file_name) LIKE ?)",
                rs -> new Object[]{rs.getLong(1), rs.getString(2)}, like, like, like);
    }

    static String initials(String name) {
        StringBuilder sb = new StringBuilder();
        for (String w : name.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) if (!w.isEmpty()) sb.append(w.charAt(0));
        return sb.toString();
    }
}
