// SPDX-License-Identifier: MIT

//! The `client` command: one bounded operation, one result document.
//!
//! The requests go through tokio-modbus's TCP client context
//! (`tokio_modbus::client::Context`): the `Reader` methods for the reads,
//! `Client::call` for the writes (it hands out the echo, which the `Writer`
//! methods check only in debug builds) and `Request::Custom` for `raw`.
//! The library knows no timeouts; every wait is wrapped in one here.

use std::borrow::Cow;
use std::io;
use std::pin::Pin;
use std::sync::{Arc, Mutex};
use std::task::{Context as TaskContext, Poll};
use std::time::{Duration, Instant};

use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
use tokio::net::TcpStream;
use tokio::time::error::Elapsed;
use tokio_modbus::client::{tcp, Context};
use tokio_modbus::prelude::*;
use tokio_modbus::{Error, ExceptionResponse, ProtocolError};

use crate::args::{Category, ClientConfig, Op};
use crate::json::{self, Obj};

/// What the transport saw since the last request: the bytes received and
/// whether the peer closed the connection.
#[derive(Debug, Default)]
struct Seen {
    received: Vec<u8>,
    closed: bool,
}

/// The TCP stream handed to tokio-modbus. It only observes: `raw` returns the
/// response PDU as it arrived, which the library does not hand out for the
/// function codes it decodes itself, and a connection closed by the peer has
/// to be told from other failures (the library reports it as whatever the
/// last OS error happened to be).
#[derive(Debug)]
struct Observed {
    stream: TcpStream,
    seen: Arc<Mutex<Seen>>,
}

impl AsyncRead for Observed {
    fn poll_read(self: Pin<&mut Self>, cx: &mut TaskContext<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        let before = buf.filled().len();
        let room = buf.remaining();
        let poll = Pin::new(&mut this.stream).poll_read(cx, buf);
        if let Poll::Ready(Ok(())) = &poll {
            let mut seen = this.seen.lock().unwrap_or_else(|e| e.into_inner());
            let new = &buf.filled()[before..];
            if new.is_empty() && room > 0 {
                seen.closed = true;
            }
            seen.received.extend_from_slice(new);
        }
        poll
    }
}

impl AsyncWrite for Observed {
    fn poll_write(self: Pin<&mut Self>, cx: &mut TaskContext<'_>, buf: &[u8]) -> Poll<io::Result<usize>> {
        Pin::new(&mut self.get_mut().stream).poll_write(cx, buf)
    }

    fn poll_flush(self: Pin<&mut Self>, cx: &mut TaskContext<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.get_mut().stream).poll_flush(cx)
    }

    fn poll_shutdown(self: Pin<&mut Self>, cx: &mut TaskContext<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.get_mut().stream).poll_shutdown(cx)
    }
}

/// Why a request did not get its normal response.
enum Failure {
    /// The device answered with an exception response of this code.
    Exception(u8),
    /// An `error.code` of the contract and a message for people.
    Error(&'static str, String),
}

struct Session {
    context: Context,
    seen: Arc<Mutex<Seen>>,
    timeout: Duration,
    requests: u64,
    responses: u64,
}

impl Session {
    fn closed_by_peer(&self) -> bool {
        self.seen.lock().unwrap_or_else(|e| e.into_inner()).closed
    }

    /// Maps the library's nested result: the outer error is the transport or
    /// the protocol, the inner one an exception response.
    fn settle<T>(&self, result: tokio_modbus::Result<T>) -> Result<T, Failure> {
        match result {
            Ok(Ok(value)) => Ok(value),
            Ok(Err(exception)) => Err(Failure::Exception(exception.into())),
            // The transaction or unit identifier, or the function code, is
            // not the one of the request.
            Err(Error::Protocol(e)) => Err(Failure::Error("invalid-response", e.to_string())),
            Err(Error::Transport(e)) => {
                use io::ErrorKind::*;
                let code = match e.kind() {
                    // The codec could not decode what arrived.
                    InvalidData => "invalid-response",
                    UnexpectedEof | ConnectionReset | ConnectionAborted | BrokenPipe | NotConnected => {
                        "connection-lost"
                    }
                    _ if self.closed_by_peer() => "connection-lost",
                    _ => "failed",
                };
                let message = if code == "connection-lost" {
                    format!("the connection closed before the response ({e})")
                } else {
                    e.to_string()
                };
                Err(Failure::Error(code, message))
            }
        }
    }

    /// Before a request: forget what the last one received, count this one.
    fn begin(&mut self) {
        self.seen.lock().unwrap_or_else(|e| e.into_inner()).received.clear();
        self.requests += 1;
    }

    /// After the bounded wait for a response.
    fn timed<T>(&self, waited: Result<tokio_modbus::Result<T>, Elapsed>) -> Result<tokio_modbus::Result<T>, Failure> {
        waited.map_err(|_| Failure::Error("timeout", format!("no response within {} ms", self.timeout.as_millis())))
    }

    /// The PDU of the response the library just accepted, after the function
    /// code, taken from the bytes that arrived.
    fn received_data(&self, function: u8) -> Result<Vec<u8>, Failure> {
        let seen = self.seen.lock().unwrap_or_else(|e| e.into_inner());
        let adu = &seen.received;
        let length = if adu.len() >= 8 { usize::from(u16::from_be_bytes([adu[4], adu[5]])) } else { 0 };
        if length < 2 || adu.len() < 6 + length || adu[7] != function {
            return Err(Failure::Error("failed", "the response frame was not captured".into()));
        }
        Ok(adu[8..6 + length].to_vec())
    }
}

/// Sends one request through a method of the client context and waits for
/// its response, at most the session's timeout: `Result<tokio_modbus::Result<T>, Failure>`.
macro_rules! ask {
    ($session:ident.$method:ident($($argument:expr),*)) => {{
        $session.begin();
        let waited = tokio::time::timeout($session.timeout, $session.context.$method($($argument),*)).await;
        $session.timed(waited)
    }};
}

/// `ask!`, with the library's result mapped: `Result<T, Failure>`.
macro_rules! exchange {
    ($session:ident.$method:ident($($argument:expr),*)) => {{
        match ask!($session.$method($($argument),*)) {
            Ok(result) => $session.settle(result),
            Err(failure) => Err(failure),
        }
    }};
}

fn invalid(message: String) -> Failure {
    Failure::Error("invalid-response", message)
}

fn function_of(result: &Result<Response, ExceptionResponse>) -> u8 {
    match result {
        Ok(response) => response.function_code().value(),
        Err(exception) => exception.function.value(),
    }
}

/// Runs the operation once. Returns the `response` object of the result
/// document.
async fn once(session: &mut Session, op: &Op) -> Result<String, Failure> {
    let function = u64::from(op.function());
    let response = Obj::new().num("function", function);
    match op {
        Op::ReadCoils { address, quantity } | Op::ReadDiscreteInputs { address, quantity } => {
            let (address, quantity) = (*address, *quantity);
            let bits = if matches!(op, Op::ReadCoils { .. }) {
                exchange!(session.read_coils(address, quantity))?
            } else {
                exchange!(session.read_discrete_inputs(address, quantity))?
            };
            // The library cuts the bits of the last byte down to the quantity.
            if bits.len() != usize::from(quantity) {
                return Err(invalid(format!("{} bits in the response, {quantity} requested", bits.len())));
            }
            session.responses += 1;
            Ok(response.bits("bits", &bits).end())
        }
        Op::ReadHoldingRegisters { address, quantity } | Op::ReadInputRegisters { address, quantity } => {
            let (address, quantity) = (*address, *quantity);
            let registers = if matches!(op, Op::ReadHoldingRegisters { .. }) {
                exchange!(session.read_holding_registers(address, quantity))?
            } else {
                exchange!(session.read_input_registers(address, quantity))?
            };
            if registers.len() != usize::from(quantity) {
                return Err(invalid(format!(
                    "{} registers in the response, {quantity} requested",
                    registers.len()
                )));
            }
            session.responses += 1;
            Ok(response.words("registers", &registers).end())
        }
        Op::WriteSingleCoil { address, value } => {
            let request = Request::WriteSingleCoil(*address, *value);
            match exchange!(session.call(request))? {
                Response::WriteSingleCoil(a, v) if a == *address && v == *value => {
                    session.responses += 1;
                    Ok(response.num("address", a.into()).boolean("value", v).end())
                }
                other => Err(invalid(format!("the response does not echo the request: {other:?}"))),
            }
        }
        Op::WriteSingleRegister { address, value } => {
            let request = Request::WriteSingleRegister(*address, *value);
            match exchange!(session.call(request))? {
                Response::WriteSingleRegister(a, v) if a == *address && v == *value => {
                    session.responses += 1;
                    Ok(response.num("address", a.into()).num("value", v.into()).end())
                }
                other => Err(invalid(format!("the response does not echo the request: {other:?}"))),
            }
        }
        Op::WriteMultipleCoils { address, values } => {
            let request = Request::WriteMultipleCoils(*address, Cow::Borrowed(values));
            match exchange!(session.call(request))? {
                Response::WriteMultipleCoils(a, q) if a == *address && usize::from(q) == values.len() => {
                    session.responses += 1;
                    Ok(response.num("address", a.into()).num("quantity", q.into()).end())
                }
                other => Err(invalid(format!("the response does not echo the request: {other:?}"))),
            }
        }
        Op::WriteMultipleRegisters { address, values } => {
            let request = Request::WriteMultipleRegisters(*address, Cow::Borrowed(values));
            match exchange!(session.call(request))? {
                Response::WriteMultipleRegisters(a, q) if a == *address && usize::from(q) == values.len() => {
                    session.responses += 1;
                    Ok(response.num("address", a.into()).num("quantity", q.into()).end())
                }
                other => Err(invalid(format!("the response does not echo the request: {other:?}"))),
            }
        }
        Op::MaskWriteRegister { address, and_mask, or_mask } => {
            let request = Request::MaskWriteRegister(*address, *and_mask, *or_mask);
            match exchange!(session.call(request))? {
                Response::MaskWriteRegister(a, and, or) if a == *address && and == *and_mask && or == *or_mask => {
                    session.responses += 1;
                    Ok(response
                        .num("address", a.into())
                        .num("andMask", and.into())
                        .num("orMask", or.into())
                        .end())
                }
                other => Err(invalid(format!("the response does not echo the request: {other:?}"))),
            }
        }
        Op::ReadWriteMultipleRegisters { read_address, read_quantity, write_address, values } => {
            let (read_address, read_quantity, write_address) = (*read_address, *read_quantity, *write_address);
            let registers =
                exchange!(session.read_write_multiple_registers(read_address, read_quantity, write_address, values))?;
            if registers.len() != usize::from(read_quantity) {
                return Err(invalid(format!(
                    "{} registers in the response, {read_quantity} requested",
                    registers.len()
                )));
            }
            session.responses += 1;
            Ok(response.words("registers", &registers).end())
        }
        Op::ReadDeviceIdentification { category } => {
            let read_code = match category {
                Category::Basic => ReadCode::Basic,
                Category::Regular => ReadCode::Regular,
                Category::Extended => ReadCode::Extended,
            };
            // Stream access from object 0, following "more follows".
            let mut objects: Vec<DeviceIdObject> = Vec::new();
            let mut object_id = 0u8;
            let conformity_level = loop {
                let part = exchange!(session.read_device_identification(read_code, object_id))?;
                objects.extend(part.device_id_objects);
                // The stream must advance, or it would never end.
                if part.more_follows && (part.next_object_id <= object_id || objects.len() > 256) {
                    return Err(invalid(format!(
                        "more follows, but the next object id is {} after {object_id}",
                        part.next_object_id
                    )));
                }
                session.responses += 1;
                if !part.more_follows {
                    break part.conformity_level;
                }
                object_id = part.next_object_id;
            };
            objects.sort_by_key(|o| o.id);
            let list: Vec<String> = objects
                .iter()
                .map(|o| {
                    Obj::new()
                        .num("id", o.id.into())
                        .str("value", &String::from_utf8_lossy(&o.value))
                        .end()
                })
                .collect();
            Ok(response
                .num("conformityLevel", conformity_level.value().into())
                .raw("objects", &format!("[{}]", list.join(",")))
                .end())
        }
        Op::Raw { function, data } => {
            let function = *function;
            let request = Request::Custom(function, Cow::Borrowed(data));
            let result = ask!(session.call(request))?;
            // The library compares the function codes of request and response
            // as enum values. A custom request has `FunctionCode::Custom(n)`
            // even when n is a code the library has a name for, and the
            // response then has the named one: a correct answer comes back as
            // a mismatch that carries the result. Same number, same function.
            let result = match result {
                Err(Error::Protocol(ProtocolError::FunctionCodeMismatch { request, result }))
                    if function_of(&result) == request.value() =>
                {
                    Ok(result.map_err(|e| e.exception))
                }
                other => other,
            };
            session.settle(result)?;
            let data = session.received_data(function)?;
            session.responses += 1;
            Ok(response.str("data", &json::hex(&data)).end())
        }
    }
}

struct Outcome {
    connected: bool,
    requests: u64,
    responses: u64,
    response: Option<String>,
    exception: Option<u8>,
    error: Option<(&'static str, String)>,
}

async fn connect(host: &str, port: u16) -> io::Result<TcpStream> {
    let mut last = io::Error::new(io::ErrorKind::NotFound, format!("{host} has no address"));
    for address in tokio::net::lookup_host((host, port)).await? {
        match TcpStream::connect(address).await {
            Ok(stream) => return Ok(stream),
            Err(e) => last = e,
        }
    }
    Err(last)
}

async fn operate(config: &ClientConfig) -> Outcome {
    let mut outcome = Outcome {
        connected: false,
        requests: 0,
        responses: 0,
        response: None,
        exception: None,
        error: None,
    };
    let stream = match tokio::time::timeout(config.connect_timeout, connect(&config.host, config.port)).await {
        Ok(Ok(stream)) => stream,
        Ok(Err(e)) => {
            outcome.error = Some(("connect-failed", e.to_string()));
            return outcome;
        }
        Err(_) => {
            outcome.error = Some((
                "connect-failed",
                format!("no connection within {} ms", config.connect_timeout.as_millis()),
            ));
            return outcome;
        }
    };
    outcome.connected = true;
    let _ = stream.set_nodelay(true);

    let seen = Arc::new(Mutex::new(Seen::default()));
    let observed = Observed {
        stream,
        seen: Arc::clone(&seen),
    };
    let mut session = Session {
        context: tcp::attach_slave(observed, Slave(config.unit_id)),
        seen,
        timeout: config.timeout,
        requests: 0,
        responses: 0,
    };

    let mut first: Option<String> = None;
    let mut differ = false;
    for _ in 0..config.repeat {
        match once(&mut session, &config.op).await {
            Ok(response) => {
                match &first {
                    None => first = Some(response.clone()),
                    Some(first) => differ |= *first != response,
                }
                outcome.response = Some(response);
            }
            Err(Failure::Exception(code)) => {
                outcome.exception = Some(code);
                outcome.error = Some(("exception", format!("the device answered with exception {code}")));
                break;
            }
            Err(Failure::Error(code, message)) => {
                outcome.error = Some((code, message));
                break;
            }
        }
    }
    if outcome.error.is_none() && differ {
        outcome.error = Some(("failed", "the responses to the repeated request differ".into()));
    }
    outcome.requests = session.requests;
    outcome.responses = session.responses;
    let _ = tokio::time::timeout(config.timeout, session.context.disconnect()).await;
    outcome
}

/// Runs the operation, prints the result document and returns the exit code.
pub fn run(config: ClientConfig) -> i32 {
    let started = Instant::now();
    let runtime = match tokio::runtime::Builder::new_current_thread().enable_all().build() {
        Ok(runtime) => runtime,
        Err(e) => {
            eprintln!("modbus-interop: cannot start the runtime: {e}");
            return 1;
        }
    };
    let outcome = runtime.block_on(operate(&config));
    // A name lookup that outlived the connect timeout must not hold up the exit.
    runtime.shutdown_background();

    if let Some((code, message)) = &outcome.error {
        eprintln!("modbus-interop: {}: {code}: {message}", config.operation);
    }
    let elapsed = (started.elapsed().as_secs_f64() * 1_000_000.0).round() / 1000.0;
    let document = Obj::new()
        .str("schemaVersion", "1.0")
        .str("adapter", crate::ADAPTER)
        .str("operation", &config.operation)
        .boolean("ok", outcome.error.is_none())
        .raw(
            "error",
            &match &outcome.error {
                Some((code, message)) => Obj::new().str("code", code).str("message", message).end(),
                None => "null".to_string(),
            },
        )
        .boolean("connected", outcome.connected)
        .num("unitId", config.unit_id.into())
        .num("requests", outcome.requests)
        .num("responses", outcome.responses)
        .raw("response", outcome.response.as_deref().unwrap_or("null"))
        .raw(
            "exception",
            &match outcome.exception {
                Some(code) => Obj::new()
                    .num("function", u64::from(config.op.function()) + 128)
                    .num("code", code.into())
                    .end(),
                None => "null".to_string(),
            },
        )
        .raw("elapsedMs", &format!("{elapsed}"))
        .end();
    json::emit(&document);

    match &outcome.error {
        None => 0,
        Some(("connect-failed", _)) => 3,
        Some(_) => 1,
    }
}
