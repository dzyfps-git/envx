package dev.sevli.query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Size-limited text output. Agents pay for every character, so each answer is capped
 * (default ~6000 chars, roughly 1.5k tokens). When a cap is hit, the output says how much was
 * left out and how to narrow the query, instead of silently cutting. Lines added with a name
 * are listed by name at the end when they do not fit, so a cut answer still shows everything it matched.
 */
public final class Out {
    public static final int DEFAULT_BUDGET = 6000;

    private final StringBuilder sb = new StringBuilder();
    private final int budget;
    private int dropped;
    /** Dropped named lines, grouped (e.g. by class); the "" group is listed bare. */
    private final Map<String, Group> droppedNames = new LinkedHashMap<>();

    private static final class Group {
        final List<String> names = new ArrayList<>();
        final Map<String, Integer> tallies = new TreeMap<>(); // counted, not named (e.g. fields)
    }

    public Out(int budget) {
        this.budget = budget <= 0 ? DEFAULT_BUDGET : budget;
    }

    /** Appends a line if it fits; returns false once the budget is exhausted. */
    public boolean line(String s) {
        if (sb.length() + s.length() + 1 > budget) {
            dropped++;
            return false;
        }
        sb.append(s).append('\n');
        return true;
    }

    /**
     * Appends a line if it fits in the budget minus a third kept for the names of dropped lines;
     * otherwise remembers {@code name} under {@code group} for the closing summary, which costs a
     * few characters per dropped line instead of a whole line. A null name only opens the group
     * (a group header). Once one line is dropped, later ones are too, so the listing has no gaps.
     */
    public boolean line(String s, String group, String name) {
        return named(s, group, name, null);
    }

    /** Like {@link #line(String, String, String)}, but a dropped line is only counted: "+3 fields". */
    public boolean tallied(String s, String group, String noun) {
        return named(s, group, null, noun);
    }

    private boolean named(String s, String group, String name, String noun) {
        if (droppedNames.isEmpty() && sb.length() + s.length() + 1 <= budget - budget / 3) {
            sb.append(s).append('\n');
            return true;
        }
        dropped++;
        Group g = droppedNames.computeIfAbsent(group, k -> new Group());
        if (name != null && !g.names.contains(name)) g.names.add(name); // overloads are listed once
        if (noun != null) g.tallies.merge(noun, 1, Integer::sum);
        return false;
    }

    /** Always appended (headers and hints), even past the budget. */
    public void force(String s) {
        sb.append(s).append('\n');
    }

    public boolean full() {
        return dropped > 0;
    }

    /** Characters left before the budget is reached. */
    public int remaining() {
        return budget - sb.length();
    }

    public void dropped(int n) {
        dropped += n;
    }

    public String finish(String refineHint) {
        if (dropped > 0) {
            sb.append("… ").append(dropped).append(" more line(s) omitted");
            String names = names(Math.max(300, budget / 3));
            if (!names.isEmpty()) sb.append(": ").append(names);
            if (refineHint != null) sb.append(names.isEmpty() ? "; " : "\n  ").append(refineHint);
            sb.append('\n');
        }
        return sb.toString();
    }

    /** "A(x, y, +2 fields); B(z); w", cut at {@code max} chars with a count of the names that did not fit. */
    private String names(int max) {
        StringBuilder s = new StringBuilder();
        int total = droppedNames.values().stream().mapToInt(g -> g.names.size()).sum();
        int shown = 0, skippedGroups = 0;
        for (var e : droppedNames.entrySet()) {
            List<String> parts = new ArrayList<>(e.getValue().names);
            e.getValue().tallies.forEach((noun, n) -> parts.add("+" + n + " " + noun + (n == 1 ? "" : "s")));
            if (parts.isEmpty()) continue;
            if (e.getKey().isEmpty()) { // bare names (one class's outline): as many as fit
                for (String p : parts) {
                    String sep = s.isEmpty() ? "" : ", ";
                    if (s.length() + sep.length() + p.length() > max) break;
                    s.append(sep).append(p);
                    if (!p.startsWith("+")) shown++;
                }
                continue;
            }
            String item = e.getKey() + "(" + String.join(", ", parts) + ")";
            String sep = s.isEmpty() ? "" : "; ";
            if (s.length() + sep.length() + item.length() > max) { // a class is listed whole or not at all
                skippedGroups++;
                continue;
            }
            s.append(sep).append(item);
            shown += e.getValue().names.size();
        }
        if (shown < total) {
            s.append(s.isEmpty() ? "" : "; ").append("+").append(total - shown).append(" more names");
            if (skippedGroups > 0) s.append(" in ").append(skippedGroups).append(" more classes");
        }
        return s.toString();
    }
}
