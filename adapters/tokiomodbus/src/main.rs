// SPDX-License-Identifier: MIT

//! modbus-interop adapter for tokio-modbus: the commands of
//! docs/CONTAINER_CONTRACT.md.

mod args;
mod client;
mod fixture;
mod json;
mod server;

use json::Obj;

pub const ADAPTER: &str = "tokiomodbus";

// Set by the Dockerfile; the fallbacks are for a build outside it.
const ADAPTER_VERSION: &str = match option_env!("ADAPTER_VERSION") {
    Some(version) => version,
    None => "dev",
};
const UPSTREAM_VERSION: &str = match option_env!("UPSTREAM_VERSION") {
    Some(version) => version,
    None => "unknown",
};
const UPSTREAM_REVISION: &str = match option_env!("UPSTREAM_REVISION") {
    Some(revision) => revision,
    None => "unknown",
};

const FIXTURES: &str = "/fixtures";

const USAGE: &str = "usage:
  modbus-interop server [--fixture PATH] [--bind-address ADDR] [--port N] [--ready-file PATH]
  modbus-interop client <operation> --host HOST [--port N] [--unit-id N]
                        [--connect-timeout-ms N] [--timeout-ms N] [--repeat N] [operation flags]
  modbus-interop print-capabilities
  modbus-interop print-fixture [NAME]
  modbus-interop version";

fn usage(message: &str) -> i32 {
    eprintln!("modbus-interop: {message}\n{USAGE}");
    2
}

fn capabilities() -> String {
    let upstream = Obj::new()
        .str("name", "tokio-modbus")
        .str("version", UPSTREAM_VERSION)
        .str("revision", UPSTREAM_REVISION)
        .str("license", "MIT OR Apache-2.0")
        .str("language", "Rust")
        .boolean("independentEngine", true)
        .end();
    let roles = Obj::new().boolean("server", true).boolean("client", true).end();
    let features = Obj::new()
        .boolean("serverUnitIdFiltering", true)
        .boolean("serverQuantityCheck", true)
        .boolean("serverMaskWrite", true)
        .boolean("serverReadWriteMultiple", true)
        .boolean("serverDeviceIdentification", true)
        .boolean("requestEvents", true)
        .boolean("connectionEvents", true)
        .boolean("clientMaskWrite", true)
        .boolean("clientReadWriteMultiple", true)
        .boolean("clientDeviceIdentification", true)
        .boolean("clientRaw", true)
        .boolean("clientRepeat", true)
        .boolean("clientUnitIdCheck", true)
        .end();
    let operations: Vec<String> = args::OPERATIONS.iter().map(|o| json::quote(o)).collect();
    Obj::new()
        .str("schemaVersion", "1.0")
        .str("adapter", ADAPTER)
        .str("adapterVersion", ADAPTER_VERSION)
        .str("protocol", "modbus-tcp")
        .raw("upstream", &upstream)
        .raw("roles", &roles)
        .raw("features", &features)
        .raw("serverFunctions", "[1,2,3,4,5,6,15,16,22,23,43]")
        .raw("clientOperations", &format!("[{}]", operations.join(",")))
        .end()
}

/// Writes the baked fixture byte for byte.
fn print_fixture(name: &str) -> i32 {
    use std::io::Write as _;
    let plain = !name.is_empty() && name.chars().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '-');
    let bytes = match plain.then(|| std::fs::read(format!("{FIXTURES}/{name}/fixture.json"))) {
        Some(Ok(bytes)) => bytes,
        _ => {
            eprintln!("modbus-interop: no fixture named {name:?}");
            return 1;
        }
    };
    let mut stdout = std::io::stdout().lock();
    match stdout.write_all(&bytes).and_then(|()| stdout.flush()) {
        Ok(()) => 0,
        Err(_) => 1,
    }
}

fn run(args: &[String]) -> i32 {
    let Some(command) = args.first() else {
        return usage("a command is required");
    };
    let rest = &args[1..];
    match command.as_str() {
        "server" => match args::server(rest) {
            Ok(config) => server::run(config),
            Err(message) => usage(&message),
        },
        "client" => match args::client(rest) {
            Ok(config) => client::run(config),
            Err(message) => usage(&message),
        },
        "print-capabilities" if rest.is_empty() => {
            json::emit(&capabilities());
            0
        }
        "print-fixture" if rest.len() <= 1 => print_fixture(rest.first().map_or("baseline", String::as_str)),
        "version" if rest.is_empty() => {
            println!("modbus-interop {ADAPTER} {ADAPTER_VERSION} (tokio-modbus {UPSTREAM_VERSION})");
            0
        }
        "print-capabilities" | "print-fixture" | "version" => usage(&format!("{command}: unexpected argument")),
        _ => usage(&format!("unknown command {command:?}")),
    }
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    std::process::exit(run(&args));
}
