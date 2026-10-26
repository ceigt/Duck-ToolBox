// SPDX-License-Identifier: GPL-3.0-only
//! Import only an allowlisted summary of a decoded report. No token or account data is retained.
use anyhow::{Result, bail};
use serde_json::{Value, json};
pub fn summarize(payload: &Value) -> Result<Value> {
    if !payload.is_object() {
        bail!("expected a decoded integrity report object");
    }
    let device = &payload["deviceIntegrity"];
    if !device.is_object() {
        bail!("report has no deviceIntegrity object");
    }
    let labels = match device.get("deviceRecognitionVerdict") {
        None => vec![],
        Some(Value::Array(labels)) if labels.len() <= 8 && labels.iter().all(Value::is_string) => {
            labels
                .iter()
                .filter_map(Value::as_str)
                .filter(|s| {
                    [
                        "MEETS_BASIC_INTEGRITY",
                        "MEETS_DEVICE_INTEGRITY",
                        "MEETS_STRONG_INTEGRITY",
                        "MEETS_VIRTUAL_INTEGRITY",
                    ]
                    .contains(s)
                })
                .collect()
        }
        _ => bail!("invalid deviceRecognitionVerdict"),
    };
    let licensing = payload["accountDetails"]["appLicensingVerdict"]
        .as_str()
        .filter(|s| ["LICENSED", "UNLICENSED", "UNEVALUATED"].contains(s));
    let recognition = payload["appIntegrity"]["appRecognitionVerdict"]
        .as_str()
        .filter(|s| ["PLAY_RECOGNIZED", "UNRECOGNIZED_VERSION", "UNEVALUATED"].contains(s));
    let sdk = device["deviceAttributes"]["sdkVersion"]
        .as_u64()
        .filter(|s| *s <= 100);
    Ok(json!({"labels":labels,"licensing":licensing,"recognition":recognition,"sdk":sdk}))
}
pub fn observation(input: &Value) -> Result<Value> {
    let payload = input["report"]
        .get("tokenPayloadExternal")
        .unwrap_or(&input["report"]);
    let report = summarize(payload)?;
    let mut result = input.clone();
    result["app"] = payload["requestDetails"]["requestPackageName"].clone();
    if !result["app"].is_string() {
        bail!("report is missing its requesting package");
    }
    result["version"] = json!(
        payload["appIntegrity"]["versionCode"]
            .as_str()
            .unwrap_or("")
    );
    for (key, label) in [
        ("basic", "MEETS_BASIC_INTEGRITY"),
        ("device", "MEETS_DEVICE_INTEGRITY"),
        ("strong", "MEETS_STRONG_INTEGRITY"),
    ] {
        result[key] = json!(if report["labels"]
            .as_array()
            .unwrap()
            .iter()
            .any(|v| v == label)
        {
            "pass"
        } else {
            "omitted"
        });
    }
    result["report"] = report;
    Ok(result)
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn omitted_labels_are_not_failures_and_sensitive_fields_are_discarded() {
        let value=observation(&json!({"report":{"deviceIntegrity":{"deviceRecognitionVerdict":["MEETS_DEVICE_INTEGRITY"]},"requestDetails":{"requestPackageName":"example.checker","nonce":"secret"},"token":"secret"}})).unwrap();
        assert_eq!(value["device"], "pass");
        assert_eq!(value["strong"], "omitted");
        assert!(!value.to_string().contains("secret"));
        assert!(summarize(&json!({"deviceIntegrity":{"deviceRecognitionVerdict":false}})).is_err());
    }
}
