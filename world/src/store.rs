//! Small JSON cache in the app's data folder (adapted from VeloPopuli's store, GPLv3).

use std::fs;
use std::path::PathBuf;

use serde::de::DeserializeOwned;
use serde::Serialize;

pub struct Store {
    dir: PathBuf,
}

fn valid_name(name: &str) -> bool {
    !name.is_empty() && name.len() <= 80 && name.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
}

impl Store {
    pub fn new(dir: PathBuf) -> Self {
        Self { dir }
    }

    fn file(&self, name: &str) -> Result<PathBuf, String> {
        if !valid_name(name) {
            return Err(format!("Invalid store key: {name}"));
        }
        Ok(self.dir.join(format!("{name}.json")))
    }

    pub fn get<T: DeserializeOwned>(&self, name: &str) -> Option<T> {
        let text = fs::read_to_string(self.file(name).ok()?).ok()?;
        serde_json::from_str(&text).ok()
    }

    pub fn set<T: Serialize>(&self, name: &str, value: &T) -> Result<(), String> {
        let file = self.file(name)?;
        fs::create_dir_all(file.parent().unwrap()).map_err(|e| e.to_string())?;
        let tmp = file.with_extension(format!("{}.tmp", std::process::id()));
        fs::write(&tmp, serde_json::to_vec(value).map_err(|e| e.to_string())?).map_err(|e| e.to_string())?;
        fs::rename(&tmp, &file).map_err(|e| e.to_string())
    }
}
