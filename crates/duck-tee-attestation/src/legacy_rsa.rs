//! Local consistency checking for legacy RSA-1024 software attestation material.
//! No signing, issuance or trust decision is performed here.
use anyhow::{Result, ensure};
use rsa::{
    RsaPrivateKey, RsaPublicKey,
    pkcs1::DecodeRsaPrivateKey,
    pkcs8::{DecodePrivateKey, DecodePublicKey, EncodePublicKey},
    signature::Verifier,
    traits::PublicKeyParts,
};
use rustls::pki_types::PrivateKeyDer;
use x509_cert::{Certificate, der::Encode};

pub(crate) fn public_key(der: &PrivateKeyDer<'_>) -> Result<Vec<u8>> {
    let key = match der {
        PrivateKeyDer::Pkcs1(value) => RsaPrivateKey::from_pkcs1_der(value.secret_pkcs1_der())?,
        PrivateKeyDer::Pkcs8(value) => RsaPrivateKey::from_pkcs8_der(value.secret_pkcs8_der())?,
        _ => anyhow::bail!("not RSA"),
    };
    ensure!(key.n().bits() == 1024, "unsupported legacy RSA size");
    key.validate()?;
    Ok(key.to_public_key().to_public_key_der()?.as_bytes().to_vec())
}

pub(crate) fn verify(cert: &Certificate, issuer: &Certificate) -> Result<()> {
    // The fallback is restricted to SHA256-with-RSA and RSA-1024; normal
    // certificates continue through the existing provider's algorithms.
    ensure!(cert.signature_algorithm().oid.to_string() == "1.2.840.113549.1.1.11");
    let spki = issuer
        .tbs_certificate()
        .subject_public_key_info()
        .to_der()?;
    let key = RsaPublicKey::from_public_key_der(&spki)?;
    ensure!(key.n().bits() == 1024);
    let signature = cert
        .signature()
        .as_bytes()
        .ok_or_else(|| anyhow::anyhow!("invalid signature"))?;
    rsa::pkcs1v15::VerifyingKey::<rsa::sha2::Sha256>::new(key)
        .verify(&cert.tbs_certificate().to_der()?, &signature.try_into()?)?;
    Ok(())
}
