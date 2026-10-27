package dev.duck.renewal;

import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.*;

public final class RenewalTest {
    static Path fixtures;
    static Path temporaryRoot;
    static int count;
    static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        count++;
    }
    static byte[] fixture(String name) throws Exception { return Files.readAllBytes(fixtures.resolve(name)); }
    static class Fake implements Renewal.Platform {
        long time = System.currentTimeMillis() + 70 * Renewal.HOUR;
        int requests, crls, acknowledgements;
        boolean online = true, supported = true, crlFailure, timeout, crashAfterSwap, refuseNew, revoked;
        String failure;
        String backend = "teesimulator";
        Path outputs;
        public long now() { return time; }
        public String backend() { return backend; }
        public boolean supported() { return supported; }
        public boolean online() { return online; }
        public JSONObject issue(String protocol) throws Exception {
            requests++;
            if (timeout) throw new IOException("TEST_SENSITIVE_MESSAGE_DO_NOT_REPORT");
            if (failure != null) return new JSONObject().put("ok", false).put("error", new JSONObject().put("code", failure));
            Path run = outputs.resolve("rkp-device-test-" + requests); Files.createDirectories(run);
            Files.write(run.resolve("keybox.xml"), fixture("new.xml"));
            for (String name : Arrays.asList("private_key.pem", "csr.cbor", "cert_chain.der")) Files.write(run.resolve(name), new byte[]{1,2,3});
            return new JSONObject().put("ok", true).put("data", new JSONObject().put("mode", "device-ta-oracle")
                    .put("keybox_xml", "TEST_PRIVATE_RESPONSE_NOT_FOR_STATUS")
                    .put("artifacts", new JSONObject().put("run_dir", run.toString()).put("keybox_path", run.resolve("keybox.xml").toString())));
        }
        public JSONObject revocations() throws Exception {
            crls++; if (crlFailure) throw new IOException("offline");
            if (revoked) return new JSONObject().put("entries", new JSONObject().put(Keybox.parse(fixture("new.xml"),
                    Keybox.pins(fixtures.resolve("roots.pem")), time, true).chain.get(0).getSerialNumber().toString(16),
                    new JSONObject().put("status", "REVOKED")));
            return new JSONObject().put("entries", new JSONObject().put("ff", new JSONObject().put("status", "REVOKED")));
        }
        public Object cursor() { return acknowledgements; }
        public boolean accepted(String id, Object cursor) {
            acknowledgements++;
            if (crashAfterSwap) { crashAfterSwap = false; throw new AssertionError("simulated_process_death"); }
            return !refuseNew || acknowledgements > 1;
        }
    }
    static class Case {
        Path base, target, root, outputs;
        Set<String> pins;
        Fake fake = new Fake();
        Renewal engine;
        Case() throws Exception {
            base = (temporaryRoot == null ? Files.createTempDirectory("duck-renewal-test-")
                    : Files.createTempDirectory(temporaryRoot, "duck-renewal-test-")).toAbsolutePath().normalize();
            root = base.resolve("state"); outputs = base.resolve("outputs"); target = base.resolve("keybox.xml");
            Files.createDirectories(outputs); Files.write(target, fixture("old.xml"));
            fake.outputs = outputs;
            pins = Keybox.pins(fixtures.resolve("roots.pem")); restart();
        }
        void restart() throws Exception { engine = new Renewal(root, outputs, target, pins, fake); }
        JSONObject run() throws Exception { return engine.command("tick", ""); }
        void enable() throws Exception { engine.command("enable", "v2"); }
        String hash() throws Exception { return Keybox.sha256(Files.readAllBytes(target)); }
    }

    public static void main(String[] args) throws Exception {
        fixtures = Paths.get(args[0]).toAbsolutePath().normalize();
        if (args.length > 1) { temporaryRoot = Paths.get(args[1]); Files.createDirectories(temporaryRoot); }
        Set<String> pins = Keybox.pins(fixtures.resolve("roots.pem"));
        long now = System.currentTimeMillis() + Renewal.HOUR;
        Keybox old = Keybox.parse(fixture("old.xml"), pins, now, true);
        Keybox next = Keybox.parse(fixture("new.xml"), pins, now, true);
        check(next.expires > old.expires, "fixtures ordered");
        check(Keybox.parse(fixture("sec1.xml"), pins, now, true).chain.size() == 2, "SEC1 private key matching");
        check(Keybox.parse(fixture("short.xml"), pins, now, true).expires < old.expires, "earliest intermediate expiry");
        try { Keybox.parse(fixture("mismatch.xml"), pins, now, true); throw new AssertionError("key mismatch accepted"); }
        catch (java.security.GeneralSecurityException | IOException expected) { count++; }
        try { Keybox.parse(fixture("new.xml"), Collections.singleton("wrong"), now, true); throw new AssertionError("wrong pin accepted"); }
        catch (IOException expected) { count++; }
        try { Keybox.parse("<!DOCTYPE a [<!ENTITY x SYSTEM 'file:///private'>]><a>&x;</a>".getBytes(StandardCharsets.UTF_8), pins, now, true); throw new AssertionError("DTD accepted"); }
        catch (IOException expected) { count++; }
        try { Keybox.safePath(fixtures.resolve("../escape")); throw new AssertionError("traversal accepted"); }
        catch (IOException expected) { count++; }
        String id = "0123456789abcdef";
        check(!Main.hasAck("CertSignInfo::new: keybox parsed (rsa=false, ec=true) id="+id+"\ndmn Control: ack epoch=123 ok=true applied=1 failed=0\n", id), "unattributed log evidence rejected");
        check(!Main.hasAck("Control: ack epoch=123 ok=true applied=1 failed=0\nCertSignInfo::new: keybox parsed id="+id, id), "ack before parse rejected");
        check(!Main.hasAck("CertSignInfo::new: keybox parsed id="+id+"\nControl: ack epoch=123 ok=false applied=0 failed=1", id), "failed ack rejected");

        Case symlink = new Case();
        Path link = symlink.base.resolve("symlink.xml");
        boolean linkCreated = false;
        try { Files.createSymbolicLink(link, symlink.target); linkCreated = true; }
        catch (UnsupportedOperationException | FileSystemException ignored) { /* Windows needs a privilege; Android exercises this. */ }
        if (linkCreated) {
            try { Keybox.readFile(link, 128 * 1024); throw new AssertionError("symlink accepted"); }
            catch (IOException expected) { count++; }
        }

        Case off = new Case();
        check(!off.engine.command("status", "").getBoolean("enabled") && !Files.exists(off.root), "status is read-only and default off");
        off.run(); check(off.fake.requests == 0 && off.fake.crls == 0, "disabled makes no requests");
        JSONObject offSaved = off.engine.command("protocol", "v1");
        check(!offSaved.getBoolean("enabled") && offSaved.getBoolean("protocol_configured"), "saving protocol does not enable renewal");
        off.restart();
        check("v1".equals(off.engine.status().getString("protocol")), "saved protocol survives restart");
        check(off.fake.requests == 0 && off.fake.crls == 0 && off.hash().equals(old.hash), "saving protocol is local and preserves certificate");

        Case select = new Case(); select.enable();
        select.engine.state.put("retry_after", select.fake.time + Renewal.CHECK)
                .put("attempts", new JSONArray().put(select.fake.time));
        Renewal.atomic(select.root.resolve("state.json"), select.engine.state.toString().getBytes(StandardCharsets.UTF_8));
        JSONObject selected = select.engine.command("protocol", "v1");
        check(selected.getBoolean("enabled") && "v1".equals(selected.getString("protocol")), "enabled renewal allows changing protocol");
        check(select.engine.state.getLong("retry_after") == select.fake.time + Renewal.CHECK
                && select.engine.state.getJSONArray("attempts").length() == 1 && select.fake.requests == 0, "protocol change preserves limits without issuance");
        try { select.engine.command("protocol", "auto"); throw new AssertionError("fallback protocol accepted"); }
        catch (IOException expected) { count++; }
        select.engine.state.put("phase", "pending").put("pending", new JSONObject());
        Renewal.atomic(select.root.resolve("state.json"), select.engine.state.toString().getBytes(StandardCharsets.UTF_8));
        try { select.engine.command("protocol", "v2"); throw new AssertionError("pending protocol changed"); }
        catch (IOException expected) { count++; }
        check("v1".equals(select.engine.load().getString("protocol")), "pending rejection preserves saved interface");

        Case boundary = new Case(); boundary.enable();
        boundary.fake.time = old.expires - Renewal.LEAD - 1;
        check("not_due".equals(boundary.run().getString("result")) && boundary.fake.requests == 0, "not before lead window");
        boundary.fake.time++;
        JSONObject renewed = boundary.run();
        check("renewed".equals(renewed.getString("result")) && boundary.fake.requests == 1 && boundary.fake.crls == 1, "inclusive 24h boundary renews once");
        check(boundary.hash().equals(next.hash), "new exact bytes installed");
        check(!renewed.toString().contains("PRIVATE"), "no sensitive child response in status");
        boundary.run(); check(boundary.fake.requests == 1 && boundary.fake.crls == 1, "ordinary checks offline after renewal");

        Case offline = new Case(); offline.enable(); offline.fake.online = false;
        check("offline".equals(offline.run().getString("result")) && offline.fake.requests == 0 && offline.hash().equals(old.hash), "offline preserves file without request");

        Case pending = new Case(); pending.enable(); pending.fake.crlFailure = true;
        check("revocation_unavailable".equals(pending.run().getString("result")) && pending.fake.requests == 1 && pending.hash().equals(old.hash), "pending on CRL failure");
        pending.restart(); pending.run(); check(pending.fake.requests == 1 && pending.fake.crls == 1, "pending cooldown after restart");
        pending.fake.time += Renewal.CHECK + 1000; pending.fake.crlFailure = false;
        check("renewed".equals(pending.run().getString("result")) && pending.fake.requests == 1, "reuse pending without another signature request");

        Case revoked = new Case(); revoked.enable(); revoked.fake.revoked = true;
        check("new_certificate_revoked".equals(revoked.run().getString("result")) && revoked.hash().equals(old.hash)
                && revoked.fake.acknowledgements == 0, "revoked new certificate never installed");

        Case timeout = new Case(); timeout.enable(); timeout.fake.timeout = true;
        check(timeout.run().getBoolean("blocked") && timeout.hash().equals(old.hash), "uncertain submission pauses and preserves file");
        timeout.restart(); timeout.fake.time += 25 * Renewal.HOUR; timeout.run();
        check(timeout.fake.requests == 1, "uncertain submission never auto-retried across reboot");

        Case rate = new Case(); rate.enable(); rate.fake.failure = "challenge_unavailable";
        rate.run(); rate.run(); check(rate.fake.requests == 1, "cooldown blocks repeated checks");
        rate.fake.time += Renewal.CHECK + 1000; rate.run();
        rate.fake.time += Renewal.CHECK + 1000; rate.run();
        check(rate.fake.requests == 2, "two attempts per rolling day");

        Case rollback = new Case(); rollback.enable(); rollback.fake.refuseNew = true;
        JSONObject rolledBack = rollback.run();
        check("rolled_back".equals(rolledBack.getString("result")) && rolledBack.getBoolean("blocked") && rollback.hash().equals(old.hash), "negative ack restores old bytes and pauses");
        rollback.fake.time += 25 * Renewal.HOUR; rollback.run();
        check(rollback.fake.requests == 1, "failed apply never signs a second keybox");

        Case crash = new Case(); crash.enable(); crash.fake.crashAfterSwap = true;
        try { crash.run(); throw new AssertionError("crash not reached"); }
        catch (AssertionError expected) { if (!"simulated_process_death".equals(expected.getMessage())) throw expected; }
        check(crash.hash().equals(next.hash), "crash happened after swap");
        crash.restart(); crash.run();
        check(crash.hash().equals(old.hash) && crash.fake.requests == 1 && crash.engine.state.getBoolean("blocked"), "interrupted transaction recovers exact old bytes without issuance");

        Case changed = new Case(); changed.enable(); changed.fake.crlFailure = true; changed.run();
        Files.write(changed.target, fixture("sec1.xml")); changed.fake.crlFailure = false; changed.fake.time += Renewal.CHECK + 1000;
        check("target_changed".equals(changed.run().getString("result")) && changed.fake.requests == 1, "manual replacement not overwritten");

        Case cleanup = new Case(); cleanup.enable(); cleanup.run();
        Path userFile = cleanup.root.resolve("backups/user-created.xml"); Files.write(userFile, fixture("old.xml"));
        Path backup = Paths.get(cleanup.engine.state.getJSONArray("owned").getJSONObject(1).getString("path"));
        check(Files.exists(backup), "unexpired old backup retained");
        cleanup.fake.time = old.expires + 1000; cleanup.run();
        check(!Files.exists(backup) && Files.exists(userFile) && cleanup.hash().equals(next.hash), "only manifest-owned expired backup deleted");

        Case altered = new Case(); altered.enable(); altered.run();
        Path alteredBackup = Paths.get(altered.engine.state.getJSONArray("owned").getJSONObject(1).getString("path"));
        Files.write(alteredBackup, new byte[]{9}); altered.fake.time = old.expires + 1000; altered.run();
        check(Files.exists(alteredBackup), "changed backup never deleted automatically");

        Case locked = new Case(); locked.enable();
        try (FileChannel channel = FileChannel.open(locked.root.resolve("operation.lock"), StandardOpenOption.WRITE); FileLock lock = channel.lock()) {
            try { locked.run(); throw new AssertionError("concurrent lock ignored"); }
            catch (IOException expected) { count++; }
        }
        check(locked.fake.requests == 0, "lock prevents duplicate issuance");
        Case clock = new Case(); clock.enable(); clock.run(); clock.fake.time -= 2 * Renewal.HOUR;
        check("clock_invalid".equals(clock.run().getString("result")), "clock rollback pauses");
        System.out.println("PASS: " + count + " offline renewal assertions; no device or network used.");
    }
}
