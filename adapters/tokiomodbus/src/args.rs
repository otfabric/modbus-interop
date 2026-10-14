// SPDX-License-Identifier: MIT

//! Command line of the container contract (docs/CONTAINER_CONTRACT.md).
//!
//! Flags are `--name value`. Anything unknown, missing or out of range is a
//! usage error; the caller exits 2 before any network access.

use std::collections::HashMap;
use std::net::IpAddr;
use std::time::Duration;

pub const OPERATIONS: [&str; 13] = [
    "read-coils",
    "read-discrete-inputs",
    "read-holding-registers",
    "read-input-registers",
    "write-single-coil",
    "write-single-register",
    "write-multiple-coils",
    "write-multiple-registers",
    "mask-write-register",
    "read-write-multiple-registers",
    "read-device-identification",
    "report-server-id",
    "raw",
];

/// Operations of the contract that tokio-modbus has no request type for:
/// sending them would mean encoding and decoding the PDU in the adapter.
/// They are refused like an unknown operation (exit 2, nothing sent).
const REFUSED: [&str; 4] = ["diagnostics", "read-file-record", "write-file-record", "read-fifo-queue"];

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Category {
    Basic,
    Regular,
    Extended,
}

#[derive(Debug, Clone)]
pub enum Op {
    ReadCoils { address: u16, quantity: u16 },
    ReadDiscreteInputs { address: u16, quantity: u16 },
    ReadHoldingRegisters { address: u16, quantity: u16 },
    ReadInputRegisters { address: u16, quantity: u16 },
    WriteSingleCoil { address: u16, value: bool },
    WriteSingleRegister { address: u16, value: u16 },
    WriteMultipleCoils { address: u16, values: Vec<bool> },
    WriteMultipleRegisters { address: u16, values: Vec<u16> },
    MaskWriteRegister { address: u16, and_mask: u16, or_mask: u16 },
    ReadWriteMultipleRegisters { read_address: u16, read_quantity: u16, write_address: u16, values: Vec<u16> },
    ReadDeviceIdentification { category: Category },
    ReportServerId,
    Raw { function: u8, data: Vec<u8> },
}

impl Op {
    /// Function code of the request.
    pub fn function(&self) -> u8 {
        match self {
            Op::ReadCoils { .. } => 1,
            Op::ReadDiscreteInputs { .. } => 2,
            Op::ReadHoldingRegisters { .. } => 3,
            Op::ReadInputRegisters { .. } => 4,
            Op::WriteSingleCoil { .. } => 5,
            Op::WriteSingleRegister { .. } => 6,
            Op::WriteMultipleCoils { .. } => 15,
            Op::WriteMultipleRegisters { .. } => 16,
            Op::MaskWriteRegister { .. } => 22,
            Op::ReadWriteMultipleRegisters { .. } => 23,
            Op::ReadDeviceIdentification { .. } => 43,
            Op::ReportServerId => 17,
            Op::Raw { function, .. } => *function,
        }
    }
}

#[derive(Debug, Clone)]
pub struct ClientConfig {
    pub operation: String,
    pub op: Op,
    pub host: String,
    pub port: u16,
    pub unit_id: u8,
    pub connect_timeout: Duration,
    pub timeout: Duration,
    pub repeat: u32,
}

#[derive(Debug, Clone)]
pub struct ServerConfig {
    pub fixture: String,
    pub bind_address: IpAddr,
    pub port: u16,
    pub ready_file: String,
}

/// Decimal, or hexadecimal with a `0x` prefix.
fn number(text: &str) -> Option<u64> {
    let (digits, radix) = match text.strip_prefix("0x").or_else(|| text.strip_prefix("0X")) {
        Some(hex) => (hex, 16),
        None => (text, 10),
    };
    if digits.is_empty() || digits.len() > 16 || !digits.chars().all(|c| c.is_digit(radix)) {
        return None;
    }
    u64::from_str_radix(digits, radix).ok()
}

fn coil(text: &str) -> Option<bool> {
    match text.to_ascii_lowercase().as_str() {
        "true" | "1" | "on" => Some(true),
        "false" | "0" | "off" => Some(false),
        _ => None,
    }
}

fn hex_bytes(text: &str) -> Option<Vec<u8>> {
    if text.len() % 2 != 0 || !text.chars().all(|c| c.is_ascii_hexdigit()) {
        return None;
    }
    (0..text.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&text[i..i + 2], 16).ok())
        .collect()
}

struct Flags {
    values: HashMap<String, String>,
}

impl Flags {
    /// Every argument must be one of `allowed`, followed by its value.
    fn parse(args: &[String], allowed: &[&str]) -> Result<Self, String> {
        let mut values = HashMap::new();
        let mut i = 0;
        while i < args.len() {
            let arg = &args[i];
            let name = arg
                .strip_prefix("--")
                .filter(|n| allowed.contains(n))
                .ok_or_else(|| format!("unknown flag {arg:?}"))?;
            let value = args
                .get(i + 1)
                .filter(|v| !v.starts_with("--"))
                .ok_or_else(|| format!("--{name} needs a value"))?;
            values.insert(name.to_string(), value.clone());
            i += 2;
        }
        Ok(Self { values })
    }

    fn text(&self, name: &str) -> Result<&str, String> {
        self.values
            .get(name)
            .map(String::as_str)
            .ok_or_else(|| format!("--{name} is required"))
    }

    fn number_or(&self, name: &str, default: Option<u64>, min: u64, max: u64) -> Result<u64, String> {
        let Some(text) = self.values.get(name) else {
            return default.ok_or_else(|| format!("--{name} is required"));
        };
        number(text)
            .filter(|n| (min..=max).contains(n))
            .ok_or_else(|| format!("--{name} must be a number {min}..{max}, got {text:?}"))
    }

    fn u16(&self, name: &str) -> Result<u16, String> {
        self.number_or(name, None, 0, 65535).map(|n| n as u16)
    }

    fn quantity(&self, name: &str, max: u64) -> Result<u16, String> {
        self.number_or(name, None, 1, max).map(|n| n as u16)
    }

    fn list<T>(&self, name: &str, max: usize, item: impl Fn(&str) -> Option<T>) -> Result<Vec<T>, String> {
        let text = self.text(name)?;
        let values: Vec<T> = text
            .split(',')
            .map(|part| item(part.trim()).ok_or_else(|| format!("--{name}: invalid value {part:?}")))
            .collect::<Result<_, _>>()?;
        if values.is_empty() || values.len() > max {
            return Err(format!("--{name} takes 1..{max} values, got {}", values.len()));
        }
        Ok(values)
    }

    fn registers(&self, name: &str, max: usize) -> Result<Vec<u16>, String> {
        self.list(name, max, |t| number(t).filter(|n| *n <= 65535).map(|n| n as u16))
    }
}

const COMMON: [&str; 6] = ["host", "port", "unit-id", "connect-timeout-ms", "timeout-ms", "repeat"];

fn operation_flags(operation: &str) -> Option<&'static [&'static str]> {
    Some(match operation {
        "read-coils" | "read-discrete-inputs" | "read-holding-registers" | "read-input-registers" => {
            &["address", "quantity"]
        }
        "write-single-coil" | "write-single-register" => &["address", "value"],
        "write-multiple-coils" | "write-multiple-registers" => &["address", "values"],
        "mask-write-register" => &["address", "and-mask", "or-mask"],
        "read-write-multiple-registers" => &["read-address", "read-quantity", "write-address", "values"],
        "read-device-identification" => &["category"],
        "report-server-id" => &[],
        "raw" => &["function", "data"],
        _ => return None,
    })
}

/// `client <operation> ...`: `args` starts at the operation.
pub fn client(args: &[String]) -> Result<ClientConfig, String> {
    let operation = args.first().ok_or_else(|| "client needs an operation".to_string())?;
    if REFUSED.contains(&operation.as_str()) {
        return Err(format!("operation {operation:?} is not supported: tokio-modbus has no request type for it"));
    }
    let own = operation_flags(operation).ok_or_else(|| format!("unknown operation {operation:?}"))?;
    let allowed: Vec<&str> = COMMON.iter().chain(own.iter()).copied().collect();
    let flags = Flags::parse(&args[1..], &allowed)?;

    let host = flags.text("host")?.to_string();
    if host.is_empty() {
        return Err("--host must not be empty".into());
    }
    let op = match operation.as_str() {
        "read-coils" => Op::ReadCoils {
            address: flags.u16("address")?,
            quantity: flags.quantity("quantity", 2000)?,
        },
        "read-discrete-inputs" => Op::ReadDiscreteInputs {
            address: flags.u16("address")?,
            quantity: flags.quantity("quantity", 2000)?,
        },
        "read-holding-registers" => Op::ReadHoldingRegisters {
            address: flags.u16("address")?,
            quantity: flags.quantity("quantity", 125)?,
        },
        "read-input-registers" => Op::ReadInputRegisters {
            address: flags.u16("address")?,
            quantity: flags.quantity("quantity", 125)?,
        },
        "write-single-coil" => Op::WriteSingleCoil {
            address: flags.u16("address")?,
            value: coil(flags.text("value")?)
                .ok_or_else(|| "--value must be true/false, 1/0 or on/off".to_string())?,
        },
        "write-single-register" => Op::WriteSingleRegister {
            address: flags.u16("address")?,
            value: flags.u16("value")?,
        },
        "write-multiple-coils" => Op::WriteMultipleCoils {
            address: flags.u16("address")?,
            values: flags.list("values", 1968, coil)?,
        },
        "write-multiple-registers" => Op::WriteMultipleRegisters {
            address: flags.u16("address")?,
            values: flags.registers("values", 123)?,
        },
        "mask-write-register" => Op::MaskWriteRegister {
            address: flags.u16("address")?,
            and_mask: flags.u16("and-mask")?,
            or_mask: flags.u16("or-mask")?,
        },
        "read-write-multiple-registers" => Op::ReadWriteMultipleRegisters {
            read_address: flags.u16("read-address")?,
            read_quantity: flags.quantity("read-quantity", 125)?,
            write_address: flags.u16("write-address")?,
            values: flags.registers("values", 121)?,
        },
        "read-device-identification" => Op::ReadDeviceIdentification {
            category: match flags.values.get("category").map(String::as_str) {
                None | Some("basic") => Category::Basic,
                Some("regular") => Category::Regular,
                Some("extended") => Category::Extended,
                Some(other) => return Err(format!("--category must be basic, regular or extended, got {other:?}")),
            },
        },
        "report-server-id" => Op::ReportServerId,
        "raw" => {
            let data = hex_bytes(flags.text("data")?)
                .ok_or_else(|| "--data must be an even number of hexadecimal digits".to_string())?;
            // A PDU is at most 253 bytes, one of which is the function code.
            if data.len() > 252 {
                return Err(format!("--data is {} bytes, a PDU holds at most 252 after the function code", data.len()));
            }
            Op::Raw {
                function: flags.number_or("function", None, 1, 127)? as u8,
                data,
            }
        }
        _ => unreachable!("operation_flags() knows every operation"),
    };

    Ok(ClientConfig {
        operation: operation.clone(),
        op,
        host,
        port: flags.number_or("port", Some(1502), 1, 65535)? as u16,
        unit_id: flags.number_or("unit-id", Some(1), 0, 255)? as u8,
        connect_timeout: Duration::from_millis(flags.number_or("connect-timeout-ms", Some(5000), 1, 3_600_000)?),
        timeout: Duration::from_millis(flags.number_or("timeout-ms", Some(5000), 1, 3_600_000)?),
        repeat: flags.number_or("repeat", Some(1), 1, 1000)? as u32,
    })
}

/// `server ...`: `args` starts after the command.
pub fn server(args: &[String]) -> Result<ServerConfig, String> {
    let flags = Flags::parse(args, &["fixture", "bind-address", "port", "ready-file"])?;
    let text = |name: &str, default: &str| flags.values.get(name).cloned().unwrap_or_else(|| default.to_string());
    let bind = text("bind-address", "0.0.0.0");
    Ok(ServerConfig {
        fixture: text("fixture", "/fixtures/baseline/fixture.json"),
        bind_address: bind
            .parse()
            .map_err(|_| format!("--bind-address must be an IP address, got {bind:?}"))?,
        port: flags.number_or("port", Some(1502), 1, 65535)? as u16,
        ready_file: text("ready-file", "/run/modbus-interop/ready"),
    })
}
