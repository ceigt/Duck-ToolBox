package dev.duck.renewal;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Synthetic OMK watcher/identity evidence; no real module, credentials, or network. */
public final class OmkTest {
    static int count;
    static void check(boolean value, String name) { if (!value) throw new AssertionError(name); count++; }
    static void write(Path path, String text) throws Exception {
        Files.createDirectories(path.getParent()); Files.write(path, text.getBytes(StandardCharsets.UTF_8));
    }
    static class Fixture {
        final Path root, module, tee, log, pid;
        final Omk omk;
        final String id;
        Fixture() throws Exception {
            root = Files.createTempDirectory(RenewalTest.temporaryRoot, "omk-");
            module = root.resolve("data/adb/modules/duck-toolbox"); Files.createDirectories(module);
            tee = root.resolve("data/misc/keystore/omk"); log = tee.resolve("logs/keymint.log");
            write(root.resolve("data/adb/modules/oh_my_keymint/module.prop"), "id=oh_my_keymint\nversionCode=222\n");
            write(tee.resolve("config.toml"), "[trust]\nos_version=\"auto\"\n");
            write(tee.resolve("injector.toml"), "scoop=[]\n"); write(log, "old log\n");
            Files.write(tee.resolve("keybox.xml"), RenewalTest.fixture("old.xml"));
            pid = root.resolve("proc/111");
            write(pid.resolve("cmdline"), root.resolve("data/adb/modules/oh_my_keymint/libs/arm64-v8a/keymint") + "\0");
            write(pid.resolve("stat"), "111 (keymint helper) S " + String.join(" ", Collections.nCopies(18,"0")) + " 123\n");
            omk = new Omk(module, root.resolve("data/adb/duck-toolbox"), root);
            id = Keybox.runtimeId(RenewalTest.fixture("new.xml"));
        }
        void publish() throws Exception {
            Files.write(tee.resolve("keybox.xml"), RenewalTest.fixture("new.xml"));
            String line = "2026-10-27 03:00:00 UTC [INFO] keymint::keybox - active keybox identity updated from "
                    + tee.resolve("keybox.xml") + " (fallback=false)\n";
            Files.write(log, line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        }
    }
    public static void main(String[] args) throws Exception {
        RenewalTest.fixtures = Paths.get(args[0]).toAbsolutePath().normalize();
        RenewalTest.temporaryRoot = Paths.get(args[1]).toAbsolutePath().normalize();
        Files.createDirectories(RenewalTest.temporaryRoot);
        Fixture good = new Fixture(); check(good.omk.supported(), "OMK 222 fixed config supported");
        Object cursor = good.omk.cursor(); good.publish();
        check(good.omk.accepted(good.id, cursor), "fresh watcher success plus unchanged process config and file accepted");
        check(!good.omk.manualSupported(), "OMK native manual import stays independent");
        Files.createDirectories(good.root.resolve("data/adb/modules/teesim"));
        check(!good.omk.supported(), "two active backends rejected");
        write(good.root.resolve("data/adb/modules/teesim/disable"), "");
        check(good.omk.supported(), "disabled previous backend is allowed");
        write(good.root.resolve("data/adb/modules/oh_my_keymint/module.prop"), "versionCode=221\n");
        check(!good.omk.supported(), "older unverified OMK version rejected");
        Fixture changed = new Fixture(); cursor = changed.omk.cursor(); changed.publish();
        write(changed.tee.resolve("config.toml"), "changed");
        check(!changed.omk.accepted(changed.id, cursor), "changed configuration rejects load proof");
        Fixture process = new Fixture(); cursor = process.omk.cursor(); process.publish();
        write(process.pid.resolve("stat"), "111 (keymint helper) S " + String.join(" ", Collections.nCopies(18,"0")) + " 124\n");
        check(!process.omk.accepted(process.id, cursor), "PID reuse or process restart rejects load proof");
        Fixture rotated = new Fixture(); cursor = rotated.omk.cursor(); rotated.publish();
        Files.move(rotated.log, rotated.log.resolveSibling("keymint.log.1")); write(rotated.log, "rotated\n");
        check(!rotated.omk.accepted(rotated.id, cursor), "log rotation rejects stale cursor");
        check(!Omk.loaded("active keybox identity updated from /wrong (fallback=false)", "/target"), "unattributed line rejected");
        String ok = "[INFO] keymint::keybox - active keybox identity updated from /target (fallback=false)\n";
        check(Omk.loaded(ok, "/target"), "expected watcher message recognized");
        check(!Omk.loaded(ok + "[WARN] keymint::keybox - invalid keybox.xml\n", "/target"), "fallback invalidates success");
        check(!Omk.loaded(ok + "[INFO] keymint::keybox - reload (fallback=true)\n", "/target"), "bundled fallback rejected");
        RenewalTest.Case migration = new RenewalTest.Case(); migration.enable();
        migration.fake.backend = "ohmykeymint"; migration.restart();
        JSONObject state = migration.run();
        check(!state.getBoolean("enabled") && "backend_confirmation_required".equals(state.getString("result"))
                && migration.fake.requests == 0, "old backend consent cannot issue or install on OMK");
        migration.engine.command("enable", "v1");
        check(migration.engine.status().getBoolean("enabled"), "explicit OMK enable binds consent to backend");
        check("renewed".equals(migration.run().getString("result")), "OMK bound state follows existing complete renewal transaction");
        RenewalTest.Case pending = new RenewalTest.Case(); pending.enable(); pending.fake.crlFailure = true; pending.run();
        pending.fake.backend = "ohmykeymint"; pending.restart();
        try { pending.engine.command("enable", "v2"); throw new AssertionError("pending crossed backend"); }
        catch (IOException expected) { check("backend_changed".equals(expected.getMessage()), "pending candidate cannot silently cross backends"); }
        RenewalTest.Case recovery = new RenewalTest.Case(); recovery.fake.crashAfterSwap = true;
        try { recovery.engine.command("install", new String(RenewalTest.fixture("new.xml"), StandardCharsets.UTF_8)); }
        catch (AssertionError expected) { /* Durable transaction remains. */ }
        recovery.fake.backend = "ohmykeymint"; recovery.restart(); String before = recovery.hash();
        try { recovery.engine.command("recover", ""); throw new AssertionError("recovery crossed backend"); }
        catch (IOException expected) { check(before.equals(recovery.hash()) && "backend_changed".equals(expected.getMessage()), "old transaction cannot overwrite the new backend"); }
        System.out.println("PASS: " + count + " OMK assertions; synthetic files only, no device/network.");
    }
}
