package dev.duck.renewal;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Exercise the audited entry combinations with disposable certificates and no device/network. */
public final class AuditFixTest {
    static int count;
    static void check(boolean value, String name) { if (!value) throw new AssertionError(name); count++; }
    public static void main(String[] args) throws Exception {
        RenewalTest.fixtures = Paths.get(args[0]).toAbsolutePath().normalize();
        RenewalTest.temporaryRoot = Paths.get(args[1]).toAbsolutePath().normalize();
        Files.createDirectories(RenewalTest.temporaryRoot);
        String xml = new String(RenewalTest.fixture("new.xml"), StandardCharsets.UTF_8);
        RenewalTest.Case first = new RenewalTest.Case(); Files.delete(first.target);
        JSONObject installed = first.engine.command("install", xml);
        check("manual_installed".equals(installed.getString("result")) && Files.isRegularFile(first.target), "first import creates missing target");
        check(installed.getString("backup_path").isEmpty(), "first import does not claim a backup");
        RenewalTest.Case failure = new RenewalTest.Case(); failure.enable(); failure.fake.timeout = true;
        String diagnostic = failure.run().getString("diagnostic"); failure.restart();
        check(diagnostic.equals(failure.run().getString("diagnostic")), "unknown submission retains diagnostic across ticks");
        RenewalTest.Case crash = new RenewalTest.Case(); crash.fake.crashAfterSwap = true;
        String before = crash.hash();
        try { crash.engine.command("install", xml); throw new AssertionError("missing crash"); }
        catch (AssertionError e) { if (!"simulated_process_death".equals(e.getMessage())) throw e; }
        crash.restart(); check(crash.engine.status().getBoolean("recovery_required"), "interrupted import advertises recovery");
        JSONObject recovered = crash.engine.command("recover", "");
        check(!recovered.getBoolean("enabled") && !recovered.getBoolean("recovery_required") && before.equals(crash.hash()), "explicit recovery needs no renewal consent");
        RenewalTest.Case absent = new RenewalTest.Case(); Files.delete(absent.target); absent.fake.crashAfterSwap = true;
        try { absent.engine.command("install", xml); throw new AssertionError("missing crash"); }
        catch (AssertionError e) { if (!"simulated_process_death".equals(e.getMessage())) throw e; }
        absent.restart(); JSONObject absentResult = absent.engine.command("recover", "");
        check(!Files.exists(absent.target) && absentResult.getBoolean("blocked")
                && "rollback_absent_unconfirmed".equals(absentResult.getString("result")), "missing old file restored without claiming runtime proof");
        Path tee = first.base;
        JSONObject profiles = new JSONObject().put("default", new JSONObject().put("keybox", "keybox.xml").put("mode", "generation"))
                .put("second", new JSONObject().put("keybox", "keybox.xml").put("mode", "patch"));
        JSONObject config = new JSONObject().put("version", 1).put("profiles", profiles);
        check(TeeConfig.manualTarget(tee, first.target, config), "manual generation and multiple profiles allowed");
        Files.move(first.target, tee.resolve("custom.xml")); profiles.getJSONObject("default").put("keybox", "custom.xml");
        profiles.getJSONObject("second").put("keybox", "custom.xml");
        check(TeeConfig.manualTarget(tee, tee.resolve("custom.xml"), config), "manual custom filename follows config");
        for (String name : Arrays.asList("keybox.XML", "attestation", "nested/attestation")) {
            JSONObject named = new JSONObject().put("version", 1).put("profiles", new JSONObject()
                    .put("default", new JSONObject().put("keybox", name).put("mode", "patch")));
            check(TeeConfig.manualTarget(tee, tee.resolve(name), named), "manual target accepts safe name: " + name);
        }
        try { TeeConfig.keybox(tee, "../escape.xml"); throw new AssertionError("traversal"); }
        catch (IOException expected) { count++; }
        for (String unsafe : Arrays.asList("", ".", "nested/../attestation", tee.resolve("outside").toString())) {
            try { TeeConfig.keybox(tee, unsafe); throw new AssertionError("unsafe target accepted"); }
            catch (IOException expected) { count++; }
        }
        Path watchedConfig = tee.resolve("config.json");
        byte[] configBytes = config.toString().getBytes(StandardCharsets.UTF_8);
        Files.write(watchedConfig, configBytes);
        Main watcher = new Main(tee, first.root, tee);
        for (String name : Arrays.asList("keybox.XML", "attestation", "nested/attestation", "nested/keybox.xml")) {
            Files.setLastModifiedTime(watchedConfig, java.nio.file.attribute.FileTime.fromMillis(1000));
            watcher.reload(tee.resolve(name));
            check(Files.getLastModifiedTime(watchedConfig).toMillis() != 1000
                    && Arrays.equals(configBytes, Files.readAllBytes(watchedConfig)),
                    "unwatched target triggers reload without changing config: " + name);
        }
        Files.setLastModifiedTime(watchedConfig, java.nio.file.attribute.FileTime.fromMillis(1000));
        watcher.reload(tee.resolve("keybox.xml"));
        check(Files.getLastModifiedTime(watchedConfig).toMillis() == 1000,
                "watched top-level XML needs no config rewrite");
        Map<String,String> proof = TeeConfig.proof(tee, config);
        String id = Keybox.runtimeId(RenewalTest.fixture("new.xml"));
        String logs = RegressionTest.build(id,"100").replace("mode=patch", "mode=generation")
                .replace("HandleConnection: applied config epoch=100 profiles=1/1", "unused")
                + RegressionTest.build(id,"101").replace("staged profile 'default'", "staged profile 'second'")
                .replace("profiles=1/1", "profiles=2/2")
                + RegressionTest.ack("101",true).replace("applied=1", "applied=2");
        check(LoadProof.accepted(logs, proof), "multi-profile proof includes names modes ids and matching full ACK");
        check(!LoadProof.accepted(logs.replace("mode=generation", "mode=patch"), proof), "incorrect profile mode rejected");
        check(!LoadProof.accepted(logs.replace("applied=2", "applied=1"), proof), "partial ACK rejected");
        final boolean[] disconnected = {false};
        HttpURLConnection slow = new HttpURLConnection(new URL("https://example.invalid")) {
            public void connect() {} public boolean usingProxy() { return false; }
            public void disconnect() { disconnected[0] = true; }
            public int getResponseCode() { return 200; }
            public InputStream getInputStream() { return new InputStream() {
                public int read() throws IOException { try { Thread.sleep(1000); } catch (InterruptedException e) { throw new IOException(); } return ' '; }
            }; }
        };
        long start = System.nanoTime();
        try { Main.readRevocations(slow, 30); throw new AssertionError("deadline missing"); }
        catch (java.util.concurrent.TimeoutException expected) {
            check(disconnected[0] && System.nanoTime() - start < 2000000000L, "total download deadline disconnects a slow response");
        }
        System.out.println("PASS: " + count + " audit-fix assertions; no device or network.");
    }
}
