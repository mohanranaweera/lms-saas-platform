//! The installation key (ADR-025 §3, Amendment 1): ECDSA P-256 whose private
//! half never leaves the OS key store. On Windows it is a non-exportable CNG
//! key, in the TPM when there is one.

#[cfg(windows)]
mod cng;
#[cfg(test)]
pub mod software;

#[derive(Debug, thiserror::Error)]
pub enum KeyError {
    #[error("the installation key was not found")]
    NotFound,
    #[error("the key store failed: {0}")]
    Platform(String),
}

/// One installation's key pair.
pub trait InstallationKey: Send + Sync {
    /// Base64 (standard) X.509 SubjectPublicKeyInfo, as `pairings/complete` expects.
    fn public_key_spki(&self) -> Result<String, KeyError>;

    /// ECDSA P-256 over SHA-256(`message`), IEEE P1363 `r || s`.
    fn sign(&self, message: &[u8]) -> Result<[u8; 64], KeyError>;
}

/// Where installation keys are created, found and removed.
pub trait KeyStore: Send + Sync {
    /// Creates a new persisted key; returns it with the provider that holds it.
    fn create(&self, name: &str) -> Result<(Box<dyn InstallationKey>, String), KeyError>;

    fn open(&self, name: &str, provider: &str) -> Result<Box<dyn InstallationKey>, KeyError>;

    /// Removes a key; a key that is already gone is not an error.
    fn delete(&self, name: &str, provider: &str) -> Result<(), KeyError>;
}

/// A fresh, random key name (no hardware fingerprint, ADR-025 §3).
pub fn new_key_name() -> String {
    format!("AOC-Player-{}", uuid::Uuid::new_v4())
}

/// The key store for this operating system.
pub fn platform_store() -> Box<dyn KeyStore> {
    #[cfg(windows)]
    {
        Box::new(cng::CngKeyStore)
    }
    #[cfg(not(windows))]
    {
        Box::new(Unsupported)
    }
}

#[cfg(not(windows))]
struct Unsupported;

#[cfg(not(windows))]
impl KeyStore for Unsupported {
    fn create(&self, _: &str) -> Result<(Box<dyn InstallationKey>, String), KeyError> {
        Err(KeyError::Platform("only Windows is supported".into()))
    }

    fn open(&self, _: &str, _: &str) -> Result<Box<dyn InstallationKey>, KeyError> {
        Err(KeyError::Platform("only Windows is supported".into()))
    }

    fn delete(&self, _: &str, _: &str) -> Result<(), KeyError> {
        Ok(())
    }
}
