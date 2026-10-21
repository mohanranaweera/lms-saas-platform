//! Request signing for the player endpoints (ADR-025 Amendment 1,
//! `docs/api/aoc-player.md`). The bytes signed must match the backend's
//! `PlayerRequestVerifier.canonical` exactly:
//! `METHOD\nPATH\nTIMESTAMP\nNONCE\nbase64url(SHA-256(body))`.

use base64::Engine as _;
use base64::engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD};
use sha2::{Digest as _, Sha256};

/// DER prefix of an X.509 SubjectPublicKeyInfo for an uncompressed P-256
/// point (id-ecPublicKey, prime256v1); followed by `04 || X || Y`.
const P256_SPKI_PREFIX: [u8; 26] = [
    0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x02, 0x01, 0x06, 0x08, 0x2a,
    0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00,
];

pub fn canonical(method: &str, path: &str, timestamp: i64, nonce: &str, body: &[u8]) -> Vec<u8> {
    format!(
        "{method}\n{path}\n{timestamp}\n{nonce}\n{}",
        URL_SAFE_NO_PAD.encode(Sha256::digest(body))
    )
    .into_bytes()
}

/// A single-use nonce: 32 random bytes, base64url (43 characters, within the
/// backend's `[A-Za-z0-9_-]{16,64}`).
pub fn new_nonce() -> String {
    let mut bytes = [0u8; 32];
    getrandom::fill(&mut bytes).expect("the OS random number generator is unavailable");
    URL_SAFE_NO_PAD.encode(bytes)
}

pub fn base64url(bytes: &[u8]) -> String {
    URL_SAFE_NO_PAD.encode(bytes)
}

/// The public key as the backend expects it: standard base64 of the SPKI DER.
pub fn spki_base64(x: &[u8; 32], y: &[u8; 32]) -> String {
    let mut der = Vec::with_capacity(91);
    der.extend_from_slice(&P256_SPKI_PREFIX);
    der.push(0x04);
    der.extend_from_slice(x);
    der.extend_from_slice(y);
    STANDARD.encode(der)
}

pub fn sha256(message: &[u8]) -> [u8; 32] {
    Sha256::digest(message).into()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn canonical_matches_the_backend_format() {
        // Same layout as PlayerRequestVerifier.canonical: body hash is
        // base64url(SHA-256(body)) without padding; empty body hashes "".
        let bytes = canonical(
            "POST",
            "/api/v1/player/exchange",
            1_700_000_000,
            "nonce-123",
            b"{}",
        );
        assert_eq!(
            String::from_utf8(bytes).unwrap(),
            "POST\n/api/v1/player/exchange\n1700000000\nnonce-123\nRBNvo1WzZ4oRRq0W9-hknpT7T8If536DEMBg9hyq_4o"
        );
        let empty = canonical("POST", "/p", 1, "n", b"");
        assert!(
            String::from_utf8(empty)
                .unwrap()
                .ends_with("47DEQpj8HBSa-_TImW-5JCeuQeRkm5NMpJWZG3hSuFU")
        );
    }

    #[test]
    fn nonces_are_unique_and_well_formed() {
        let a = new_nonce();
        let b = new_nonce();
        assert_ne!(a, b);
        assert_eq!(a.len(), 43);
        assert!(
            a.bytes()
                .all(|c| c.is_ascii_alphanumeric() || c == b'-' || c == b'_')
        );
    }

    #[test]
    fn spki_is_a_p256_subject_public_key_info() {
        use p256::elliptic_curve::sec1::ToSec1Point as _;
        use p256::pkcs8::DecodePublicKey as _;
        let secret = p256::SecretKey::from_slice(&[7u8; 32]).unwrap();
        let point = secret.public_key().to_sec1_point(false);
        let x: [u8; 32] = point.x().unwrap().as_slice().try_into().unwrap();
        let y: [u8; 32] = point.y().unwrap().as_slice().try_into().unwrap();
        let der = STANDARD.decode(spki_base64(&x, &y)).unwrap();
        let parsed = p256::PublicKey::from_public_key_der(&der).unwrap();
        assert_eq!(parsed, secret.public_key());
    }
}
