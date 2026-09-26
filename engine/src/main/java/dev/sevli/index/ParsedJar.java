package dev.sevli.index;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Everything extracted from one jar before it is written to the database. Pure data. */
public final class ParsedJar {
    public String sha256;
    public String fileName;
    public long size;
    public String kind; // minecraft | mod | library
    /** Raw jar bytes; the writer stores them content-addressed and then drops the reference. */
    public byte[] bytes;
    public ModMeta mod; // null when the jar has no fabric.mod.json
    public final List<ClassRec> classes = new ArrayList<>();
    public final List<MixinRec> mixins = new ArrayList<>();
    public final List<ResourceRec> resources = new ArrayList<>();
    public final List<ParsedJar> nested = new ArrayList<>();
    /** Non-fatal problems worth surfacing (unreadable class, missing mixin config, ...). */
    public final List<String> warnings = new ArrayList<>();

    public record ModMeta(String id, String version, String name, String json) {}

    public record ClassRec(String name, String named, String superName, String interfaces, int access,
                           boolean mixin, List<MemberRec> members, Set<String> refs) {}

    public record MemberRec(char kind, String name, String desc, String named, String namedDesc, int access, Integer line) {}

    public record MixinRec(String config, String mixinClass, String targetClass, String kind, String handler,
                           String targetRaw, String targetName, String targetDesc, String atValue, String atTarget,
                           int priority, boolean cancellable, String side, String plugin) {}

    /** {@code data} is only held until the writer extracts it to the resource cache. */
    public record ResourceRec(String path, long size, byte[] data) {}
}
