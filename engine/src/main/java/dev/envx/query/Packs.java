package dev.envx.query;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * One file holding all text files of a folder that never changes (a decompiled jar, a jar's extracted resources),
 * for {@code grep}. On a spinning disk, reading tens of thousands of small files from cold took 21 s (decompiled code)
 * and 55 s (resources), nearly all of it seeks; one file per folder is read in one sweep (ADR 0011).
 *
 * <p>Format: each file starts with a line {@link #ENTRY}{@code <path relative to the folder>}, followed by its text.
 * Files that are not UTF-8 are left out, exactly as grep skips them when reading the folder.
 */
public final class Packs {
    /** The pack's file name inside its folder (dot files are never searched themselves). */
    public static final String NAME = ".pack";
    static final char ENTRY = '\u0001';

    private Packs() {}

    public static Path of(Path dir) {
        return dir.resolve(NAME);
    }

    /**
     * Writes {@code dir}'s pack from its files that {@code include} accepts (sorted, so it is reproducible). Returns
     * false, writing nothing, when a file's text contains the entry marker at a line start (it could not be read back).
     */
    public static boolean write(Path dir, Predicate<Path> include) throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(dir)) {
            files = walk.filter(Files::isRegularFile).filter(f -> {
                String n = f.getFileName().toString();
                return !n.startsWith(".") && !n.endsWith(".tmp");
            }).filter(include).sorted().toList();
        }
        Path tmp = dir.resolve(NAME + "." + ProcessHandle.current().pid() + ".tmp");
        try (var out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            for (Path f : files) {
                String text;
                try {
                    text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(Files.readAllBytes(f))).toString();
                } catch (CharacterCodingException e) {
                    continue; // binary: grep skips it too
                }
                if (text.indexOf(ENTRY) >= 0 && (text.charAt(0) == ENTRY || text.contains("\n" + ENTRY) || text.contains("\r" + ENTRY))) {
                    out.close();
                    Files.deleteIfExists(tmp);
                    return false;
                }
                out.write(ENTRY + dir.relativize(f).toString().replace('\\', '/') + "\n");
                out.write(text);
                if (!text.isEmpty() && !text.endsWith("\n") && !text.endsWith("\r")) out.write("\n");
            }
        }
        Files.move(tmp, of(dir), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
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
