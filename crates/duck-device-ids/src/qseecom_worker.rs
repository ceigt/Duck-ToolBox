//! Qualcomm calls run outside the CLI; a timeout is an unknown write outcome, never a retry.
use crate::{backend::SessionInfo, kph_process, trustkernel_worker};
use anyhow::{Result, ensure};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::{path::Path, time::Duration};

#[derive(Serialize, Deserialize)]
struct Request {
    ta_path: String,
    ta_name: String,
    command: Vec<u8>,
}

pub(crate) fn provision(
    ctx: &duck_core::Context,
    ta_path: &str,
    ta_name: &str,
    command: &[u8],
) -> Result<SessionInfo> {
    let _lock = kph_process::lock(&ctx.paths.var_dir)?;
    let runner = kph_process::Runner {
        executable: std::env::current_exe()?,
        directory: kph_process::directory(&ctx.paths.tmp_dir)?,
        timeout: Duration::from_secs(30),
    };
    trustkernel_worker::write(
        &runner.directory.path().join("request.json"),
        &Request {
            ta_path: ta_path.into(),
            ta_name: ta_name.into(),
            command: command.into(),
        },
    )?;
    let output = runner.run(&[
        "device-ids",
        "qsee-call",
        "--exchange",
        runner
            .directory
            .path()
            .to_str()
            .ok_or_else(|| anyhow::anyhow!("invalid exchange path"))?,
    ])?;
    ensure!(
        output.status.success(),
        "Qualcomm worker exited; provisioning outcome may be unknown"
    );
    let reply: Value = serde_json::from_slice(&trustkernel_worker::read(
        &runner.directory.path().join("reply.json"),
        64 * 1024,
    )?)?;
    ensure!(
        reply["ok"] == true,
        "Qualcomm worker failed; provisioning outcome may be unknown"
    );
    let text = |name| reply["session"][name].as_str().map(str::to_owned);
    Ok(SessionInfo {
        loaded_library: text("loaded_library"),
        executable: Some(runner.executable.display().to_string()),
        ta_api_version: text("ta_api_version"),
        ta_version: text("ta_version"),
        response_hex: text("response_hex"),
        ..SessionInfo::default()
    })
}

pub(crate) fn execute(directory: &Path) -> Result<()> {
    let result = (|| -> Result<SessionInfo> {
        ensure!(
            cfg!(all(target_os = "android", target_arch = "aarch64")),
            "Android arm64 required"
        );
        let request: Request = serde_json::from_slice(&trustkernel_worker::read(
            &directory.join("request.json"),
            256 * 1024,
        )?)?;
        ensure!(
            request.command.len() >= 4
                && request.command.len() <= 32 * 1024
                && request.command[..4] == 0x220au32.to_le_bytes(),
            "invalid Qualcomm request"
        );
        crate::qseecom::provision(&request.ta_path, &request.ta_name, &request.command)
    })();
    let reply = match result {
        Ok(session) => serde_json::json!({"ok":true,"session":session}),
        Err(_) => serde_json::json!({"ok":false}),
    };
    trustkernel_worker::write(&directory.join("reply.json"), &reply)
}
