// SPDX-License-Identifier: MIT

//! The `server` command: a Modbus TCP device that serves one fixture.
//!
//! tokio-modbus provides the listener loop, the MBAP framing and the PDU
//! codec (`tokio_modbus::server::tcp::Server`). It asks for one `Service` per
//! accepted connection and hands every decoded request to `Service::call`.
//! Everything a device decides is in that service: the unit identifier, the
//! address ranges, the quantities and the state.

use std::future::{ready, Ready};
use std::net::SocketAddr;
use std::ops::Range;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use tokio::net::{TcpListener, TcpStream};
use tokio::signal::unix::{signal, SignalKind};
use tokio_modbus::bytes::Bytes;
use tokio_modbus::prelude::*;
use tokio_modbus::server::tcp::Server;
use tokio_modbus::server::Service;

use crate::args::ServerConfig;
use crate::fixture::{Block, Fixture, IdObject};
use crate::json::{emit, Obj};

/// A response PDU is at most 253 bytes; a Read Device Identification
/// response spends 7 of them before the first object.
const ID_OBJECT_BUDGET: usize = 253 - 7;

struct Tables {
    coils: Option<Block<bool>>,
    discrete_inputs: Option<Block<bool>>,
    holding_registers: Option<Block<u16>>,
    input_registers: Option<Block<u16>>,
}

/// The device: shared by all connections, alive as long as the process.
struct Device {
    unit_id: u8,
    identification: Vec<IdObject>,
    tables: Mutex<Tables>,
    connections: AtomicUsize,
}

/// The part of `block` that `quantity` elements from `address` cover, or
/// exception 2 when any of them lies outside it (or the table is absent).
fn span<T>(block: &Option<Block<T>>, address: u16, quantity: usize) -> Result<Range<usize>, ExceptionCode> {
    let block = block.as_ref().ok_or(ExceptionCode::IllegalDataAddress)?;
    let first = usize::from(address);
    if first < block.address || first + quantity > block.address + block.values.len() {
        return Err(ExceptionCode::IllegalDataAddress);
    }
    Ok(first - block.address..first - block.address + quantity)
}

/// Exception 3 unless `quantity` is in `1..=max`.
fn quantity_in(quantity: usize, max: usize) -> Result<(), ExceptionCode> {
    if quantity == 0 || quantity > max {
        return Err(ExceptionCode::IllegalDataValue);
    }
    Ok(())
}

impl Device {
    fn new(fixture: Fixture) -> Self {
        Self {
            unit_id: fixture.unit_id,
            identification: fixture.identification,
            tables: Mutex::new(Tables {
                coils: fixture.coils,
                discrete_inputs: fixture.discrete_inputs,
                holding_registers: fixture.holding_registers,
                input_registers: fixture.input_registers,
            }),
            connections: AtomicUsize::new(0),
        }
    }

    /// Answers one request as docs/FIXTURES.md defines the device.
    fn answer(&self, unit_id: u8, request: &Request<'_>) -> Result<Response, ExceptionCode> {
        if unit_id != self.unit_id {
            return Err(ExceptionCode::GatewayTargetDevice);
        }
        let mut guard = self.tables.lock().unwrap_or_else(|e| e.into_inner());
        let tables = &mut *guard;
        match request {
            Request::ReadCoils(address, quantity) => {
                quantity_in(usize::from(*quantity), 2000)?;
                let range = span(&tables.coils, *address, usize::from(*quantity))?;
                Ok(Response::ReadCoils(tables.coils.as_ref().unwrap().values[range].to_vec()))
            }
            Request::ReadDiscreteInputs(address, quantity) => {
                quantity_in(usize::from(*quantity), 2000)?;
                let range = span(&tables.discrete_inputs, *address, usize::from(*quantity))?;
                Ok(Response::ReadDiscreteInputs(
                    tables.discrete_inputs.as_ref().unwrap().values[range].to_vec(),
                ))
            }
            Request::ReadHoldingRegisters(address, quantity) => {
                quantity_in(usize::from(*quantity), 125)?;
                let range = span(&tables.holding_registers, *address, usize::from(*quantity))?;
                Ok(Response::ReadHoldingRegisters(
                    tables.holding_registers.as_ref().unwrap().values[range].to_vec(),
                ))
            }
            Request::ReadInputRegisters(address, quantity) => {
                quantity_in(usize::from(*quantity), 125)?;
                let range = span(&tables.input_registers, *address, usize::from(*quantity))?;
                Ok(Response::ReadInputRegisters(
                    tables.input_registers.as_ref().unwrap().values[range].to_vec(),
                ))
            }
            Request::WriteSingleCoil(address, value) => {
                let range = span(&tables.coils, *address, 1)?;
                tables.coils.as_mut().unwrap().values[range.start] = *value;
                Ok(Response::WriteSingleCoil(*address, *value))
            }
            Request::WriteSingleRegister(address, value) => {
                let range = span(&tables.holding_registers, *address, 1)?;
                tables.holding_registers.as_mut().unwrap().values[range.start] = *value;
                Ok(Response::WriteSingleRegister(*address, *value))
            }
            Request::WriteMultipleCoils(address, values) => {
                quantity_in(values.len(), 1968)?;
                let range = span(&tables.coils, *address, values.len())?;
                tables.coils.as_mut().unwrap().values[range].copy_from_slice(values);
                Ok(Response::WriteMultipleCoils(*address, values.len() as u16))
            }
            Request::WriteMultipleRegisters(address, values) => {
                quantity_in(values.len(), 123)?;
                let range = span(&tables.holding_registers, *address, values.len())?;
                tables.holding_registers.as_mut().unwrap().values[range].copy_from_slice(values);
                Ok(Response::WriteMultipleRegisters(*address, values.len() as u16))
            }
            Request::MaskWriteRegister(address, and_mask, or_mask) => {
                let range = span(&tables.holding_registers, *address, 1)?;
                let register = &mut tables.holding_registers.as_mut().unwrap().values[range.start];
                *register = (*register & and_mask) | (or_mask & !and_mask);
                Ok(Response::MaskWriteRegister(*address, *and_mask, *or_mask))
            }
            Request::ReadWriteMultipleRegisters(read_address, read_quantity, write_address, values) => {
                quantity_in(usize::from(*read_quantity), 125)?;
                quantity_in(values.len(), 121)?;
                // Both ranges are checked before anything is written.
                let read = span(&tables.holding_registers, *read_address, usize::from(*read_quantity))?;
                let write = span(&tables.holding_registers, *write_address, values.len())?;
                let registers = &mut tables.holding_registers.as_mut().unwrap().values;
                registers[write].copy_from_slice(values);
                Ok(Response::ReadWriteMultipleRegisters(registers[read].to_vec()))
            }
            Request::ReadDeviceIdentification(read_code, object_id) => self.identify(*read_code, *object_id),
            // FC17 is decoded by the library but is not part of the device;
            // everything else arrives as Custom.
            _ => Err(ExceptionCode::IllegalFunction),
        }
    }

    /// FC43/14. Stream access returns the objects of the category from
    /// `object_id` on, as many as one PDU holds; individual access returns
    /// the one object.
    fn identify(&self, read_code: ReadCode, object_id: u8) -> Result<Response, ExceptionCode> {
        let objects = &self.identification;
        if objects.is_empty() {
            return Err(ExceptionCode::IllegalFunction);
        }
        let highest = objects.last().map_or(0, |o| o.id);
        // Individual access is offered, hence the levels 0x81..0x83.
        let conformity_level = if highest >= 0x80 {
            ConformityLevel::ExtendedIdentification
        } else if highest >= 3 {
            ConformityLevel::RegularIdentification
        } else {
            ConformityLevel::BasicIdentification
        };
        let object = |o: &IdObject| DeviceIdObject {
            id: o.id,
            value: Bytes::copy_from_slice(o.value.as_bytes()),
        };

        let last_id = match read_code {
            ReadCode::Specific => {
                let found = objects
                    .iter()
                    .find(|o| o.id == object_id)
                    .ok_or(ExceptionCode::IllegalDataAddress)?;
                return Ok(Response::ReadDeviceIdentification(ReadDeviceIdentificationResponse {
                    read_code,
                    conformity_level,
                    more_follows: false,
                    next_object_id: 0,
                    device_id_objects: vec![object(found)],
                }));
            }
            ReadCode::Basic => 0x02,
            ReadCode::Regular => 0x06,
            ReadCode::Extended => 0xff,
        };
        let category: Vec<&IdObject> = objects.iter().filter(|o| o.id <= last_id).collect();
        // An object id the category does not have restarts at the first object.
        let start = category.iter().position(|o| o.id == object_id).unwrap_or(0);
        let mut used = 0;
        let mut device_id_objects = Vec::new();
        let mut next = None;
        for o in &category[start..] {
            let size = 2 + o.value.len();
            if used + size > ID_OBJECT_BUDGET && !device_id_objects.is_empty() {
                next = Some(o.id);
                break;
            }
            used += size;
            device_id_objects.push(object(o));
        }
        Ok(Response::ReadDeviceIdentification(ReadDeviceIdentificationResponse {
            read_code,
            conformity_level,
            more_follows: next.is_some(),
            next_object_id: next.unwrap_or(0),
            device_id_objects,
        }))
    }
}

/// The `request` event of one request and of how it was answered.
fn request_event(unit_id: u8, request: &Request<'_>, result: &Result<Response, ExceptionCode>) -> String {
    let event = Obj::new()
        .str("event", "request")
        .num("unitId", unit_id.into())
        .num("function", request.function_code().value().into());
    let range = |event: Obj, address: u16, quantity: usize| event.num("address", address.into()).num("quantity", quantity as u64);
    let event = match request {
        Request::ReadCoils(address, quantity)
        | Request::ReadDiscreteInputs(address, quantity)
        | Request::ReadHoldingRegisters(address, quantity)
        | Request::ReadInputRegisters(address, quantity) => range(event, *address, usize::from(*quantity)),
        Request::WriteSingleCoil(address, value) => range(event, *address, 1).bits("bits", &[*value]),
        Request::WriteSingleRegister(address, value) => range(event, *address, 1).words("registers", &[*value]),
        Request::WriteMultipleCoils(address, values) => range(event, *address, values.len()).bits("bits", values),
        Request::WriteMultipleRegisters(address, values) => {
            range(event, *address, values.len()).words("registers", values)
        }
        Request::MaskWriteRegister(address, and_mask, or_mask) => range(event, *address, 1)
            .num("andMask", (*and_mask).into())
            .num("orMask", (*or_mask).into()),
        Request::ReadWriteMultipleRegisters(read_address, read_quantity, write_address, values) => {
            range(event, *read_address, usize::from(*read_quantity))
                .num("writeAddress", (*write_address).into())
                .words("writeRegisters", values)
        }
        _ => event,
    };
    match result {
        Ok(_) => event.null("exception"),
        Err(code) => event.num("exception", u8::from(*code).into()),
    }
    .end()
}

/// The service of one connection. tokio-modbus owns it for as long as it
/// processes the connection and drops it when the connection ends, which is
/// what reports `connection-closed`.
struct Connection {
    device: Arc<Device>,
    peer: SocketAddr,
}

impl Connection {
    fn open(device: Arc<Device>, peer: SocketAddr) -> Self {
        device.connections.fetch_add(1, Ordering::SeqCst);
        emit(&Obj::new().str("event", "connection-opened").str("peer", &peer.to_string()).end());
        Self { device, peer }
    }
}

impl Drop for Connection {
    fn drop(&mut self) {
        emit(&Obj::new().str("event", "connection-closed").str("peer", &self.peer.to_string()).end());
        self.device.connections.fetch_sub(1, Ordering::SeqCst);
    }
}

impl Service for Connection {
    type Request = SlaveRequest<'static>;
    type Response = Response;
    type Exception = ExceptionCode;
    type Future = Ready<Result<Response, ExceptionCode>>;

    fn call(&self, request: SlaveRequest<'static>) -> Self::Future {
        let SlaveRequest { slave, request } = request;
        let result = self.device.answer(slave, &request);
        emit(&request_event(slave, &request, &result));
        ready(result)
    }
}

async fn serve(config: ServerConfig, device: Arc<Device>, fixture_name: String) -> i32 {
    let address = SocketAddr::new(config.bind_address, config.port);
    let listener = match TcpListener::bind(address).await {
        Ok(listener) => listener,
        Err(e) => {
            eprintln!("modbus-interop: cannot listen on {address}: {e}");
            return 1;
        }
    };
    // Installed before readiness: the process is PID 1 in its container and
    // would otherwise ignore the signals.
    let (mut terminate, mut interrupt) = match (signal(SignalKind::terminate()), signal(SignalKind::interrupt())) {
        (Ok(t), Ok(i)) => (t, i),
        (Err(e), _) | (_, Err(e)) => {
            eprintln!("modbus-interop: cannot install the signal handlers: {e}");
            return 1;
        }
    };
    let (stop, stopped) = tokio::sync::oneshot::channel::<()>();
    tokio::spawn(async move {
        tokio::select! {
            _ = terminate.recv() => {}
            _ = interrupt.recv() => {}
        }
        let _ = stop.send(());
    });

    if let Err(e) = std::fs::write(&config.ready_file, b"ready\n") {
        eprintln!("modbus-interop: cannot write {}: {e}", config.ready_file);
        return 1;
    }
    emit(
        &Obj::new()
            .str("event", "ready")
            .str("adapter", crate::ADAPTER)
            .str("address", &address.to_string())
            .str("fixture", &fixture_name)
            .num("unitId", device.unit_id.into())
            .end(),
    );

    let on_connected = |stream: TcpStream, peer: SocketAddr| {
        let device = Arc::clone(&device);
        async move {
            let _ = stream.set_nodelay(true);
            Ok(Some((Connection::open(device, peer), stream)))
        }
    };
    // A request the library cannot decode ends its connection without an answer.
    let on_process_error = |e: std::io::Error| eprintln!("modbus-interop: connection ended: {e}");
    let outcome = Server::new(listener)
        .serve_until(&on_connected, on_process_error, async move {
            let _ = stopped.await;
        })
        .await;

    let _ = std::fs::remove_file(&config.ready_file);
    // Leaving serve_until cancelled the connection tasks; each one drops its
    // service, which reports connection-closed. Give them a moment.
    for _ in 0..200 {
        if device.connections.load(Ordering::SeqCst) == 0 {
            break;
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    match outcome {
        Ok(_) => {
            emit(&Obj::new().str("event", "stopped").end());
            0
        }
        Err(e) => {
            eprintln!("modbus-interop: the listener failed: {e}");
            1
        }
    }
}

/// Runs the server until SIGTERM or SIGINT. Returns the exit code.
pub fn run(config: ServerConfig) -> i32 {
    let fixture = match crate::fixture::load(&config.fixture) {
        Ok(fixture) => fixture,
        Err(e) => {
            eprintln!("modbus-interop: invalid fixture: {e}");
            return 4;
        }
    };
    let runtime = match tokio::runtime::Builder::new_multi_thread().enable_all().build() {
        Ok(runtime) => runtime,
        Err(e) => {
            eprintln!("modbus-interop: cannot start the runtime: {e}");
            return 1;
        }
    };
    let name = fixture.name.clone();
    let code = runtime.block_on(serve(config, Arc::new(Device::new(fixture)), name));
    runtime.shutdown_timeout(Duration::from_secs(2));
    code
}
