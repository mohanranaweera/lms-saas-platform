//! The local record of this installation: its server id and the name of its
//! key in the OS key store. Nothing here is secret - the private key never
//! leaves the key store.

use std::fs;
use std::io;
use std::path::PathBuf;

use serde::{Deserialize, Serialize};
use time::OffsetDateTime;
use uuid::Uuid;

const FILE_NAME: &str = "installation.json";

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Installation {
    pub installation_id: Uuid,
    pub key_name: String,
    pub key_provider: String,
    #[serde(with = "time::serde::rfc3339")]
    pub paired_at: OffsetDateTime,
}

pub struct InstallationStore {
    dir: PathBuf,
}

impl InstallationStore {
    pub fn new(dir: PathBuf) -> Self {
        Self { dir }
    }

    /// The saved installation; a missing or unreadable file means "not paired".
    pub fn load(&self) -> Option<Installation> {
        let bytes = fs::read(self.dir.join(FILE_NAME)).ok()?;
        serde_json::from_slice(&bytes).ok()
    }

    /// Writes the record atomically (temporary file, then rename).
    pub fn save(&self, installation: &Installation) -> io::Result<()> {
        fs::create_dir_all(&self.dir)?;
        let temporary = self.dir.join(format!("{FILE_NAME}.tmp"));
        fs::write(&temporary, serde_json::to_vec_pretty(installation)?)?;
        fs::rename(&temporary, self.dir.join(FILE_NAME))
    }

    pub fn clear(&self) -> io::Result<()> {
        match fs::remove_file(self.dir.join(FILE_NAME)) {
            Err(error) if error.kind() != io::ErrorKind::NotFound => Err(error),
            _ => Ok(()),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_dir() -> PathBuf {
        let dir = std::env::temp_dir().join(format!("aoc-player-test-{}", Uuid::new_v4()));
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn saves_loads_and_clears() {
        let dir = temp_dir();
        let store = InstallationStore::new(dir.clone());
        assert_eq!(store.load(), None);
        let installation = Installation {
            installation_id: Uuid::new_v4(),
            key_name: "AOC-Player-test".into(),
            key_provider: "memory".into(),
            paired_at: OffsetDateTime::from_unix_timestamp(1_700_000_000).unwrap(),
        };
        store.save(&installation).unwrap();
        assert_eq!(store.load(), Some(installation));
        let json = fs::read_to_string(dir.join(FILE_NAME)).unwrap();
        assert!(json.contains("\"installationId\"") && json.contains("2023-11-14T22:13:20Z"));
        store.clear().unwrap();
        store.clear().unwrap();
        assert_eq!(store.load(), None);
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn corrupt_file_means_not_paired() {
        let dir = temp_dir();
        fs::write(dir.join(FILE_NAME), b"{not json").unwrap();
        assert_eq!(InstallationStore::new(dir.clone()).load(), None);
        fs::remove_dir_all(dir).unwrap();
    }
}
