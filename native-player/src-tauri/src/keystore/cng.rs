//! Windows CNG (NCrypt) installation keys. Keys are per-user, persisted and
//! non-exportable; they are created in the Microsoft Platform Crypto Provider
//! (TPM) when it works, otherwise in the Microsoft Software Key Storage
//! Provider. `NCryptSignHash` returns ECDSA signatures as `r || s`, which is
//! the IEEE P1363 form the backend verifies.

use std::sync::Mutex;

use windows::Win32::Foundation::NTE_BAD_KEYSET;
use windows::Win32::Security::Cryptography::{
    BCRYPT_ECCPUBLIC_BLOB, BCRYPT_ECDSA_P256_ALGORITHM, BCRYPT_ECDSA_PUBLIC_P256_MAGIC,
    CERT_KEY_SPEC, NCRYPT_EXPORT_POLICY_PROPERTY, NCRYPT_FLAGS, NCRYPT_KEY_HANDLE,
    NCRYPT_PROV_HANDLE, NCRYPT_SILENT_FLAG, NCryptCreatePersistedKey, NCryptDeleteKey,
    NCryptExportKey, NCryptFinalizeKey, NCryptFreeObject, NCryptOpenKey, NCryptOpenStorageProvider,
    NCryptSetProperty, NCryptSignHash,
};
use windows::core::HSTRING;

use super::{InstallationKey, KeyError, KeyStore};
use crate::signing;

pub const TPM_PROVIDER: &str = "Microsoft Platform Crypto Provider";
pub const SOFTWARE_PROVIDER: &str = "Microsoft Software Key Storage Provider";

pub struct CngKeyStore;

impl KeyStore for CngKeyStore {
    fn create(&self, name: &str) -> Result<(Box<dyn InstallationKey>, String), KeyError> {
        let (key, provider) = match create_in(TPM_PROVIDER, name) {
            Ok(key) => (key, TPM_PROVIDER),
            Err(_) => (create_in(SOFTWARE_PROVIDER, name)?, SOFTWARE_PROVIDER),
        };
        Ok((Box::new(key), provider.to_owned()))
    }

    fn open(&self, name: &str, provider: &str) -> Result<Box<dyn InstallationKey>, KeyError> {
        let provider = Provider::open(checked_provider(provider)?)?;
        let handle = open_key(&provider, name)?;
        Ok(Box::new(CngKey::new(handle, provider)))
    }

    fn delete(&self, name: &str, provider: &str) -> Result<(), KeyError> {
        let provider = Provider::open(checked_provider(provider)?)?;
        match open_key(&provider, name) {
            // NCryptDeleteKey also frees the handle.
            Ok(handle) => unsafe { NCryptDeleteKey(handle, 0) }.map_err(platform),
            Err(KeyError::NotFound) => Ok(()),
            Err(other) => Err(other),
        }
    }
}

fn create_in(provider_name: &str, name: &str) -> Result<CngKey, KeyError> {
    let provider = Provider::open(provider_name)?;
    let mut handle = NCRYPT_KEY_HANDLE::default();
    unsafe {
        NCryptCreatePersistedKey(
            provider.0,
            &mut handle,
            BCRYPT_ECDSA_P256_ALGORITHM,
            &HSTRING::from(name),
            CERT_KEY_SPEC(0),
            NCRYPT_FLAGS(0),
        )
    }
    .map_err(platform)?;
    // A key that fails half way is deleted (which also frees the handle).
    let abandon = |error: windows::core::Error| {
        let _ = unsafe { NCryptDeleteKey(handle, 0) };
        platform(error)
    };
    if provider_name == SOFTWARE_PROVIDER {
        // Not exportable (0 = no export allowed). TPM keys can never be exported.
        let policy = 0u32.to_le_bytes();
        unsafe {
            NCryptSetProperty(
                handle.into(),
                NCRYPT_EXPORT_POLICY_PROPERTY,
                &policy,
                NCRYPT_FLAGS(0),
            )
        }
        .map_err(abandon)?;
    }
    unsafe { NCryptFinalizeKey(handle, NCRYPT_SILENT_FLAG) }.map_err(abandon)?;
    Ok(CngKey::new(handle, provider))
}

fn open_key(provider: &Provider, name: &str) -> Result<NCRYPT_KEY_HANDLE, KeyError> {
    let mut handle = NCRYPT_KEY_HANDLE::default();
    unsafe {
        NCryptOpenKey(
            provider.0,
            &mut handle,
            &HSTRING::from(name),
            CERT_KEY_SPEC(0),
            NCRYPT_SILENT_FLAG,
        )
    }
    .map_err(|error| {
        if error.code() == NTE_BAD_KEYSET {
            KeyError::NotFound
        } else {
            platform(error)
        }
    })?;
    Ok(handle)
}

fn checked_provider(provider: &str) -> Result<&str, KeyError> {
    match provider {
        TPM_PROVIDER | SOFTWARE_PROVIDER => Ok(provider),
        _ => Err(KeyError::Platform("unknown key storage provider".into())),
    }
}

struct Provider(NCRYPT_PROV_HANDLE);

impl Provider {
    fn open(name: &str) -> Result<Self, KeyError> {
        let mut handle = NCRYPT_PROV_HANDLE::default();
        unsafe { NCryptOpenStorageProvider(&mut handle, &HSTRING::from(name), 0) }
            .map_err(platform)?;
        Ok(Self(handle))
    }
}

impl Drop for Provider {
    fn drop(&mut self) {
        let _ = unsafe { NCryptFreeObject(self.0.into()) };
    }
}

struct CngKey {
    // Signing is serialized: a CNG key handle is not documented as safe for
    // concurrent use. Declared before the provider so it is freed first.
    handle: Mutex<NCRYPT_KEY_HANDLE>,
    _provider: Provider,
}

// SAFETY: the handles are opaque references owned by this value; every use of
// the key handle goes through the mutex above.
unsafe impl Send for CngKey {}
unsafe impl Sync for CngKey {}

impl CngKey {
    fn new(handle: NCRYPT_KEY_HANDLE, provider: Provider) -> Self {
        Self {
            handle: Mutex::new(handle),
            _provider: provider,
        }
    }

    fn handle(&self) -> std::sync::MutexGuard<'_, NCRYPT_KEY_HANDLE> {
        self.handle
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }
}

impl Drop for CngKey {
    fn drop(&mut self) {
        let handle = *self
            .handle
            .get_mut()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let _ = unsafe { NCryptFreeObject(handle.into()) };
    }
}

impl InstallationKey for CngKey {
    fn public_key_spki(&self) -> Result<String, KeyError> {
        let handle = self.handle();
        let mut size = 0u32;
        unsafe {
            NCryptExportKey(
                *handle,
                None,
                BCRYPT_ECCPUBLIC_BLOB,
                None,
                None,
                &mut size,
                NCRYPT_FLAGS(0),
            )
        }
        .map_err(platform)?;
        let mut blob = vec![0u8; size as usize];
        unsafe {
            NCryptExportKey(
                *handle,
                None,
                BCRYPT_ECCPUBLIC_BLOB,
                None,
                Some(&mut blob),
                &mut size,
                NCRYPT_FLAGS(0),
            )
        }
        .map_err(platform)?;
        // BCRYPT_ECCKEY_BLOB { dwMagic: u32, cbKey: u32 } followed by X and Y.
        if size != 72 || blob.len() != 72 {
            return Err(KeyError::Platform("unexpected public key format".into()));
        }
        let magic = u32::from_le_bytes(blob[0..4].try_into().expect("4 bytes"));
        let length = u32::from_le_bytes(blob[4..8].try_into().expect("4 bytes"));
        if magic != BCRYPT_ECDSA_PUBLIC_P256_MAGIC || length != 32 {
            return Err(KeyError::Platform("unexpected public key format".into()));
        }
        let x: [u8; 32] = blob[8..40].try_into().expect("32 bytes");
        let y: [u8; 32] = blob[40..72].try_into().expect("32 bytes");
        Ok(signing::spki_base64(&x, &y))
    }

    fn sign(&self, message: &[u8]) -> Result<[u8; 64], KeyError> {
        let digest = signing::sha256(message);
        let handle = self.handle();
        let mut signature = [0u8; 64];
        let mut written = 0u32;
        unsafe {
            NCryptSignHash(
                *handle,
                None,
                &digest,
                Some(&mut signature),
                &mut written,
                NCRYPT_SILENT_FLAG,
            )
        }
        .map_err(platform)?;
        if written != 64 {
            return Err(KeyError::Platform("unexpected signature length".into()));
        }
        Ok(signature)
    }
}

fn platform(error: windows::core::Error) -> KeyError {
    KeyError::Platform(format!("{:#010x}", error.code().0))
}

#[cfg(test)]
mod tests {
    use super::*;
    use base64::Engine as _;
    use p256::ecdsa::signature::Verifier as _;
    use p256::pkcs8::DecodePublicKey as _;

    fn verify(spki: &str, message: &[u8], signature: &[u8; 64]) -> bool {
        let der = base64::engine::general_purpose::STANDARD
            .decode(spki)
            .unwrap();
        let key = p256::ecdsa::VerifyingKey::from_public_key_der(&der).unwrap();
        let signature = p256::ecdsa::Signature::from_slice(signature).unwrap();
        key.verify(message, &signature).is_ok()
    }

    #[test]
    fn software_provider_key_signs_verifiable_p1363() {
        let name = crate::keystore::new_key_name();
        let key = create_in(SOFTWARE_PROVIDER, &name).expect("create software key");
        let spki = key.public_key_spki().unwrap();
        let message = signing::canonical("POST", "/api/v1/player/exchange", 1, "n", b"{}");
        let signature = key.sign(&message).unwrap();
        assert!(verify(&spki, &message, &signature));
        assert!(!verify(&spki, b"other", &signature));
        drop(key);

        // The persisted key can be reopened by name and still verifies.
        let reopened = CngKeyStore.open(&name, SOFTWARE_PROVIDER).unwrap();
        assert_eq!(reopened.public_key_spki().unwrap(), spki);
        let again = reopened.sign(b"hello").unwrap();
        assert!(verify(&spki, b"hello", &again));
        drop(reopened);

        CngKeyStore.delete(&name, SOFTWARE_PROVIDER).unwrap();
        assert!(matches!(
            CngKeyStore.open(&name, SOFTWARE_PROVIDER),
            Err(KeyError::NotFound)
        ));
        // Deleting a missing key is fine.
        CngKeyStore.delete(&name, SOFTWARE_PROVIDER).unwrap();
    }

    #[test]
    fn software_provider_key_is_not_exportable() {
        let name = crate::keystore::new_key_name();
        let key = create_in(SOFTWARE_PROVIDER, &name).unwrap();
        let mut size = 0u32;
        let private = unsafe {
            NCryptExportKey(
                *key.handle(),
                None,
                windows::Win32::Security::Cryptography::BCRYPT_ECCPRIVATE_BLOB,
                None,
                None,
                &mut size,
                NCRYPT_FLAGS(0),
            )
        };
        assert!(private.is_err(), "private key export must be refused");
        drop(key);
        CngKeyStore.delete(&name, SOFTWARE_PROVIDER).unwrap();
    }

    #[test]
    fn unknown_providers_are_refused() {
        assert!(CngKeyStore.open("x", "Some Other Provider").is_err());
    }

    /// Needs a TPM 2.0; run with `cargo test -- --ignored`.
    #[test]
    #[ignore]
    fn tpm_key_signs_verifiable_p1363() {
        let name = crate::keystore::new_key_name();
        let key = create_in(TPM_PROVIDER, &name).expect("create TPM key");
        let spki = key.public_key_spki().unwrap();
        let signature = key.sign(b"tpm").unwrap();
        assert!(verify(&spki, b"tpm", &signature));
        drop(key);
        CngKeyStore.delete(&name, TPM_PROVIDER).unwrap();
    }
}
