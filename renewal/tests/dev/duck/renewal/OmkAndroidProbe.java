package dev.duck.renewal;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Android acceptance probe: only reads the live backend; writes disposable dummy files. */
public final class OmkAndroidProbe {
    public static void main(String[] args) throws Exception {
        Path temporary = Paths.get(args[0]), module = Paths.get(args[1]);
        Path data = temporary.resolve("data"); Renewal.mkdir(data);
        Omk omk = new Omk(module, data, Paths.get("/"));
        if (!omk.supported()) throw new AssertionError("live OMK unsupported");
        Omk.Evidence cursor = (Omk.Evidence) omk.cursor();
        Keybox box = Keybox.read(omk.tee.resolve("keybox.xml"), Keybox.pins(module.resolve("renewal-roots.pem")),
                System.currentTimeMillis(), false);
        Path dummy = temporary.resolve("metadata-dummy");
        Renewal.atomic(dummy, new byte[]{1});
        Class<?> os = Class.forName("android.system.Os");
        os.getMethod("chown", String.class, int.class, int.class).invoke(null, dummy.toString(), 1017, 1017);
        byte[] before = (byte[]) os.getMethod("getxattr", String.class, String.class)
                .invoke(null, dummy.toString(), "security.selinux");
        Renewal.atomic(dummy, new byte[]{2});
        Object stat = os.getMethod("stat", String.class).invoke(null, dummy.toString());
        byte[] after = (byte[]) os.getMethod("getxattr", String.class, String.class)
                .invoke(null, dummy.toString(), "security.selinux");
        if (stat.getClass().getField("st_uid").getInt(stat) != 1017
                || stat.getClass().getField("st_gid").getInt(stat) != 1017 || !Arrays.equals(before, after)
                || Files.readAllBytes(dummy)[0] != 2) throw new AssertionError("metadata preservation failed");
        Renewal engine = new Renewal(data.resolve("var/renewal"), data.resolve("var/outputs"),
                omk.tee.resolve("keybox.xml"), Keybox.pins(module.resolve("renewal-roots.pem")), omk);
        JSONObject status = engine.status();
        if (!status.getBoolean("target_supported") || !status.getBoolean("certificate_valid"))
            throw new AssertionError("current certificate invalid");
        System.out.println(new JSONObject().put("supported", true).put("backend", omk.backend())
                .put("processes", cursor.processes.size()).put("certificate_valid", true)
                .put("expires_at", box.expires).put("metadata_preserved", true)
                .put("live_keybox_modified", false).put("network_requests", 0));
        Files.delete(dummy);
    }
}
