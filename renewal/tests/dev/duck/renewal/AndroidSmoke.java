package dev.duck.renewal;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Read-only device smoke test. Excluded from the release DEX. */
public final class AndroidSmoke {
    public static void main(String[] args) throws Exception {
        JSONObject result = new JSONObject();
        try {
            Path module = Paths.get(args[0]);
            Path target = Paths.get("/data/adb/teesim/keybox.xml");
            Main platform = new Main(module, Paths.get("/data/adb/duck-toolbox"), target.getParent());
            Keybox box = Keybox.read(target, Keybox.pins(module.resolve("renewal-roots.pem")), platform.now(), true);
            result.put("certificate_valid", true).put("expires_at", box.expires)
                    .put("profile_supported", platform.supported()).put("validated_network_available", platform.online());
            byte[] log = Files.readAllBytes(target.getParent().resolve("log/teesim.log"));
            result.put("current_keybox_has_runtime_ack", Main.hasAck(new String(log, StandardCharsets.UTF_8), box.runtimeId));
        } catch (Exception error) {
            result.put("certificate_valid", false).put("exception_type", error.getClass().getSimpleName());
            // Only a fixed internal code is shown, never provider/parser messages or input bytes.
            String message = error.getMessage();
            if (message != null && message.matches("[a-z_]{1,40}")) result.put("internal_code", message);
        }
        System.out.println(result);
    }
}
