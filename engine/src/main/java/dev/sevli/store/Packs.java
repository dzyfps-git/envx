package dev.sevli.store;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * One file holding many text files that never change (a decompiled jar, a jar's extracted resources, a snapshot's
 * config texts), for {@code grep}. On a spinning disk, reading tens of thousands of small files from cold took 21 s
 * (decompiled code) and 55 s (resources), nearly all of it seeks; one file is read in one sweep (ADR 0011).
 *
 * <p>Format: each file starts with a line {@link #ENTRY}{@code <path as answers show it>}, followed by its text.
 * Files that are not UTF-8 are left out, exactly as grep skips them when reading them one by one.
 */
public final class Packs {
    /** A folder's pack file name inside it (dot files are never searched themselves). */
    public static final String NAME = ".pack";
    public static final char ENTRY = '\u0001';

    private Packs() {}

    public static Path of(Path dir) {
        return dir.resolve(NAME);
    }

    /**
     * Writes {@code dir}'s pack from its files that {@code include} accepts (sorted, so it is reproducible). Returns
     * false, writing nothing, when a file's text contains the entry marker at a line start (it could not be read back).
     */
    public static boolean write(Path dir, Predicate<Path> include) throws IOException {
        Map<String, Path> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile).filter(f -> {
                String n = f.getFileName().toString();
                return !n.startsWith(".") && !n.endsWith(".tmp");
            }).filter(include).forEach(f -> files.put(dir.relativize(f).toString().replace('\\', '/'), f));
        }
        return write(of(dir), files);
    }

    /** Writes a pack at {@code target} from {path as answers show it -> stored file}, in the map's order. */
    public static boolean write(Path target, Map<String, Path> files) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + "." + ProcessHandle.current().pid() + ".tmp");
        try (var out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            for (var e : files.entrySet()) {
                String text;
                try {
                    text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(Files.readAllBytes(e.getValue()))).toString();
                } catch (CharacterCodingException | NoSuchFileException ex) {
                    continue; // binary (grep skips it too) or gone
                }
                if (text.indexOf(ENTRY) >= 0 && (text.charAt(0) == ENTRY || text.contains("\n" + ENTRY) || text.contains("\r" + ENTRY))) {
                    out.close();
                    Files.deleteIfExists(tmp);
                    return false;
                }
                out.write(ENTRY + e.getKey() + "\n");
                out.write(text);
                if (!text.isEmpty() && !text.endsWith("\n") && !text.endsWith("\r")) out.write("\n");
            }
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return true;
    }

    /** A pack as {path, lines} entries, the lines split as {@link Files#readAllLines} splits a file. */
    public static List<Object[]> read(Path pack) throws IOException {
        List<Object[]> out = new ArrayList<>();
        List<String> lines = null;
        for (String line : Files.readAllLines(pack, StandardCharsets.UTF_8)) {
            if (!line.isEmpty() && line.charAt(0) == ENTRY) {
                lines = new ArrayList<>();
                out.add(new Object[]{line.substring(1), lines});
            } else if (lines != null) {
                lines.add(line);
            }
        }
        return out;
    }
}
