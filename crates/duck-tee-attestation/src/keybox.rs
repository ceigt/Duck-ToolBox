use std::{collections::HashSet, fmt::Write, time::SystemTime};

use anyhow::{Result, ensure};
use clap::ValueEnum;
use rcgen::{KeyPair, PublicKeyData};
use rustls::pki_types::PrivateKeyDer;
use serde::{Deserialize, Serialize};
use x509_cert::{
    Certificate,
    der::{AnyRef, Decode, Encode},
};
use zeroize::Zeroizing;

use crate::{error::MaterialError, files, pem, xml};

#[derive(Debug, Clone, Copy, Default, Deserialize, ValueEnum)]
#[serde(rename_all = "kebab-case")]
pub enum Format {
    #[default]
    Auto,
    Xml,
    Pem,
}

pub(crate) struct Material {
    pub algorithm: &'static str,
    pub private: Zeroizing<String>,
    pub public: Vec<u8>,
    pub certificates: Vec<Certificate>,
}

pub(crate) struct Keybox {
    pub device_id: String,
    pub keys: Vec<Material>,
}

#[derive(Serialize)]
pub(crate) struct KeySummary {
    pub algorithm: &'static str,
    pub certificates: usize,
    pub public_key_sha256: String,
    pub valid_now: bool,
}

#[derive(Serialize)]
pub(crate) struct Report {
    pub input_format: &'static str,
    pub source_sha256: String,
    pub keyboxes: usize,
    pub keys: Vec<KeySummary>,
    pub certificate_signatures_valid: bool,
    pub trust_verified: bool,
    pub revocation_checked: bool,
}

/// Local consistency check for installers. A self-signed root is not Google trust.
pub fn validate_install(bytes: &[u8]) -> Result<()> {
    validate_install_at(bytes, SystemTime::now())
}

fn validate_install_at(bytes: &[u8], now: SystemTime) -> Result<()> {
    let boxes = xml::parse_install(bytes)?;
    for keybox in boxes {
        let mut algorithms = HashSet::new();
        for key in keybox.keys {
            ensure!(algorithms.insert(key.algorithm), "duplicate key algorithm");
            // Tricky Store's Unknown format intentionally carries an uncertified RSA key.
            if key.certificates.is_empty() && key.algorithm == "rsa" {
                continue;
            }
            let ordered = ordered_chain(&key.public, &key.certificates)?;
            ensure!(
                ordered == key.certificates,
                "certificate order or key mismatch"
            );
            ensure!(
                ordered.iter().all(|cert| {
                    let v = cert.tbs_certificate().validity();
                    v.not_before.to_system_time() <= now && now <= v.not_after.to_system_time()
                }),
                "certificate is not currently valid"
            );
        }
    }
    Ok(())
}
pub(crate) fn extract(bytes: &[u8], format: Format) -> Result<(Zeroizing<String>, Report)> {
    let xml_start = xml::find_start(bytes);
    let (mut boxes, input_format) = match format {
        Format::Xml => (xml::parse(bytes)?, "xml"),
        Format::Auto if xml_start.is_some() => (xml::parse(bytes)?, "xml"),
        Format::Auto | Format::Pem => (vec![from_pem(bytes)?], "pem"),
    };
    let mut summaries = Vec::new();
    for keybox in &mut boxes {
        let mut algorithms = HashSet::new();
        for key in &mut keybox.keys {
            if !algorithms.insert(key.algorithm) {
                return Err(MaterialError::Keybox("duplicate key algorithm in one keybox").into());
            }
            let ordered = ordered_chain(&key.public, &key.certificates)?;
            if ordered.len() != key.certificates.len() {
                return Err(MaterialError::Keybox(
                    "keybox contains unrelated or repeated certificates",
                )
                .into());
            }
            key.certificates = ordered;
            summaries.push(KeySummary {
                algorithm: key.algorithm,
                certificates: key.certificates.len(),
                public_key_sha256: files::digest(&key.public),
                valid_now: key.certificates.iter().all(|cert| {
                    let validity = cert.tbs_certificate().validity();
                    let now = SystemTime::now();
                    validity.not_before.to_system_time() <= now
                        && now <= validity.not_after.to_system_time()
                }),
            });
        }
    }
    let report = Report {
        input_format,
        source_sha256: files::digest(bytes),
        keyboxes: boxes.len(),
        keys: summaries,
        certificate_signatures_valid: true,
        trust_verified: false,
        revocation_checked: false,
    };
    Ok((render(&boxes)?, report))
}

pub(crate) fn material(
    private: Zeroizing<String>,
    certificates: Vec<Certificate>,
) -> Result<Material> {
    let blocks = pem::blocks(private.as_bytes())?;
    let [block] = blocks.as_slice() else {
        return Err(MaterialError::Keybox("expected exactly one private key PEM block").into());
    };
    let der = PrivateKeyDer::try_from(block.der.as_slice())
        .map_err(|_| MaterialError::Keybox("invalid private key encoding"))?;
    let label = match &der {
        PrivateKeyDer::Pkcs1(_) => "RSA PRIVATE KEY",
        PrivateKeyDer::Sec1(_) => "EC PRIVATE KEY",
        PrivateKeyDer::Pkcs8(_) => "PRIVATE KEY",
        _ => return Err(MaterialError::Keybox("unsupported private key encoding").into()),
    };
    if block.label != label {
        return Err(
            MaterialError::Keybox("private key PEM label does not match its DER encoding").into(),
        );
    }
    // AOSP's legacy software key is RSA-1024, which rcgen cannot import.
    let pair = match KeyPair::try_from(&der) {
        Ok(pair) => pair,
        Err(_) => {
            let public = super::legacy_rsa::public_key(&der)
                .map_err(|_| MaterialError::Keybox("unsupported or malformed private key"))?;
            return Ok(Material {
                algorithm: "rsa",
                private,
                public,
                certificates,
            });
        }
    };
    let algorithm = match pair.algorithm() {
        alg if alg == &rcgen::PKCS_RSA_SHA256 => "rsa",
        alg if alg == &rcgen::PKCS_ECDSA_P256_SHA256 || alg == &rcgen::PKCS_ECDSA_P384_SHA384 => {
            "ecdsa"
        }
        _ => return Err(MaterialError::Keybox("unsupported keybox private key algorithm").into()),
    };
    Ok(Material {
        algorithm,
        private,
        public: pair.subject_public_key_info(),
        certificates,
    })
}

fn from_pem(bytes: &[u8]) -> Result<Keybox> {
    let mut certificates = Vec::new();
    let mut private_keys = Vec::new();
    for block in pem::blocks(bytes)? {
        if block.label == "CERTIFICATE" {
            let cert = Certificate::from_der(&block.der)
                .map_err(|_| MaterialError::Keybox("cannot decode certificate"))?;
            if !certificates.contains(&cert) {
                certificates.push(cert);
            }
        } else {
            private_keys.push(pem::wrap(&block.label, &block.der));
        }
    }
    if private_keys.is_empty() || private_keys.len() > 2 || certificates.len() > 16 {
        return Err(
            MaterialError::Keybox("PEM bundle needs 1..2 keys and 1..16 certificates").into(),
        );
    }
    let mut used = HashSet::new();
    let mut keys = Vec::new();
    for private in private_keys {
        let mut key = material(private, Vec::new())?;
        key.certificates = ordered_chain(&key.public, &certificates)?;
        used.extend(
            key.certificates
                .iter()
                .map(Encode::to_der)
                .collect::<Result<Vec<_>, _>>()?,
        );
        keys.push(key);
    }
    if used.len() != certificates.len() {
        return Err(MaterialError::Keybox("PEM bundle contains unrelated certificates").into());
    }
    Ok(Keybox {
        device_id: "offline".into(),
        keys,
    })
}

/// Build a single unambiguous leaf-to-root path for this private key. Self-signatures
/// establish internal consistency only; no root is considered trusted by this check.
fn ordered_chain(public: &[u8], certificates: &[Certificate]) -> Result<Vec<Certificate>> {
    let check = || -> Result<Vec<Certificate>> {
        ensure!(!certificates.is_empty() && certificates.len() <= 16);
        let leaves = certificates
            .iter()
            .filter(|cert| {
                cert.tbs_certificate()
                    .subject_public_key_info()
                    .to_der()
                    .ok()
                    .as_deref()
                    == Some(public)
            })
            .collect::<Vec<_>>();
        ensure!(leaves.len() == 1);
        let mut chain = vec![leaves[0].clone()];
        loop {
            let current = chain.last().unwrap();
            let tbs = current.tbs_certificate();
            // Self-issued key rotation certificates share names with their issuer;
            // only a valid self-signature terminates the path.
            if tbs.issuer() == tbs.subject() && verify_certificate(current, current).is_ok() {
                return Ok(chain);
            }
            let issuers = certificates
                .iter()
                .filter(|cert| {
                    cert.tbs_certificate().subject() == tbs.issuer()
                        && !chain.contains(cert)
                        && verify_certificate(current, cert).is_ok()
                })
                .collect::<Vec<_>>();
            ensure!(issuers.len() == 1 && chain.len() < 8);
            chain.push(issuers[0].clone());
        }
    };
    check().map_err(|_| {
        MaterialError::Keybox(
        "private key must match one leaf with an unambiguous, signed path to a self-signed root"
    ).into()
    })
}

fn verify_certificate(cert: &Certificate, issuer: &Certificate) -> Result<()> {
    let tbs = cert.tbs_certificate();
    ensure!(tbs.signature() == cert.signature_algorithm());
    let public = issuer.tbs_certificate().subject_public_key_info();
    let public_algorithm = public.algorithm.to_der()?;
    let signature_algorithm = cert.signature_algorithm().to_der()?;
    let public_algorithm = AnyRef::from_der(&public_algorithm)?;
    let signature_algorithm = AnyRef::from_der(&signature_algorithm)?;
    let message = tbs.to_der()?;
    let provider = rustls::crypto::aws_lc_rs::default_provider();
    ensure!(
        provider
            .signature_verification_algorithms
            .all
            .iter()
            .any(|algorithm| {
                algorithm.public_key_alg_id().as_ref() == public_algorithm.value()
                    && algorithm.signature_alg_id().as_ref() == signature_algorithm.value()
                    && public
                        .subject_public_key
                        .as_bytes()
                        .zip(cert.signature().as_bytes())
                        .is_some_and(|(key, signature)| {
                            algorithm.verify_signature(key, &message, signature).is_ok()
                        })
            })
            || super::legacy_rsa::verify(cert, issuer).is_ok()
    );
    Ok(())
}

fn render(boxes: &[Keybox]) -> Result<Zeroizing<String>> {
    let mut output = Zeroizing::new(format!(
        "<?xml version=\"1.0\"?>\n<AndroidAttestation>\n  <NumberOfKeyboxes>{}</NumberOfKeyboxes>\n",
        boxes.len()
    ));
    for keybox in boxes {
        writeln!(
            output,
            "  <Keybox DeviceID=\"{}\">",
            quick_xml::escape::escape(&keybox.device_id)
        )?;
        for key in &keybox.keys {
            writeln!(
                output,
                "    <Key algorithm=\"{}\">\n      <PrivateKey format=\"pem\">\n{}\n      </PrivateKey>",
                key.algorithm,
                key.private.trim()
            )?;
            writeln!(
                output,
                "      <CertificateChain>\n        <NumberOfCertificates>{}</NumberOfCertificates>",
                key.certificates.len()
            )?;
            for cert in &key.certificates {
                writeln!(
                    output,
                    "        <Certificate format=\"pem\">\n{}\n        </Certificate>",
                    pem::wrap("CERTIFICATE", &cert.to_der()?).as_str()
                )?;
            }
            writeln!(output, "      </CertificateChain>\n    </Key>")?;
        }
        writeln!(output, "  </Keybox>")?;
    }
    writeln!(output, "</AndroidAttestation>")?;
    Ok(output)
}

#[cfg(test)]
mod install_tests {
    use super::*;
    const AOSP: &[u8] = include_bytes!("../../duck-tricky-store/assets/aosp-keybox.xml");
    #[test]
    fn legacy_aosp_consistency_and_expiry() {
        let historical = SystemTime::UNIX_EPOCH + std::time::Duration::from_secs(1_735_689_600);
        validate_install_at(AOSP, historical).unwrap();
        let expired = historical + std::time::Duration::from_secs(2 * 366 * 86400);
        assert!(
            validate_install_at(AOSP, expired)
                .unwrap_err()
                .to_string()
                .contains("not currently valid")
        );
        let mut broken = AOSP.to_vec();
        let text = std::str::from_utf8(AOSP).unwrap();
        let offset = text.find("-----BEGIN RSA PRIVATE KEY-----").unwrap()
            + "-----BEGIN RSA PRIVATE KEY-----".len();
        let offset = offset
            + broken[offset..]
                .iter()
                .position(|b| !b.is_ascii_whitespace())
                .unwrap();
        broken[offset] = b'!';
        assert!(validate_install_at(&broken, historical).is_err());
    }
}
