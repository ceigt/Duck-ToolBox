//! Bridge native config writes to the renewal helper's fcntl lock. Rust flock alone
//! does not exclude Java FileLock, so the child holds that lock until stdin closes.
use anyhow::{Result, bail};
use duck_core::Context;
use std::{
    io::{BufRead, BufReader, Read},
    process::{Child, Command, Stdio},
    sync::mpsc,
    thread,
    time::Duration,
};

pub(crate) struct RenewalLease(Child);

impl Drop for RenewalLease {
    fn drop(&mut self) {
        drop(self.0.stdin.take());
        // Pipe EOF releases the Java lock even if the native owner was killed.
        for _ in 0..50 {
            if self.0.try_wait().ok().flatten().is_some() {
                return;
            }
            thread::sleep(Duration::from_millis(20));
        }
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}

pub(crate) fn acquire(ctx: &Context) -> Result<Option<RenewalLease>> {
    let module = ctx.sysroot.path("/data/adb/modules/teesim");
    if !cfg!(target_os = "android")
        || ctx.sysroot.root() != std::path::Path::new("/")
        || !module.is_dir()
        || module.join("disable").exists()
        || module.join("remove").exists()
    {
        return Ok(None);
    }
    let mut child = Command::new("/system/bin/app_process")
        .args(["/", "dev.duck.renewal.Main"])
        .arg(&ctx.paths.root)
        .arg(&ctx.paths.data_root)
        .arg("config-lease")
        .env("CLASSPATH", ctx.paths.root.join("bin/duck-renewal.dex"))
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()?;
    let output = child
        .stdout
        .take()
        .ok_or_else(|| anyhow::anyhow!("config lease output missing"))?;
    let (send, receive) = mpsc::channel();
    thread::spawn(move || {
        let mut line = Vec::new();
        let result = BufReader::new(output.take(2048)).read_until(b'\n', &mut line);
        let _ = send.send(result.is_ok() && line == b"LOCKED\n");
    });
    if receive.recv_timeout(Duration::from_secs(10)).ok() != Some(true) {
        let _ = child.kill();
        let _ = child.wait();
        bail!("keystore renewal/configuration is busy or its lock helper is unavailable");
    }
    Ok(Some(RenewalLease(child)))
}
