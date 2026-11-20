//! Test-only in-memory key store, so the API client and the player can be
//! tested without touching the Windows key store.

use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use p256::ecdsa::signature::Signer as _;
use p256::ecdsa::{Signature, SigningKey};
use p256::elliptic_curve::sec1::ToSec1Point as _;

use super::{InstallationKey, KeyError, KeyStore};
use crate::signing;

#[derive(Clone)]
pub struct SoftwareKey(pub Arc<SigningKey>);

impl SoftwareKey {
    pub fn random() -> Self {
        loop {
            let mut bytes = [0u8; 32];
            getrandom::fill(&mut bytes).unwrap();
            if let Ok(key) = SigningKey::from_slice(&bytes) {
                return Self(Arc::new(key));
            }
        }
    }
}

impl InstallationKey for SoftwareKey {
    fn public_key_spki(&self) -> Result<String, KeyError> {
        let point = p256::PublicKey::from(self.0.verifying_key()).to_sec1_point(false);
        let x: [u8; 32] = point.x().unwrap().as_slice().try_into().unwrap();
        let y: [u8; 32] = point.y().unwrap().as_slice().try_into().unwrap();
        Ok(signing::spki_base64(&x, &y))
    }

    fn sign(&self, message: &[u8]) -> Result<[u8; 64], KeyError> {
        let signature: Signature = self.0.sign(message);
        Ok(signature.to_bytes().as_slice().try_into().unwrap())
    }
}

#[derive(Default, Clone)]
pub struct MemoryKeyStore {
    pub keys: Arc<Mutex<HashMap<String, SoftwareKey>>>,
}

impl KeyStore for MemoryKeyStore {
    fn create(&self, name: &str) -> Result<(Box<dyn InstallationKey>, String), KeyError> {
        let key = SoftwareKey::random();
        self.keys
            .lock()
            .unwrap()
            .insert(name.to_owned(), key.clone());
        Ok((Box::new(key), "memory".to_owned()))
    }

    fn open(&self, name: &str, _: &str) -> Result<Box<dyn InstallationKey>, KeyError> {
        let key = self
            .keys
            .lock()
            .unwrap()
            .get(name)
            .cloned()
            .ok_or(KeyError::NotFound)?;
        Ok(Box::new(key))
    }

    fn delete(&self, name: &str, _: &str) -> Result<(), KeyError> {
        self.keys.lock().unwrap().remove(name);
        Ok(())
    }
}
