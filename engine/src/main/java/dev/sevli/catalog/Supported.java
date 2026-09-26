package dev.sevli.catalog;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import dev.sevli.Config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The publicly supported jars (ADR 0014): exact jar versions, by sha256, taken from the maintainer's published packs.
 * The catalog generates {@code supported.json} next to {@code index.json} and signs it ({@code supported.json.sig},
 * Ed25519); a list without a valid signature is never used. The last good copy is kept under {@code <home>/catalog/}
 * so syncs work offline.
 *
 * <p>Each jar has a status: {@code active}; {@code retired} (not offered any more, but an environment that already
 * indexed it keeps it); {@code revoked} (hidden everywhere, with a reason). {@code since} is the catalog version that
 * first listed it: an environment indexes only jars listed by the catalog version it accepted, so jars that become
 * supported later wait for the user's click.
 */
public final class Supported {
    /**
     * The maintainer's public key (X.509, base64). Empty until the catalog publishes a signed list: then nothing is
     * publicly supported yet. {@code -Dsevli.catalogKey} overrides it for development and tests only.
     */
    static final String PUBLIC_KEY = "";

    public int schema = 1;
    public int catalogVersion;
    public Map<String, Jar> jars = new LinkedHashMap<>();

    public record Jar(String modId, String version, List<String> packs, String status, String reason, int since) {
        public boolean revoked() {
            return "revoked".equals(status);
        }

        public boolean retired() {
            return "retired".equals(status);
        }
    }

    /** Nothing supported (no signed list yet, or it could not be verified). */
    public static final Supported NONE = new Supported();

    private static final Gson GSON = new Gson();
    /** How old the cached list may be before a sync fetches it again. */
    static final Duration MAX_AGE = Duration.ofHours(6);

    public Jar get(String sha256) {
        return sha256 == null ? null : jars.get(sha256);
    }

    /**
     * Whether a jar may be indexed for an environment that accepted catalog version {@code accepted}:
     * active and listed by then, or (retired) already indexed there before ({@code usedBefore}). Never revoked.
     */
    public boolean acceptable(String sha256, int accepted, boolean usedBefore) {
        Jar j = get(sha256);
        if (j == null || j.revoked()) return false;
        if (usedBefore) return true;
        return !j.retired() && j.since() <= accepted;
    }

    /** Listed as supported, but only by a catalog version newer than the one the environment accepted. */
    public boolean newlySupported(String sha256, int accepted) {
        Jar j = get(sha256);
        return j != null && !j.revoked() && !j.retired() && j.since() > accepted;
    }

    // ---------------------------------------------------------------- loading

    static Path cacheDir(Config config) {
        return config.home().resolve("catalog");
    }

    /** The cached list, verified again; {@link #NONE} when there is none or it does not verify. Reads no network. */
    public static Supported cached(Config config) {
        Path dir = cacheDir(config);
        try {
            byte[] json = Files.readAllBytes(dir.resolve("supported.json"));
            byte[] sig = Files.readAllBytes(dir.resolve("supported.json.sig"));
            return parse(json, sig);
        } catch (IOException | RuntimeException e) {
            return NONE;
        }
    }

    /**
     * The list for a sync: fetched from the catalog when the cached copy is older than {@link #MAX_AGE} (or
     * {@code force}), else the cached copy. A failed fetch or a list that does not verify keeps the cached copy.
     */
    public static Supported current(Config config, boolean force) {
        Path dir = cacheDir(config);
        Path json = dir.resolve("supported.json");
        try {
            boolean fresh = Files.exists(json) && Files.getLastModifiedTime(json).toInstant().isAfter(Instant.now().minus(MAX_AGE));
            if (fresh && !force) return cached(config);
        } catch (IOException ignored) {
            // fetch below
        }
        try {
            fetch(config);
        } catch (IOException | RuntimeException e) {
            // offline or not published yet: the cached copy (possibly none) stands
        }
        return cached(config);
    }

    /** Downloads, verifies and caches the list; returns it. Throws when it is missing or does not verify. */
    public static Supported fetch(Config config) throws IOException {
        String url = Catalog.resolve(config.catalogUrl, "supported.json");
        byte[] json = Catalog.fetchText(url).getBytes(StandardCharsets.UTF_8);
        byte[] sig = Catalog.fetchText(url + ".sig").trim().getBytes(StandardCharsets.UTF_8);
        Supported s = parse(json, sig);
        Supported old = cached(config);
        if (s.catalogVersion < old.catalogVersion) throw new IOException("the catalog offered an older supported list (" + s.catalogVersion + " < " + old.catalogVersion + ")");
        Path dir = cacheDir(config);
        Files.createDirectories(dir);
        write(dir.resolve("supported.json"), json);
        write(dir.resolve("supported.json.sig"), sig);
        return s;
    }

    private static void write(Path target, byte[] data) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, data);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Parses a list after checking its signature (base64 text). */
    static Supported parse(byte[] json, byte[] sigBase64) throws IOException {
        if (!verify(json, new String(sigBase64, StandardCharsets.UTF_8).trim())) throw new IOException("the supported list's signature does not match");
        try {
            Supported s = GSON.fromJson(new String(json, StandardCharsets.UTF_8), Supported.class);
            if (s == null) throw new IOException("empty supported list");
            if (s.schema != 1) throw new IOException("supported list schema " + s.schema + " needs a newer sevli");
            if (s.jars == null) s.jars = new LinkedHashMap<>();
            return s;
        } catch (JsonSyntaxException e) {
            throw new IOException("the supported list is not valid JSON: " + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- signatures

    static String publicKey() {
        String dev = System.getProperty("sevli.catalogKey");
        return dev != null && !dev.isBlank() ? dev : PUBLIC_KEY;
    }

    static boolean verify(byte[] data, String sigBase64) {
        String key = publicKey();
        if (key.isBlank()) return false; // nothing is published yet
        try {
            PublicKey pk = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(key)));
            Signature v = Signature.getInstance("Ed25519");
            v.initVerify(pk);
            v.update(data);
            return v.verify(Base64.getDecoder().decode(sigBase64));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    /** For the maintainer: a new key pair, {public, private} as base64 (X.509 / PKCS#8). */
    public static String[] newKeyPair() throws GeneralSecurityException {
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        return new String[]{Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()),
                Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded())};
    }

    /** For the maintainer: the base64 signature of {@code data} with a private key from {@link #newKeyPair}. */
    public static String sign(byte[] data, String privateKeyBase64) throws GeneralSecurityException {
        PrivateKey pk = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyBase64.trim())));
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(pk);
        s.update(data);
        return Base64.getEncoder().encodeToString(s.sign());
    }
}
