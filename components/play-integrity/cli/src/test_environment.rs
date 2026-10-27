// SPDX-License-Identifier: GPL-3.0-only
//! Small read-only context attached to a reported test; missing readings remain unknown.
use crate::{Engine, command, files};
use anyhow::Result;
use serde_json::{Value, json};
use std::{process::Command, time::Duration};
fn output(engine: &Engine, cmd: &mut Command) -> Option<String> {
    if engine.sysroot != std::path::Path::new("/") {
        return None;
    }
    let value = command::run(cmd, Duration::from_secs(1)).ok()?;
    value
        .status
        .success()
        .then(|| String::from_utf8_lossy(&value.stdout).trim().to_owned())
}
fn version(engine: &Engine, package: &str) -> Option<String> {
    let raw = output(
        engine,
        Command::new("cmd").args(["package", "list", "packages", "--show-versioncode", package]),
    )?;
    raw.lines().find_map(|line| {
        let (name, value) = line.split_once(" versionCode:")?;
        (name == format!("package:{package}") && value.parse::<u64>().is_ok()).then(|| value.into())
    })
}
pub fn capture(engine: &Engine) -> Result<Value> {
    let sdk = output(engine, Command::new("getprop").arg("ro.build.version.sdk"))
        .and_then(|v| v.parse::<u32>().ok());
    Ok(
        json!({"boot_id":files::read(&engine.device("/proc/sys/kernel/random/boot_id"))?.trim(),
        "activation":crate::activation::pending(engine)?,"system_sdk":sdk,
        "gms_version":version(engine,"com.google.android.gms"),"store_version":version(engine,"com.android.vending")}),
    )
}
