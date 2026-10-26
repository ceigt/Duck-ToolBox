use anyhow::{Result, bail};
use duck_core::Context;
use serde_json::Value;
use std::{
    io::Write,
    path::PathBuf,
    process::{Command, Stdio},
    thread,
    time::{Duration, Instant},
};

pub(super) fn install(ctx: &Context, target: &str, content: &str) -> Result<Option<PathBuf>> {
    // The helper owns the FileLock, durable backup, swap, loader proof and rollback.
    let mut child = Command::new("/system/bin/app_process")
        .args(["/", "dev.duck.renewal.Main"])
        .arg(&ctx.paths.root)
        .arg(&ctx.paths.data_root)
        .arg("install")
        .arg(target)
        .env("CLASSPATH", ctx.paths.root.join("bin/duck-renewal.dex"))
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()?;
    child
        .stdin
        .take()
        .ok_or_else(|| anyhow::anyhow!("helper input unavailable"))?
        .write_all(content.as_bytes())?;
    let deadline = Instant::now() + Duration::from_secs(120);
    while child.try_wait()?.is_none() {
        if Instant::now() >= deadline {
            let _ = child.kill();
            let _ = child.wait();
            bail!("keybox transaction interrupted; inspect renewal recovery before retrying");
        }
        thread::sleep(Duration::from_millis(100));
    }
    let output = child.wait_with_output()?;
    // Never include the helper's raw output or exception text in a native error.
    let response: Value = serde_json::from_slice(&output.stdout)
        .map_err(|_| anyhow::anyhow!("invalid keybox transaction response"))?;
    if !output.status.success() || response["ok"] != true {
        let busy = response["error"]["code"] == "operation_busy";
        bail!(if busy {
            "another keybox operation is running"
        } else {
            "keybox transaction failed; inspect renewal diagnostics"
        });
    }
    if response["data"]["result"] != "manual_installed" {
        bail!("keybox load not confirmed; inspect renewal rollback status");
    }
    Ok(response["data"]["backup_path"]
        .as_str()
        .filter(|p| !p.is_empty())
        .map(PathBuf::from))
}
