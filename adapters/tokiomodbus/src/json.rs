// SPDX-License-Identifier: MIT

//! A small writer for the JSON documents of the container contract.
//!
//! The documents are flat and their field order is part of what makes them
//! readable, so they are written field by field; serde_json does the string
//! escaping.

use std::fmt::Write as _;
use std::io::Write as _;

/// One JSON object, written in the order the fields are added.
pub struct Obj {
    buf: String,
}

impl Obj {
    pub fn new() -> Self {
        Self {
            buf: String::from("{"),
        }
    }

    fn key(&mut self, key: &str) {
        if self.buf.len() > 1 {
            self.buf.push(',');
        }
        self.buf.push_str(&quote(key));
        self.buf.push(':');
    }

    /// A value that is already JSON.
    pub fn raw(mut self, key: &str, json: &str) -> Self {
        self.key(key);
        self.buf.push_str(json);
        self
    }

    pub fn str(self, key: &str, value: &str) -> Self {
        let json = quote(value);
        self.raw(key, &json)
    }

    pub fn num(self, key: &str, value: u64) -> Self {
        self.raw(key, &value.to_string())
    }

    pub fn boolean(self, key: &str, value: bool) -> Self {
        self.raw(key, if value { "true" } else { "false" })
    }

    pub fn null(self, key: &str) -> Self {
        self.raw(key, "null")
    }

    pub fn bits(self, key: &str, values: &[bool]) -> Self {
        let json = bits(values);
        self.raw(key, &json)
    }

    pub fn words(self, key: &str, values: &[u16]) -> Self {
        let json = words(values);
        self.raw(key, &json)
    }

    pub fn end(mut self) -> String {
        self.buf.push('}');
        self.buf
    }
}

pub fn quote(value: &str) -> String {
    serde_json::Value::from(value).to_string()
}

pub fn bits(values: &[bool]) -> String {
    let mut out = String::with_capacity(values.len() * 6 + 2);
    out.push('[');
    for (i, v) in values.iter().enumerate() {
        if i > 0 {
            out.push(',');
        }
        out.push_str(if *v { "true" } else { "false" });
    }
    out.push(']');
    out
}

pub fn words(values: &[u16]) -> String {
    let mut out = String::with_capacity(values.len() * 6 + 2);
    out.push('[');
    for (i, v) in values.iter().enumerate() {
        if i > 0 {
            out.push(',');
        }
        let _ = write!(out, "{v}");
    }
    out.push(']');
    out
}

pub fn hex(data: &[u8]) -> String {
    let mut out = String::with_capacity(data.len() * 2);
    for b in data {
        let _ = write!(out, "{b:02x}");
    }
    out
}

/// Writes one line to stdout and flushes it. stdout carries JSON only.
pub fn emit(line: &str) {
    let stdout = std::io::stdout();
    let mut lock = stdout.lock();
    let _ = lock.write_all(line.as_bytes());
    let _ = lock.write_all(b"\n");
    let _ = lock.flush();
}
