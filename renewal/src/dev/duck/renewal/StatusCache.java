package dev.duck.renewal;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Display-only cache. Every transaction still runs full Keybox validation. */
final class StatusCache {
    static long expires(Path root, Path target, Set<String> pins, long now) throws Exception {
        byte[] xml = Keybox.readFile(target, 128 * 1024);
        String hash = Keybox.sha256(xml);
        List<String> sorted = new ArrayList<>(pins); Collections.sort(sorted);
        String trust = sorted.toString();
        Path file = root.resolve("status-cache.json");
        try {
            JSONObject cache = new JSONObject(new String(Keybox.readFile(file, 4096), StandardCharsets.UTF_8));
            if (hash.equals(cache.getString("hash")) && trust.equals(cache.getString("trust"))
                    && now >= cache.getLong("checked_at")) return cache.getLong("expires");
        } catch (Exception ignored) { /* A missing/corrupt cache is never validation evidence. */ }
        Keybox box = Keybox.parse(xml, pins, now, false);
        try {
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return box.expires;
            Renewal.atomic(file, new JSONObject().put("hash", hash).put("trust", trust)
                    .put("checked_at", now).put("expires", box.expires).toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { /* Display remains usable on read-only storage. */ }
        return box.expires;
    }
}
