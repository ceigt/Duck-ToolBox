package dev.duck.renewal;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;
import org.json.*;

/** app_process boundary; public commands return a redacted Duck envelope. */
public final class Main implements Renewal.Platform {
    static final String VERSION = "0.2.0-renewal.1";
    final Path module, data, tee;
    Object context;
    Main(Path module, Path data, Path tee) { this.module = module; this.data = data; this.tee = tee; }

    @Override public long now() { return System.currentTimeMillis(); }

    @Override public boolean supported() throws Exception {
        Path teeModule = Paths.get("/data/adb/modules/teesim");
        if (!Files.isDirectory(teeModule) || Files.exists(teeModule.resolve("disable"))
                || Files.exists(teeModule.resolve("remove")) || Files.exists(module.resolve("disable"))
                || Files.exists(module.resolve("remove"))) return false;
        Properties properties = new Properties();
        properties.load(new ByteArrayInputStream(Keybox.readFile(teeModule.resolve("module.prop"), 16 * 1024)));
        if (Integer.parseInt(properties.getProperty("versionCode", "0")) < 75) return false;
        JSONObject config = new JSONObject(new String(Keybox.readFile(tee.resolve("config.json"), 256 * 1024), StandardCharsets.UTF_8));
        if (config.optInt("version") != 1) return false;
        JSONObject profiles = config.getJSONObject("profiles");
        if (profiles.length() != 1 || !profiles.has("default")) return false;
        JSONObject profile = profiles.getJSONObject("default");
        return "keybox.xml".equals(profile.optString("keybox")) && "patch".equals(profile.optString("mode"));
    }

    @Override public boolean online() {
        try {
            if (context == null) {
                Class<?> looper = Class.forName("android.os.Looper");
                if (looper.getMethod("myLooper").invoke(null) == null) looper.getMethod("prepareMainLooper").invoke(null);
                Class<?> type = Class.forName("android.app.ActivityThread");
                Object thread = type.getMethod("systemMain").invoke(null);
                context = type.getMethod("getSystemContext").invoke(thread);
            }
            Object manager = Class.forName("android.content.Context").getMethod("getSystemService", String.class)
                    .invoke(context, "connectivity");
            Class<?> cm = Class.forName("android.net.ConnectivityManager");
            Object network = cm.getMethod("getActiveNetwork").invoke(manager);
            Object caps = network == null ? null : cm.getMethod("getNetworkCapabilities", Class.forName("android.net.Network"))
                    .invoke(manager, network);
            java.lang.reflect.Method capability = Class.forName("android.net.NetworkCapabilities").getMethod("hasCapability", int.class);
            return caps != null && (Boolean) capability.invoke(caps, 12) && (Boolean) capability.invoke(caps, 16);
        } catch (Exception ignored) { return false; }
    }

    @Override public JSONObject issue(String protocol) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(module.resolve("bin/duckd").toString(), "rkp-device", "provision",
                "--acknowledge-limitations", "--protocol", protocol);
        builder.directory(module.toFile());
        builder.environment().put("DUCK_TOOLBOX_ROOT", module.toString());
        builder.environment().put("DUCK_TOOLBOX_DATA_ROOT", data.toString());
        Process child = builder.start();
        Capture stdout = new Capture(child.getInputStream(), 2 * 1024 * 1024, false);
        Capture stderr = new Capture(child.getErrorStream(), 0, true);
        Thread out = new Thread(stdout), err = new Thread(stderr);
        out.setDaemon(true); err.setDaemon(true); out.start(); err.start();
        if (!child.waitFor(180, TimeUnit.SECONDS)) {
            child.destroyForcibly(); throw new IOException("submission_unknown");
        }
        out.join(2000); err.join(2000);
        if (out.isAlive() || stdout.failed || stdout.bytes == null) throw new IOException("submission_unknown");
        try { return new JSONObject(new String(stdout.bytes, StandardCharsets.UTF_8)); }
        finally { Arrays.fill(stdout.bytes, (byte) 0); }
    }

    static final class Capture implements Runnable {
        final InputStream input; final int maximum; final boolean discard;
        byte[] bytes; boolean failed;
        Capture(InputStream input, int maximum, boolean discard) { this.input = input; this.maximum = maximum; this.discard = discard; }
        @Override public void run() {
            try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = source.read(buffer)) != -1) {
                    if (discard || failed) continue;
                    if (output.size() + n > maximum) { failed = true; continue; }
                    output.write(buffer, 0, n);
                }
                if (!failed && !discard) bytes = output.toByteArray();
            } catch (IOException ignored) { failed = true; }
        }
    }

    @Override public JSONObject revocations() throws Exception {
        // Public full list; no certificate identifier, device ID or key is sent in the URL/body.
        HttpURLConnection connection = (HttpURLConnection) new URL("https://android.googleapis.com/attestation/status").openConnection();
        connection.setConnectTimeout(15000); connection.setReadTimeout(15000);
        connection.setInstanceFollowRedirects(false);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("revocation_unavailable");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = input.read(buffer)) != -1) {
                    if (output.size() + n > 8 * 1024 * 1024) throw new IOException("revocation_oversized");
                    output.write(buffer, 0, n);
                }
                return new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally { connection.disconnect(); }
    }

    static final class Cursor {
        final Object key; final long size;
        Cursor(Object key, long size) { this.key = key; this.size = size; }
    }
    @Override public Object cursor() throws Exception {
        Path log = tee.resolve("log/teesim.log"); Keybox.safePath(log);
        if (!Files.exists(log)) throw new IOException("unsafe_log");
        BasicFileAttributes attrs = Files.readAttributes(log, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile()) throw new IOException("unsafe_log");
        return new Cursor(attrs.fileKey(), attrs.size());
    }

    static boolean hasAck(String log, String id) { return LoadProof.accepted(log, id); }
    @Override public boolean accepted(String id, Object value) throws Exception {
        Cursor cursor = (Cursor) value;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        Path log = tee.resolve("log/teesim.log");
        do {
            Keybox.safePath(log);
            if (Files.isRegularFile(log, LinkOption.NOFOLLOW_LINKS)) {
                BasicFileAttributes attrs = Files.readAttributes(log, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!Objects.equals(cursor.key, attrs.fileKey()) || attrs.size() < cursor.size
                        || attrs.size() - cursor.size > 512 * 1024) return false;
                long start = cursor.size;
                try (RandomAccessFile file = new RandomAccessFile(log.toFile(), "r")) {
                    file.seek(start); byte[] tail = new byte[(int) Math.min(512 * 1024, Math.max(0, file.length() - start))];
                    file.readFully(tail);
                    if (hasAck(new String(tail, StandardCharsets.UTF_8), id)) return true;
                }
            }
            Thread.sleep(500);
        } while (System.nanoTime() < deadline);
        return false;
    }

    public static void main(String[] args) {
        String command = args.length >= 3 ? args[2] : "status";
        try {
            int uid = (Integer) Class.forName("android.system.Os").getMethod("getuid").invoke(null);
            if (uid != 0 || args.length < 3 || args.length > 4) throw new IOException("invalid_invocation");
            Path module = Paths.get(args[0]), data = Paths.get(args[1]);
            Keybox.safePath(module); Keybox.safePath(data);
            if ("config-lease".equals(command)) { ConfigLease.hold(data, System.in, System.out); return; }
            Main platform = new Main(module, data, Paths.get("/data/adb/teesim"));
            Renewal renewal = new Renewal(data.resolve("var/renewal"), data.resolve("var/outputs"),
                    platform.tee.resolve("keybox.xml"), Keybox.pins(module.resolve("renewal-roots.pem")), platform);
            String argument = args.length == 4 ? args[3] : "";
            if ("install".equals(command)) {
                Capture input = new Capture(System.in, 128 * 1024, false); input.run();
                if (input.failed || input.bytes == null) throw new IOException("invalid_keybox");
                argument = new String(input.bytes, StandardCharsets.UTF_8);
                Arrays.fill(input.bytes, (byte)0);
            }
            JSONObject status = renewal.command(command, argument);
            System.out.println(new JSONObject().put("ok", true).put("api", 1).put("command", "renewal." + command)
                    .put("data", status).put("error", JSONObject.NULL));
        } catch (Exception failure) {
            String code = Diagnostics.code(failure);
            System.out.println(new JSONObject().put("ok", false).put("api", 1).put("command", "renewal")
                    .put("data", JSONObject.NULL).put("error", new JSONObject().put("code", code)
                    .put("message", "Renewal operation stopped: " + code)));
            System.exit(1);
        }
    }
}
