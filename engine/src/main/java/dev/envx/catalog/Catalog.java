package dev.envx.catalog;

import com.google.gson.Gson;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The published catalog: which baselines and modpacks are supported, with size estimates and where each pack
 * version's recipe is (ADR 0013). It holds only recipes (official sources, hashes, sizes), never third-party files.
 * Fetched on demand from {@link dev.envx.Config#catalogUrl}; nothing is stored until something is installed.
 */
public final class Catalog {
    public int schema;
    public List<CatalogBaseline> baselines = new ArrayList<>();
    public List<Pack> packs = new ArrayList<>();

    public record CatalogBaseline(String id, String name, String summary, long downloadBytes, long diskBytes) {}

    public record Pack(String id, String name, String summary, String baseline, String source, List<PackVersion> versions) {
        public PackVersion latest() {
            return versions == null || versions.isEmpty() ? null : versions.getLast();
        }
    }

    public record PackVersion(String version, String recipe, int mods, long downloadBytes, long diskBytes) {}

    static final Gson GSON = new Gson();
    public static final String USER_AGENT = "envx/" + dev.envx.Version.VALUE + " (+https://github.com/dzyfps-git/envx)";

    /** Reads the catalog at {@code url} (https:, http: or file:). */
    public static Catalog load(String url) throws IOException {
        Catalog c = GSON.fromJson(fetchText(url), Catalog.class);
        if (c == null) throw new IOException("empty catalog at " + url);
        if (c.schema != 1) throw new IOException("catalog schema " + c.schema + " needs a newer envx");
        if (c.baselines == null) c.baselines = new ArrayList<>();
        if (c.packs == null) c.packs = new ArrayList<>();
        return c;
    }

    /** A text file from an https:, http: or file: URL, or a path relative to {@code base} (a recipe next to the catalog). */
    public static String fetchText(String url) throws IOException {
        URI uri = URI.create(url);
        if ("file".equals(uri.getScheme())) return Files.readString(Path.of(uri), StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).header("User-Agent", USER_AGENT).GET().build();
        try {
            HttpResponse<String> r = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15))
                    .build().send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " for " + url);
            return r.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted fetching " + url, e);
        }
    }

    /** {@code rel} resolved against the catalog's URL (recipes are listed relative to it). */
    public static String resolve(String catalogUrl, String rel) {
        return URI.create(catalogUrl).resolve(rel).toString();
    }
}
