package dev.duck.renewal;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Manual imports follow the configured target; renewal deliberately keeps its narrower policy. */
final class TeeConfig {
    static Path keybox(Path tee, String name) throws Exception {
        Path relative = Paths.get(name);
        if (name.isEmpty() || relative.isAbsolute() || !relative.normalize().equals(relative))
            throw new IOException("target_unsupported");
        Path path = tee.resolve(relative);
        Keybox.safePath(path);
        if (!path.startsWith(tee)) throw new IOException("target_unsupported");
        return path;
    }

    static JSONObject profiles(JSONObject config) throws Exception {
        if (config.optInt("version") != 1) throw new IOException("target_unsupported");
        JSONObject profiles = config.getJSONObject("profiles");
        if (profiles.length() == 0 || profiles.length() > 128 || !profiles.has("default"))
            throw new IOException("target_unsupported");
        for (Iterator<String> ids = profiles.keys(); ids.hasNext();) {
            String id = ids.next(); JSONObject profile = profiles.getJSONObject(id);
            if (id.isEmpty() || id.contains("'") || id.contains("\n") || id.contains("\r")
                    || !Arrays.asList("patch", "generation").contains(profile.optString("mode", "patch")))
                throw new IOException("target_unsupported");
        }
        return profiles;
    }

    static boolean manualTarget(Path tee, Path target, JSONObject config) throws Exception {
        JSONObject profiles = profiles(config);
        if (!keybox(tee, profiles.getJSONObject("default").getString("keybox")).equals(target)) return false;
        for (Iterator<String> ids = profiles.keys(); ids.hasNext();) {
            Path path = keybox(tee, profiles.getJSONObject(ids.next()).getString("keybox"));
            if (!path.equals(target)) Keybox.readFile(path, 128 * 1024);
        }
        return true;
    }

    static Map<String, String> proof(Path tee, JSONObject config) throws Exception {
        Map<String, String> result = new HashMap<>(); JSONObject profiles = profiles(config);
        for (Iterator<String> ids = profiles.keys(); ids.hasNext();) {
            String id = ids.next(); JSONObject p = profiles.getJSONObject(id);
            result.put(id, p.optString("mode", "patch") + "\0"
                    + Keybox.runtimeId(Keybox.readFile(keybox(tee, p.getString("keybox")), 128 * 1024)));
        }
        return result;
    }
}
