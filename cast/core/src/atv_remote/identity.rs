use std::fmt;

use rand_core::{CryptoRng, RngCore};
use rcgen::{CertificateParams, DnType, IsCa, KeyPair, PKCS_RSA_SHA256, SanType, SerialNumber};
use rsa::RsaPrivateKey;
use rsa::pkcs8::{DecodePrivateKey, EncodePrivateKey, LineEnding};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, pem::PemObject};
use sha2::{Digest, Sha256};
use time::OffsetDateTime;
use x509_parser::certificate::X509Certificate;
use x509_parser::prelude::FromDer;
use x509_parser::public_key::PublicKey;
use zeroize::Zeroizing;

use super::error::AtvError;

const RSA_BITS: usize = 2048;
const CERT_YEARS: i64 = 10;

/// RSA client certificate and private key for Android TV Remote v2.
///
/// The caller stores the PEM. Debug output includes the certificate fingerprint
/// and omits the private key.
pub struct ClientIdentity {
    cert_pem: String,
    key_pem: Zeroizing<String>,
    cert_der: Vec<u8>,
    key_der: Zeroizing<Vec<u8>>,
}

impl Clone for ClientIdentity {
    fn clone(&self) -> Self {
        Self {
            cert_pem: self.cert_pem.clone(),
            key_pem: self.key_pem.clone(),
            cert_der: self.cert_der.clone(),
            key_der: self.key_der.clone(),
        }
    }
}

impl fmt::Debug for ClientIdentity {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("ClientIdentity")
            .field(
                "fingerprint_sha256",
                &fingerprint_hex(&self.fingerprint_sha256()),
            )
            .finish_non_exhaustive()
    }
}

impl ClientIdentity {
    /// Builds a new RSA-2048 key and a self-signed certificate.
    ///
    /// `client_name` is the certificate common name (1–64 characters). A DNS
    /// subject alternative name is added only when the name is a DNS name.
    pub fn generate(client_name: &str) -> Result<Self, AtvError> {
        validate_client_name(client_name)?;
        let mut rng = FillRng;
        let key = RsaPrivateKey::new(&mut rng, RSA_BITS)
            .map_err(|_| AtvError::Protocol("RSA client key generation failed".to_owned()))?;
        let document = key
            .to_pkcs8_der()
            .map_err(|_| AtvError::Protocol("RSA client key encoding failed".to_owned()))?;
        let key_der = document.as_bytes().to_vec();
        let key_pem = key
            .to_pkcs8_pem(LineEnding::LF)
            .map_err(|_| AtvError::Protocol("RSA client key encoding failed".to_owned()))?
            .to_string();
        drop(key);

        let pkcs8 = PrivatePkcs8KeyDer::from(key_der.clone());
        let key_pair = KeyPair::from_pkcs8_der_and_sign_algo(&pkcs8, &PKCS_RSA_SHA256)
            .map_err(|_| AtvError::Protocol("RSA client key encoding failed".to_owned()))?;
        let mut params = CertificateParams::default();
        params.distinguished_name = rcgen::DistinguishedName::new();
        params
            .distinguished_name
            .push(DnType::CommonName, client_name);
        let now = OffsetDateTime::now_utc();
        params.not_before = now;
        params.not_after = now
            .checked_add(time::Duration::days(CERT_YEARS * 365))
            .ok_or_else(|| AtvError::Protocol("certificate validity overflow".to_owned()))?;
        let mut serial = [0_u8; 16];
        getrandom::fill(&mut serial)
            .map_err(|_| AtvError::Protocol("certificate serial generation failed".to_owned()))?;
        // Positive, nonzero 127-bit serial; never expose it in diagnostics.
        serial[0] = (serial[0] & 0x7f) | 0x40;
        params.serial_number = Some(SerialNumber::from_slice(&serial));
        // Python's generator emits noncritical BasicConstraints (CA, pathlen 0) and no
        // SubjectKeyIdentifier. rcgen's IsCa::Ca marks that extension critical and adds SKI.
        params.is_ca = IsCa::NoCa;
        params.custom_extensions.clear();
        params
            .custom_extensions
            .push(rcgen::CustomExtension::from_oid_content(
                &[2, 5, 29, 19],
                vec![0x30, 0x06, 0x01, 0x01, 0xff, 0x02, 0x01, 0x00],
            ));
        params.subject_alt_names.clear();
        if let Some(san) = dns_san(client_name) {
            params.subject_alt_names.push(san);
        }
        let certificate = params
            .self_signed(&key_pair)
            .map_err(|_| AtvError::Protocol("client certificate generation failed".to_owned()))?;
        Ok(Self {
            cert_pem: certificate.pem(),
            key_pem: Zeroizing::new(key_pem),
            cert_der: certificate.der().to_vec(),
            key_der: Zeroizing::new(key_der),
        })
    }

    /// Only explicit pairing calls this. The caller supplies its complete, UUID-derived name.
    /// Existing control sessions keep their saved identity.
    pub fn for_pairing(
        client_name: &str,
        existing: Option<Self>,
    ) -> Result<(Self, bool), AtvError> {
        validate_client_name(client_name)?;
        if let Some(identity) = existing {
            if !identity.is_legacy()? {
                return Ok((identity, false));
            }
            return Self::generate(client_name).map(|identity| (identity, true));
        }
        Self::generate(client_name).map(|identity| (identity, false))
    }

    pub fn client_name(&self) -> Result<String, AtvError> {
        let (_, certificate) = X509Certificate::from_der(&self.cert_der)
            .map_err(|_| AtvError::Protocol("client certificate is not X.509".to_owned()))?;
        let name = certificate
            .subject()
            .iter_common_name()
            .next()
            .and_then(|attribute| attribute.as_str().ok())
            .ok_or_else(|| {
                AtvError::Protocol("client certificate common name is invalid".to_owned())
            })?;
        validate_client_name(name)?;
        Ok(name.to_owned())
    }

    pub fn is_legacy(&self) -> Result<bool, AtvError> {
        let (_, certificate) = X509Certificate::from_der(&self.cert_der)
            .map_err(|_| AtvError::Protocol("client certificate is not X.509".to_owned()))?;
        Ok(self.client_name()? == "PlayBridge"
            || strip_leading_zeros(certificate.raw_serial()) == [0x03, 0xe8])
    }

    /// Loads a PEM certificate and a PKCS#8 RSA private key.
    pub fn from_pem(cert_pem: &str, key_pem: &str) -> Result<Self, AtvError> {
        let cert = CertificateDer::from_pem_slice(cert_pem.as_bytes())
            .map_err(|_| AtvError::Protocol("client certificate PEM is invalid".to_owned()))?;
        let key = PrivateKeyDer::from_pem_slice(key_pem.as_bytes())
            .map_err(|_| AtvError::Protocol("client private key PEM is invalid".to_owned()))?;
        let PrivateKeyDer::Pkcs8(pkcs8) = key else {
            return Err(AtvError::Protocol(
                "client private key must be PKCS#8 RSA".to_owned(),
            ));
        };
        RsaPrivateKey::from_pkcs8_der(pkcs8.secret_pkcs8_der())
            .map_err(|_| AtvError::Protocol("client private key is not RSA".to_owned()))?;
        rsa_components(cert.as_ref())?;
        Ok(Self {
            cert_pem: cert_pem.to_owned(),
            key_pem: Zeroizing::new(key_pem.to_owned()),
            cert_der: cert.as_ref().to_vec(),
            key_der: Zeroizing::new(pkcs8.secret_pkcs8_der().to_vec()),
        })
    }

    pub fn certificate_pem(&self) -> &str {
        &self.cert_pem
    }

    pub fn private_key_pem(&self) -> &str {
        &self.key_pem
    }

    pub fn certificate_der(&self) -> &[u8] {
        &self.cert_der
    }

    pub fn private_key_der(&self) -> &[u8] {
        &self.key_der
    }

    pub fn fingerprint_sha256(&self) -> [u8; 32] {
        fingerprint_sha256(&self.cert_der)
    }
}

/// SHA-256 of a certificate's DER encoding.
pub fn fingerprint_sha256(der: &[u8]) -> [u8; 32] {
    Sha256::digest(der).into()
}

pub fn fingerprint_hex(fingerprint: &[u8; 32]) -> String {
    let mut hex = String::with_capacity(64);
    for byte in fingerprint {
        hex.push_str(&format!("{byte:02x}"));
    }
    hex
}

pub(crate) fn validate_client_name(client_name: &str) -> Result<(), AtvError> {
    let length = client_name.chars().count();
    if !(1..=64).contains(&length) || client_name.chars().any(char::is_control) {
        return Err(AtvError::Protocol(
            "client name must be 1 to 64 characters".to_owned(),
        ));
    }
    Ok(())
}

/// RSA modulus and exponent from a certificate, including any ASN.1 sign byte.
pub(crate) struct RsaComponents {
    pub modulus: Vec<u8>,
    pub exponent: Vec<u8>,
}

pub(crate) fn rsa_components(der: &[u8]) -> Result<RsaComponents, AtvError> {
    let (_, certificate) = X509Certificate::from_der(der)
        .map_err(|_| AtvError::Protocol("certificate is not X.509".to_owned()))?;
    let public_key = certificate
        .public_key()
        .parsed()
        .map_err(|_| AtvError::Protocol("certificate public key is unreadable".to_owned()))?;
    match public_key {
        PublicKey::RSA(rsa) => Ok(RsaComponents {
            modulus: rsa.modulus.to_vec(),
            exponent: rsa.exponent.to_vec(),
        }),
        _ => Err(AtvError::Protocol(
            "certificate public key is not RSA".to_owned(),
        )),
    }
}

/// MAC from the certificate subject CN, when the last `/` segment is a MAC.
pub fn mac_from_certificate(der: &[u8]) -> Option<String> {
    let (_, certificate) = X509Certificate::from_der(der).ok()?;
    for attribute in certificate.subject().iter_common_name() {
        let Ok(name) = attribute.as_str() else {
            continue;
        };
        if let Some(mac) = mac_from_cn(name) {
            return Some(mac);
        }
    }
    None
}

fn mac_from_cn(name: &str) -> Option<String> {
    let segment = name.rsplit('/').next().unwrap_or(name);
    if is_mac(segment) {
        Some(segment.to_owned())
    } else {
        None
    }
}

fn is_mac(value: &str) -> bool {
    let mut parts = value.split(':');
    for _ in 0..6 {
        let Some(part) = parts.next() else {
            return false;
        };
        if part.len() != 2 || !part.bytes().all(|byte| byte.is_ascii_hexdigit()) {
            return false;
        }
    }
    parts.next().is_none()
}

/// SHA-256 pairing secret.
///
/// Encoding matches androidtvremote2 `pairing.py`: uppercase hex of each
/// integer with no ASN.1 sign byte. The exponent hex is prefixed with one
/// nibble `0` (`65537` → `01 00 01`). The code's last four hex digits are the
/// nonce. `hash[0]` must equal the code's first byte.
pub fn pairing_secret(
    client_modulus: &[u8],
    client_exponent: &[u8],
    server_modulus: &[u8],
    server_exponent: &[u8],
    code: &str,
) -> Result<[u8; 32], AtvError> {
    let nonce = pairing_nonce(code)?;
    let digest = pairing_digest(
        client_modulus,
        client_exponent,
        server_modulus,
        server_exponent,
        &nonce,
    )?;
    let first = u8::from_str_radix(&code.trim()[..2], 16).unwrap_or(0);
    if digest[0] != first {
        return Err(AtvError::BadCode);
    }
    Ok(digest)
}

pub(crate) fn pairing_digest(
    client_modulus: &[u8],
    client_exponent: &[u8],
    server_modulus: &[u8],
    server_exponent: &[u8],
    nonce: &[u8],
) -> Result<[u8; 32], AtvError> {
    let mut hasher = Sha256::new();
    hasher.update(modulus_wire(client_modulus)?);
    hasher.update(exponent_wire(client_exponent)?);
    hasher.update(modulus_wire(server_modulus)?);
    hasher.update(exponent_wire(server_exponent)?);
    hasher.update(nonce);
    Ok(hasher.finalize().into())
}

pub(crate) fn modulus_wire(raw: &[u8]) -> Result<Vec<u8>, AtvError> {
    decode_hex(&integer_hex(raw))
}

pub(crate) fn exponent_wire(raw: &[u8]) -> Result<Vec<u8>, AtvError> {
    decode_hex(&format!("0{}", integer_hex(raw)))
}

fn pairing_nonce(code: &str) -> Result<Vec<u8>, AtvError> {
    let code = code.trim();
    if code.len() != 6 || !code.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        return Err(AtvError::BadCode);
    }
    decode_hex(&code[2..]).map_err(|_| AtvError::BadCode)
}

fn integer_hex(bytes: &[u8]) -> String {
    let bytes = strip_leading_zeros(bytes);
    if bytes.is_empty() {
        return "0".to_owned();
    }
    let mut hex = format!("{:X}", bytes[0]);
    for byte in &bytes[1..] {
        hex.push_str(&format!("{byte:02X}"));
    }
    hex
}

fn strip_leading_zeros(bytes: &[u8]) -> &[u8] {
    match bytes.iter().position(|byte| *byte != 0) {
        Some(start) => &bytes[start..],
        None => &[],
    }
}

fn decode_hex(hex: &str) -> Result<Vec<u8>, AtvError> {
    if !hex.len().is_multiple_of(2) {
        return Err(AtvError::Protocol(
            "pairing integer hex length is odd".to_owned(),
        ));
    }
    let mut out = Vec::with_capacity(hex.len() / 2);
    let bytes = hex.as_bytes();
    let mut index = 0;
    while index < bytes.len() {
        let high = hex_nibble(bytes[index])?;
        let low = hex_nibble(bytes[index + 1])?;
        out.push((high << 4) | low);
        index += 2;
    }
    Ok(out)
}

fn hex_nibble(byte: u8) -> Result<u8, AtvError> {
    match byte {
        b'0'..=b'9' => Ok(byte - b'0'),
        b'A'..=b'F' => Ok(byte - b'A' + 10),
        b'a'..=b'f' => Ok(byte - b'a' + 10),
        _ => Err(AtvError::Protocol(
            "pairing integer hex is invalid".to_owned(),
        )),
    }
}

fn dns_san(name: &str) -> Option<SanType> {
    if !is_dns_name(name) {
        return None;
    }
    let ia5 = rcgen::string::Ia5String::try_from(name).ok()?;
    Some(SanType::DnsName(ia5))
}

fn is_dns_name(name: &str) -> bool {
    if name.is_empty() || name.len() > 253 || name.starts_with('.') || name.ends_with('.') {
        return false;
    }
    name.split('.').all(|label| {
        !label.is_empty()
            && label.len() <= 63
            && !label.starts_with('-')
            && !label.ends_with('-')
            && label
                .bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-')
    })
}

struct FillRng;

impl RngCore for FillRng {
    fn next_u32(&mut self) -> u32 {
        let mut bytes = [0_u8; 4];
        self.fill_bytes(&mut bytes);
        u32::from_le_bytes(bytes)
    }

    fn next_u64(&mut self) -> u64 {
        let mut bytes = [0_u8; 8];
        self.fill_bytes(&mut bytes);
        u64::from_le_bytes(bytes)
    }

    fn fill_bytes(&mut self, dest: &mut [u8]) {
        self.try_fill_bytes(dest).expect("system rng unavailable");
    }

    fn try_fill_bytes(&mut self, dest: &mut [u8]) -> Result<(), rand_core::Error> {
        getrandom::fill(dest).map_err(rand_core::Error::new)
    }
}

impl CryptoRng for FillRng {}

#[cfg(test)]
mod tests {
    use super::*;

    const DIGEST: [u8; 32] = [
        0xc0, 0x40, 0x74, 0x87, 0x47, 0x1c, 0x8e, 0x80, 0x6f, 0x22, 0x6f, 0x13, 0xd3, 0x43, 0x8a,
        0xe6, 0x95, 0x34, 0x8a, 0xa0, 0x6a, 0x9a, 0xb1, 0x00, 0x75, 0x1b, 0x60, 0x91, 0x87, 0xb7,
        0x9d, 0x69,
    ];

    #[test]
    fn pairing_secret_matches_independent_fixture() {
        let secret = pairing_secret(
            &[0xAB, 0xCD],
            &[0x01, 0x00, 0x01],
            &[0x12, 0x34],
            &[0x01, 0x00, 0x01],
            "C0B2C3",
        )
        .unwrap();
        assert_eq!(secret, DIGEST);
        let stripped = pairing_secret(
            &[0x00, 0xAB, 0xCD],
            &[0x00, 0x01, 0x00, 0x01],
            &[0x12, 0x34],
            &[0x01, 0x00, 0x01],
            "c0b2c3",
        )
        .unwrap();
        assert_eq!(stripped, DIGEST);
        assert_eq!(
            pairing_secret(
                &[0xAB, 0xCD],
                &[0x01, 0x00, 0x01],
                &[0x12, 0x34],
                &[0x01, 0x00, 0x01],
                "A1B2C3",
            ),
            Err(AtvError::BadCode)
        );
    }

    #[test]
    fn odd_modulus_hex_is_a_protocol_error() {
        let error = pairing_secret(
            &[0x01],
            &[0x01, 0x00, 0x01],
            &[0x12, 0x34],
            &[0x01, 0x00, 0x01],
            "00B2C3",
        )
        .unwrap_err();
        assert!(matches!(error, AtvError::Protocol(_)));
    }

    #[test]
    fn generated_identity_is_rsa_2048() {
        let identity = ClientIdentity::generate("PlayBridge").unwrap();
        let components = rsa_components(identity.certificate_der()).unwrap();
        assert_eq!(exponent_wire(&components.exponent).unwrap(), vec![1, 0, 1]);
        assert_eq!(modulus_wire(&components.modulus).unwrap().len(), 256);
        let restored =
            ClientIdentity::from_pem(identity.certificate_pem(), identity.private_key_pem())
                .unwrap();
        assert_eq!(restored.fingerprint_sha256(), identity.fingerprint_sha256());
        assert!(format!("{identity:?}").contains("fingerprint_sha256"));
        assert!(!format!("{identity:?}").contains("BEGIN"));
        assert!(mac_from_certificate(identity.certificate_der()).is_none());
        assert_python_basic_constraints(identity.certificate_der());
    }

    #[test]
    fn pairing_identity_has_unique_cn_and_positive_random_serial_and_reuses_the_install() {
        let (first, replaced) =
            ClientIdentity::for_pairing("PlayBridge (iPhone) aaaaaaaa", None).unwrap();
        let (second, _) =
            ClientIdentity::for_pairing("PlayBridge (iPhone) bbbbbbbb", None).unwrap();
        assert!(!replaced);
        let name = first.client_name().unwrap();
        assert!(name == "PlayBridge (iPhone) aaaaaaaa");
        assert!(name != second.client_name().unwrap());
        assert!(validate_client_name(&name).is_ok());
        assert!(!first.is_legacy().unwrap());
        let (_, a) = X509Certificate::from_der(first.certificate_der()).unwrap();
        let (_, b) = X509Certificate::from_der(second.certificate_der()).unwrap();
        assert!(a.raw_serial().len() == 16 && a.raw_serial()[0] & 0x80 == 0);
        assert!(a.raw_serial() != b.raw_serial());
        let (same_name, _) = ClientIdentity::for_pairing(&name, None).unwrap();
        let (_, same_cert) = X509Certificate::from_der(same_name.certificate_der()).unwrap();
        assert!(same_name.client_name().unwrap() == name);
        assert!(a.raw_serial() != same_cert.raw_serial());
        assert!(a.subject_alternative_name().unwrap().is_none());
        let fingerprint = first.fingerprint_sha256();
        let (reused, replaced) =
            ClientIdentity::for_pairing("PlayBridge (new model)", Some(first)).unwrap();
        assert!(!replaced && reused.fingerprint_sha256() == fingerprint);
        assert!(reused.client_name().unwrap() == name);
    }

    #[test]
    fn legacy_cn_or_serial_migrates_only_when_preparing_pairing() {
        let legacy_cn = ClientIdentity::generate("PlayBridge").unwrap();
        assert!(legacy_cn.is_legacy().unwrap());
        let restored =
            ClientIdentity::from_pem(legacy_cn.certificate_pem(), legacy_cn.private_key_pem())
                .unwrap();
        assert!(restored.fingerprint_sha256() == legacy_cn.fingerprint_sha256());
        let (migrated, changed) =
            ClientIdentity::for_pairing("PlayBridge (Android) aaaaaaaa", Some(restored)).unwrap();
        assert!(changed && !migrated.is_legacy().unwrap());

        let source = ClientIdentity::generate("Other legacy client").unwrap();
        let key = KeyPair::from_pkcs8_der_and_sign_algo(
            &PrivatePkcs8KeyDer::from(source.key_der.to_vec()),
            &PKCS_RSA_SHA256,
        )
        .unwrap();
        let mut params = CertificateParams::default();
        params
            .distinguished_name
            .push(DnType::CommonName, "Other legacy client");
        params.serial_number = Some(SerialNumber::from_slice(&[0x03, 0xe8]));
        let certificate = params.self_signed(&key).unwrap();
        let legacy_serial =
            ClientIdentity::from_pem(&certificate.pem(), source.private_key_pem()).unwrap();
        assert!(legacy_serial.is_legacy().unwrap());
        let (migrated, changed) =
            ClientIdentity::for_pairing("PlayBridge (Android) aaaaaaaa", Some(legacy_serial))
                .unwrap();
        assert!(changed && !migrated.is_legacy().unwrap());
    }

    #[test]
    fn unique_names_respect_bounds_control_validation_and_dns_san_rules() {
        assert!(ClientIdentity::for_pairing("bad\nname", None).is_err());
        let (identity, _) =
            ClientIdentity::for_pairing(&format!("{} aaaaaaaa", "x".repeat(55)), None).unwrap();
        assert!(identity.client_name().unwrap().chars().count() <= 64);
        assert!(dns_san(&identity.client_name().unwrap()).is_none());
        assert!(dns_san("playbridge.example").is_some());
    }

    #[test]
    fn certificate_name_can_carry_a_mac() {
        let identity = ClientIdentity::generate("atvremote/AA:BB:CC:DD:EE:FF").unwrap();
        assert_eq!(
            mac_from_certificate(identity.certificate_der()).as_deref(),
            Some("AA:BB:CC:DD:EE:FF")
        );
    }

    #[test]
    fn spaced_name_skips_the_dns_san() {
        let identity = ClientIdentity::generate("Play Bridge").unwrap();
        assert!(identity.certificate_der().len() > 32);
    }

    fn assert_python_basic_constraints(der: &[u8]) {
        use x509_parser::extensions::ParsedExtension;
        let (_, certificate) = X509Certificate::from_der(der).unwrap();
        let mut saw_constraints = false;
        for extension in certificate.extensions() {
            match extension.parsed_extension() {
                ParsedExtension::BasicConstraints(constraints) => {
                    assert!(!extension.critical);
                    assert!(constraints.ca);
                    assert_eq!(constraints.path_len_constraint, Some(0));
                    saw_constraints = true;
                }
                ParsedExtension::SubjectKeyIdentifier(_) => {
                    panic!("generated certificate includes a subject key identifier");
                }
                _ => {}
            }
        }
        assert!(saw_constraints);
    }

    #[test]
    fn from_pem_rejects_a_non_rsa_certificate() {
        let key = KeyPair::generate().unwrap();
        let params = CertificateParams::new(vec!["localhost".to_owned()]).unwrap();
        let certificate = params.self_signed(&key).unwrap();
        let error = ClientIdentity::from_pem(&certificate.pem(), &key.serialize_pem()).unwrap_err();
        assert!(matches!(error, AtvError::Protocol(_)));
    }
}
