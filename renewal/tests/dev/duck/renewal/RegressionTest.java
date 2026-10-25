package dev.duck.renewal;

import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.json.*;

public final class RegressionTest {
    static int count;
    static void check(boolean ok, String name) { if (!ok) throw new AssertionError(name); count++; }
    static String nativeLine(String message) { return "10-23 10:00:00.001  100  101 I TEESim: " + message + "\n"; }
    static String ack(String epoch, boolean ok) { return "10-23 10:00:00.002  200  201 I Teesim: Control: ack epoch="+epoch+" ok=true applied="+(ok?1:0)+" failed="+(ok?0:1)+"\n"; }
    static String build(String id, String epoch) {
        return nativeLine("CertSignInfo::new: keybox parsed (rsa=false, ec=true) id="+id)
            + nativeLine("CertSignInfo::new: keybox parsed (id="+id+", chain already logged)")
            + nativeLine("teesim_cfg_add_profile: staged profile 'default' (mode=patch, security_level=TEE, 51 package(s), 51 uid(s))")
            + nativeLine("HandleConnection: applied config epoch="+epoch+" profiles=1/1");
    }
    public static void main(String[] args) throws Exception {
        RenewalTest.fixtures = Paths.get(args[0]).toAbsolutePath().normalize();
        RenewalTest.temporaryRoot = Paths.get(args[1]).toAbsolutePath().normalize();
        Files.createDirectories(RenewalTest.temporaryRoot);
        String javaTool = Paths.get(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        ManualKeybox.validate(Files.readAllBytes(RenewalTest.fixtures.resolve("aosp.xml")), 1735689600000L);
        check(true, "legacy AOSP key and certificate consistency");
        Path leaseRoot=RenewalTest.temporaryRoot.resolve("lease");
        Files.createDirectories(leaseRoot);
        PipedInputStream leaseInput=new PipedInputStream(); PipedOutputStream leaseOwner=new PipedOutputStream(leaseInput);
        java.util.concurrent.CountDownLatch ready=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> leaseError=new java.util.concurrent.atomic.AtomicReference<>();
        Thread lease=new Thread(() -> {try {ConfigLease.hold(leaseRoot,leaseInput,new OutputStream(){public void write(int b){} public void flush(){ready.countDown();}});}catch(Throwable error){leaseError.set(error);}});
        lease.setDaemon(true); lease.start(); check(ready.await(5,java.util.concurrent.TimeUnit.SECONDS),"config lease ready");
        Process leaseProbe = new ProcessBuilder(javaTool, "-cp", System.getProperty("java.class.path"),
                "dev.duck.renewal.LockProbe", leaseRoot.toString()).redirectErrorStream(true).start();
        leaseProbe.getOutputStream().close();
        if (!leaseProbe.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) { leaseProbe.destroyForcibly(); throw new AssertionError("lease probe timed out"); }
        try (BufferedReader output = new BufferedReader(new InputStreamReader(leaseProbe.getInputStream(), StandardCharsets.UTF_8))) {
            check(leaseProbe.exitValue() == 0 && "operation_busy".equals(output.readLine()), "separate config writer obeys renewal lock");
        }
        try(FileChannel channel=FileChannel.open(leaseRoot.resolve("var/renewal/operation.lock"),StandardOpenOption.WRITE)) {
            try {channel.tryLock();throw new AssertionError("lease ignored");}catch(OverlappingFileLockException expected){check(true,"config lease shares renewal lock");}
        } finally {leaseOwner.close(); lease.join(5000);}
        check(!lease.isAlive() && leaseError.get()==null,"owner EOF releases config lease");
        try(FileChannel channel=FileChannel.open(leaseRoot.resolve("var/renewal/operation.lock"),StandardOpenOption.WRITE);FileLock lock=channel.tryLock()){check(lock!=null,"renewal can lock after config writer exits");}
        String id="0123456789abcdef", other="fedcba9876543210";
        check(Main.hasAck(build(id,"100")+ack("100",true),id), "same-thread build and matching commit ACK");
        check(!Main.hasAck(build(id,"100")+ack("101",true),id), "wrong commit epoch rejected");
        check(!Main.hasAck(build(id,"100")+ack("100",false)+build(other,"101")+ack("101",true),id), "failed new / successful old rejected");
        check(!Main.hasAck(build(id,"100").replace("100  101 I TEESim: Handle", "100  102 I TEESim: Handle")+ack("100",true),id), "different native thread rejected");
        check(!Main.hasAck(build(id,"100")+ack("100",true)+nativeLine("CertSignInfo::new: keybox parsed id="+other),id), "subsequent different config invalidates proof");
        check(!Main.hasAck(build(id,"100")+nativeLine("chatty: dropped 12 lines")+ack("100",true),id), "lost log evidence rejected");
        check(!Main.hasAck(nativeLine("CertSignInfo::new: keybox parsed id="+id)+ack("100",true),id), "parse plus unrelated ACK insufficient");

        RenewalTest.Case pending = new RenewalTest.Case(); pending.enable(); pending.fake.crlFailure=true; pending.run();
        String before=pending.hash(); int attempts=pending.engine.state.getJSONArray("attempts").length();
        long cooldown=pending.engine.state.getLong("retry_after");
        pending.engine.command("discard", "");
        check(pending.hash().equals(before) && !pending.engine.state.has("pending"), "discard preserves live bytes");
        check(pending.engine.state.getJSONArray("attempts").length()==attempts && pending.engine.state.getLong("retry_after")==cooldown, "discard preserves limits");
        pending.engine.command("protocol", "v1"); check("v1".equals(pending.engine.state.getString("protocol")), "discard unlocks protocol");

        RenewalTest.Case expired = new RenewalTest.Case(); expired.enable(); expired.fake.crlFailure=true; expired.run();
        expired.fake.time=expired.engine.state.getJSONObject("pending").getLong("expires")+1000;
        check("pending_expired".equals(expired.run().getString("result")), "fully expired pending is actionable");
        expired.engine.command("discard", ""); check(!expired.engine.state.has("pending"), "expired pending discarded");

        RenewalTest.Case clock = new RenewalTest.Case(); clock.enable(); clock.run(); clock.fake.time-=2*Renewal.HOUR;
        clock.run(); clock.engine.command("resume", "");
        check(!"clock_invalid".equals(clock.run().getString("result")), "corrected clock resumes");
        check(clock.engine.state.getLong("retry_after")>=clock.fake.time+Renewal.LEAD && clock.fake.requests==1, "clock recovery retains conservative cooldown");

        RenewalTest.Case manual = new RenewalTest.Case(); manual.enable(); String original=manual.hash();
        String xml=new String(RenewalTest.fixture("new.xml"),StandardCharsets.UTF_8);
        try (FileChannel channel=FileChannel.open(manual.root.resolve("operation.lock"),StandardOpenOption.WRITE); FileLock lock=channel.lock()) {
            Process child = new ProcessBuilder(javaTool, "-cp", System.getProperty("java.class.path"),
                    "dev.duck.renewal.LockProbe", manual.root.toString(), manual.outputs.toString(),
                    manual.target.toString(), RenewalTest.fixtures.toString()).redirectErrorStream(true).start();
            if (!child.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) { child.destroyForcibly(); throw new AssertionError("lock probe timed out"); }
            try (BufferedReader output = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                check(child.exitValue() == 0 && "operation_busy".equals(output.readLine()), "separate installer process obeys renewal lock");
            }
            // POSIX closes can release this JVM's OS locks; probe the other process first.
            try { manual.engine.command("install",xml); throw new AssertionError("manual ignored renewal lock"); }
            catch(IOException expected) { check("operation_busy".equals(expected.getMessage()), "manual uses renewal lock"); }
        }
        try { manual.engine.command("install","<AndroidAttestation>bad</AndroidAttestation>"); throw new AssertionError("bad input accepted"); }
        catch(Exception expected) { check(manual.hash().equals(original), "invalid manual keybox leaves current file intact"); }
        JSONObject installed=manual.engine.command("install",xml);
        check("manual_installed".equals(installed.getString("result")), "manual transaction confirms load");
        check(Files.exists(Paths.get(installed.getString("backup_path"))), "manual backup retained");
        Files.write(manual.target, "broken".getBytes(StandardCharsets.UTF_8));
        check(!manual.engine.command("status", "").getBoolean("certificate_valid"), "changed file invalidates display cache");

        RenewalTest.Case crash=new RenewalTest.Case(); crash.enable(); crash.fake.crashAfterSwap=true;
        try { crash.engine.command("install",xml); throw new AssertionError("crash missing"); }
        catch(AssertionError expected) { if(!"simulated_process_death".equals(expected.getMessage())) throw expected; }
        crash.restart(); crash.run(); check(crash.hash().equals(original), "manual crash recovered by shared journal");

        RenewalTest.Case missing=new RenewalTest.Case(); missing.enable(); missing.run();
        JSONObject owner=missing.engine.state.getJSONArray("owned").getJSONObject(0);
        Path run=Paths.get(owner.getString("path"));
        for(String name:new String[]{"keybox.xml","private_key.pem","csr.cbor","cert_chain.der"}) Files.delete(run.resolve(name));
        Files.delete(run); missing.fake.time=owner.getLong("expires")+1000;
        java.lang.reflect.Method cleanup=Renewal.class.getDeclaredMethod("cleanup",Keybox.class);cleanup.setAccessible(true);
        cleanup.invoke(missing.engine,Keybox.parse(RenewalTest.fixture("old.xml"),missing.pins,missing.fake.time,false));
        check(!missing.engine.state.getJSONArray("owned").toString().contains(run.toString().replace("\\","\\\\")), "missing run removed from ownership manifest");
        check(!Diagnostics.code(new IOException("TEST_PRIVATE_SECRET")).contains("SECRET"), "diagnostics redact raw errors");
        System.out.println("PASS: "+count+" regression assertions; synthetic fixtures only.");
    }
}
