package dev.duck.renewal;

import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

/** A separate JVM enters the same install command used by the native bridge. */
public final class LockProbe {
    public static void main(String[] args) throws Exception {
        if (args.length == 1) {
            try { ConfigLease.hold(Paths.get(args[0]), System.in, System.out); }
            catch (IOException failure) { System.out.println(Diagnostics.code(failure)); }
            return;
        }
        RenewalTest.fixtures = Paths.get(args[3]);
        RenewalTest.Fake platform = new RenewalTest.Fake(); platform.outputs = Paths.get(args[1]);
        Renewal engine = new Renewal(Paths.get(args[0]), platform.outputs, Paths.get(args[2]),
                Keybox.pins(RenewalTest.fixtures.resolve("roots.pem")), platform);
        try {
            engine.command("install", new String(RenewalTest.fixture("new.xml"), StandardCharsets.UTF_8));
            System.out.println("unexpected_success");
        } catch (IOException failure) { System.out.println(Diagnostics.code(failure)); }
    }
}
