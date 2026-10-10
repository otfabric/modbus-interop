// SPDX-License-Identifier: MIT

//! Loading and validating a fixture (docs/FIXTURES.md).
//!
//! The rules are those of fixtures/schema/modbus-fixture.schema.json and of
//! the cross-field checks in scripts/validate-fixtures.py.

use std::path::Path;

use serde_json::{Map, Value};

/// One table: the address of its first element and its values.
#[derive(Debug, Clone)]
pub struct Block<T> {
    pub address: usize,
    pub values: Vec<T>,
}

#[derive(Debug, Clone)]
pub struct IdObject {
    pub id: u8,
    pub value: String,
}

#[derive(Debug, Clone)]
pub struct Fixture {
    pub name: String,
    pub unit_id: u8,
    pub coils: Option<Block<bool>>,
    pub discrete_inputs: Option<Block<bool>>,
    pub holding_registers: Option<Block<u16>>,
    pub input_registers: Option<Block<u16>>,
    /// Ascending by id; empty when the fixture has no device identification.
    pub identification: Vec<IdObject>,
}

const ROOT_KEYS: [&str; 9] = [
    "schemaVersion",
    "name",
    "description",
    "unitId",
    "coils",
    "discreteInputs",
    "holdingRegisters",
    "inputRegisters",
    "deviceIdentification",
];

/// An integer in `min..=max`. Like JSON Schema, a number with a zero
/// fractional part counts as an integer.
fn integer(value: &Value, min: u64, max: u64) -> Option<u64> {
    let n = match value.as_u64() {
        Some(n) => n,
        None => {
            let f = value.as_f64()?;
            if f.fract() != 0.0 || f < 0.0 || f > 4_294_967_295.0 {
                return None;
            }
            f as u64
        }
    };
    (min..=max).contains(&n).then_some(n)
}

fn only_keys(object: &Map<String, Value>, allowed: &[&str], what: &str) -> Result<(), String> {
    for key in object.keys() {
        if !allowed.contains(&key.as_str()) {
            return Err(format!("{what}: unknown property {key:?}"));
        }
    }
    Ok(())
}

fn valid_name(name: &str) -> bool {
    let ok = |c: char| c.is_ascii_lowercase() || c.is_ascii_digit();
    let mut chars = name.chars();
    match chars.next() {
        Some(first) if ok(first) => chars.all(|c| ok(c) || c == '-'),
        _ => false,
    }
}

fn block<T>(
    root: &Map<String, Value>,
    table: &str,
    item: impl Fn(&Value) -> Option<T>,
) -> Result<Option<Block<T>>, String> {
    let Some(value) = root.get(table) else {
        return Ok(None);
    };
    let object = value
        .as_object()
        .ok_or_else(|| format!("{table}: must be an object"))?;
    only_keys(object, &["address", "values"], table)?;
    let address = object
        .get("address")
        .and_then(|v| integer(v, 0, 65535))
        .ok_or_else(|| format!("{table}: address must be an integer 0..65535"))? as usize;
    let list = object
        .get("values")
        .and_then(Value::as_array)
        .ok_or_else(|| format!("{table}: values must be an array"))?;
    if list.is_empty() || list.len() > 65536 {
        return Err(format!("{table}: values must have 1..65536 items"));
    }
    let mut values = Vec::with_capacity(list.len());
    for (i, v) in list.iter().enumerate() {
        values.push(item(v).ok_or_else(|| format!("{table}: values[{i}] has the wrong type or range"))?);
    }
    let end = address + values.len() - 1;
    if end > 65535 {
        return Err(format!("{table}: block ends at {end}, past address 65535"));
    }
    Ok(Some(Block { address, values }))
}

fn identification(root: &Map<String, Value>) -> Result<Vec<IdObject>, String> {
    let Some(value) = root.get("deviceIdentification") else {
        return Ok(Vec::new());
    };
    let what = "deviceIdentification";
    let object = value
        .as_object()
        .ok_or_else(|| format!("{what}: must be an object"))?;
    only_keys(object, &["objects"], what)?;
    let list = object
        .get("objects")
        .and_then(Value::as_array)
        .ok_or_else(|| format!("{what}: objects must be an array"))?;
    if list.len() < 3 {
        return Err(format!("{what}: objects must have at least 3 items"));
    }
    let mut objects: Vec<IdObject> = Vec::with_capacity(list.len());
    for (i, entry) in list.iter().enumerate() {
        let entry = entry
            .as_object()
            .ok_or_else(|| format!("{what}: objects[{i}] must be an object"))?;
        only_keys(entry, &["id", "value"], what)?;
        let id = entry
            .get("id")
            .and_then(|v| integer(v, 0, 255))
            .ok_or_else(|| format!("{what}: objects[{i}].id must be an integer 0..255"))? as u8;
        let value = entry
            .get("value")
            .and_then(Value::as_str)
            .ok_or_else(|| format!("{what}: objects[{i}].value must be a string"))?;
        if value.is_empty() || value.len() > 240 || !value.bytes().all(|b| (0x20..=0x7e).contains(&b)) {
            return Err(format!(
                "{what}: objects[{i}].value must be 1..240 printable ASCII characters"
            ));
        }
        if let Some(previous) = objects.last() {
            if previous.id >= id {
                return Err(format!("{what}: object ids must be unique and ascending"));
            }
        }
        if (7..=127).contains(&id) {
            return Err(format!("{what}: object id {id} is reserved"));
        }
        objects.push(IdObject {
            id,
            value: value.to_string(),
        });
    }
    for basic in 0..=2u8 {
        if !objects.iter().any(|o| o.id == basic) {
            return Err(format!("{what}: basic object {basic} is missing"));
        }
    }
    Ok(objects)
}

/// Parses and validates a fixture document. `directory` is the name of the
/// directory the file lies in: the fixture's name must equal it.
pub fn parse(text: &str, directory: Option<&str>) -> Result<Fixture, String> {
    let document: Value = serde_json::from_str(text).map_err(|e| format!("not JSON: {e}"))?;
    let root = document
        .as_object()
        .ok_or_else(|| "the fixture must be a JSON object".to_string())?;
    only_keys(root, &ROOT_KEYS, "fixture")?;

    if root.get("schemaVersion").and_then(Value::as_str) != Some("1.0") {
        return Err("schemaVersion must be \"1.0\"".into());
    }
    let name = root
        .get("name")
        .and_then(Value::as_str)
        .filter(|n| valid_name(n))
        .ok_or_else(|| "name must match ^[a-z0-9][a-z0-9-]*$".to_string())?;
    if let Some(description) = root.get("description") {
        if !description.is_string() {
            return Err("description must be a string".into());
        }
    }
    let unit_id = root
        .get("unitId")
        .and_then(|v| integer(v, 1, 247))
        .ok_or_else(|| "unitId must be an integer 1..247".to_string())? as u8;

    let fixture = Fixture {
        name: name.to_string(),
        unit_id,
        coils: block(root, "coils", Value::as_bool)?,
        discrete_inputs: block(root, "discreteInputs", Value::as_bool)?,
        holding_registers: block(root, "holdingRegisters", |v| integer(v, 0, 65535).map(|n| n as u16))?,
        input_registers: block(root, "inputRegisters", |v| integer(v, 0, 65535).map(|n| n as u16))?,
        identification: identification(root)?,
    };
    if let Some(directory) = directory {
        if directory != fixture.name {
            return Err(format!(
                "name {:?} is not the directory name {directory:?}",
                fixture.name
            ));
        }
    }
    Ok(fixture)
}

/// Loads the fixture at `path`.
pub fn load(path: &str) -> Result<Fixture, String> {
    let text = std::fs::read_to_string(path).map_err(|e| format!("cannot read {path}: {e}"))?;
    let resolved = std::fs::canonicalize(path).unwrap_or_else(|_| Path::new(path).to_path_buf());
    let directory = resolved
        .parent()
        .and_then(Path::file_name)
        .and_then(|n| n.to_str())
        .map(str::to_string);
    parse(&text, directory.as_deref()).map_err(|e| format!("{path}: {e}"))
}
