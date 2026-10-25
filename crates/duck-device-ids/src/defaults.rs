use crate::{Backend, DeviceIdsProfile, backend, default_ta_name, default_ta_path};
use duck_core::Sysroot;
use serde::Serialize;
use std::collections::BTreeMap;

#[derive(Debug, Serialize)]
pub struct Defaults {
    #[serde(flatten)]
    pub profile: DeviceIdsProfile,
    pub backend: Option<Backend>,
}

pub(crate) fn detect(properties: &BTreeMap<String, String>, root: &Sysroot) -> Defaults {
    let backend = backend::select(properties, root).ok();
    Defaults {
        profile: profile(properties, backend),
        backend,
    }
}

/// Reads the current identifiers from system properties.
pub(crate) fn profile(
    properties: &BTreeMap<String, String>,
    backend: Option<Backend>,
) -> DeviceIdsProfile {
    let first = |keys: &[&str]| {
        keys.iter()
            .find_map(|key| {
                properties
                    .get(*key)
                    .map(|s| s.trim())
                    .filter(|s| !s.is_empty())
            })
            .unwrap_or("")
            .to_owned()
    };
    let mut profile = DeviceIdsProfile {
        brand: first(&["ro.product.brand", "ro.product.vendor.brand"]),
        device: first(&["ro.product.device", "ro.product.vendor.device"]),
        product: first(&["ro.product.name", "ro.product.vendor.name"]),
        serial: first(&["ro.serialno", "ro.boot.serialno"]),
        manufacturer: first(&["ro.product.manufacturer", "ro.product.vendor.manufacturer"]),
        model: first(&["ro.product.model", "ro.product.vendor.model"]),
        imei: first(&[
            "persist.vendor.radio.imei",
            "persist.radio.imei",
            "vendor.ril.imei",
            "ril.gsm.imei",
            "ro.ril.oem.imei",
            "ro.ril.oem.imei1",
        ]),
        imei2: first(&[
            "persist.vendor.radio.imei2",
            "persist.radio.imei2",
            "vendor.ril.imei2",
            "ril.gsm.imei2",
            "ro.ril.oem.imei2",
        ]),
        meid: first(&[
            "persist.vendor.radio.meid",
            "persist.radio.meid",
            "vendor.ril.meid",
            "ro.ril.oem.meid",
        ]),
        meid2: first(&[
            "persist.vendor.radio.meid2",
            "persist.radio.meid2",
            "vendor.ril.meid2",
            "ro.ril.oem.meid2",
        ]),
        ta_name: if backend == Some(Backend::Trustkernel) {
            "keymaster".into()
        } else {
            default_ta_name()
        },
        ta_path: if backend == Some(Backend::Trustkernel) {
            "/vendor/app/t6".into()
        } else {
            default_ta_path()
        },
        dry_run: false,
        ..DeviceIdsProfile::default()
    };
    for (field, property, target) in [
        ("brand", "brand", &mut profile.brand),
        ("device", "device", &mut profile.device),
        ("product", "name", &mut profile.product),
        ("manufacturer", "manufacturer", &mut profile.manufacturer),
        ("model", "model", &mut profile.model),
    ] {
        let candidates = [
            format!("ro.product.{property}_for_attestation"),
            format!("ro.product.{property}"),
            format!("ro.product.vendor.{property}"),
        ];
        let values = candidates
            .iter()
            .filter_map(|key| {
                properties
                    .get(key)
                    .filter(|v| !v.trim().is_empty())
                    .map(|v| (key.clone(), v.trim().to_owned()))
            })
            .collect::<Vec<_>>();
        if let Some((property, value)) = values.first() {
            *target = value.clone();
            profile.identity_sources.insert(
                field.into(),
                crate::IdentitySource {
                    property: property.clone(),
                    value: value.clone(),
                    alternatives: values
                        .iter()
                        .skip(1)
                        .filter(|(_, v)| v != value)
                        .cloned()
                        .collect(),
                },
            );
        }
    }
    profile
}

#[cfg(test)]
mod renewal_tests {
    use super::*;
    #[test]
    fn identity_prefers_provisioned_values_and_keeps_conflicts() {
        let properties = BTreeMap::from([
            ("ro.product.name".into(), "OP5CF9L1".into()),
            ("ro.product.name_for_attestation".into(), "PJE110".into()),
        ]);
        let p = profile(&properties, Some(Backend::Qseecom));
        assert_eq!(p.product, "PJE110");
        assert_eq!(p.identity_sources["product"].alternatives.len(), 1);
        assert!(!p.identity_reviewed);
    }
}
