// SPDX-License-Identifier: MIT

using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net.Sockets;
using System.Text.Json;
using System.Threading;
using NModbus;
using NModbus.Data;
using NModbus.Message;

namespace ModbusInterop.NModbusAdapter
{
    /// <summary>The request of the "raw" operation: any function code and the bytes after it.</summary>
    internal sealed class RawRequest : IModbusMessage
    {
        private readonly byte[] _data;

        public RawRequest(byte unitId, byte functionCode, byte[] data)
        {
            SlaveAddress = unitId;
            FunctionCode = functionCode;
            _data = data;
        }

        public byte FunctionCode { get; set; }

        public byte SlaveAddress { get; set; }

        public ushort TransactionId { get; set; }

        public byte[] MessageFrame => new[] { SlaveAddress }.Concat(ProtocolDataUnit).ToArray();

        public byte[] ProtocolDataUnit => new[] { FunctionCode }.Concat(_data).ToArray();

        public void Initialize(byte[] frame)
        {
            throw new NotSupportedException();
        }
    }

    /// <summary>The response of the "raw" operation, kept as it arrived.</summary>
    internal sealed class RawResponse : IModbusMessage
    {
        private byte[] _frame = new byte[2];

        public byte FunctionCode
        {
            get => _frame[1];
            set => _frame[1] = value;
        }

        public byte SlaveAddress
        {
            get => _frame[0];
            set => _frame[0] = value;
        }

        public ushort TransactionId { get; set; }

        public byte[] MessageFrame => _frame;

        public byte[] ProtocolDataUnit => _frame.Skip(1).ToArray();

        public void Initialize(byte[] frame)
        {
            if (frame == null || frame.Length < 2)
            {
                throw new FormatException("Message frame must contain at least 2 bytes of data.");
            }

            _frame = frame;
        }
    }

    /// <summary>A response NModbus handed out that is not a valid answer to the request.</summary>
    internal sealed class InvalidResponseException : Exception
    {
        public InvalidResponseException(string message)
            : base(message)
        {
        }
    }

    /// <summary>
    /// One connection: the NModbus master, the count of requests and normal
    /// responses, and a watchdog that bounds the wait for each response.
    /// </summary>
    internal sealed class Session : IDisposable
    {
        private readonly TcpClient _tcp;
        private readonly int _timeoutMs;
        private readonly Timer _watchdog;
        private volatile bool _expired;

        public Session(TcpClient tcp, IModbusMaster master, byte unitId, int timeoutMs)
        {
            _tcp = tcp;
            Master = master;
            UnitId = unitId;
            _timeoutMs = timeoutMs;
            _watchdog = new Timer(_ => Expire(), null, Timeout.Infinite, Timeout.Infinite);
        }

        public IModbusMaster Master { get; }

        public byte UnitId { get; }

        public int Requests { get; private set; }

        public int Responses { get; private set; }

        public bool Expired => _expired;

        /// <summary>One request and its response.</summary>
        public T Exchange<T>(Func<IModbusMaster, T> call)
        {
            Requests++;

            // The read timeout of the socket bounds every read; the watchdog
            // bounds the response as a whole.
            _watchdog.Change(_timeoutMs + 200, Timeout.Infinite);
            try
            {
                T result = call(Master);
                Responses++;
                return result;
            }
            finally
            {
                _watchdog.Change(Timeout.Infinite, Timeout.Infinite);
            }
        }

        public void Dispose()
        {
            _watchdog.Dispose();
            try
            {
                Master.Dispose();
            }
            catch (Exception e) when (e is IOException || e is SocketException || e is ObjectDisposedException)
            {
            }

            _tcp.Dispose();
        }

        private void Expire()
        {
            _expired = true;
            try
            {
                _tcp.Close();
            }
            catch (Exception e) when (e is IOException || e is SocketException || e is ObjectDisposedException)
            {
            }
        }
    }

    /// <summary>The "client" command: one bounded operation through an NModbus master.</summary>
    internal static class Client
    {
        public static readonly string[] Operations =
        {
            "read-coils", "read-discrete-inputs", "read-holding-registers", "read-input-registers",
            "write-single-coil", "write-single-register", "write-multiple-coils", "write-multiple-registers",
            "read-write-multiple-registers", "read-device-identification", "write-file-record", "raw",
        };

        /// <summary>
        /// Operations of the contract NModbus has no Modbus TCP API for, and
        /// why. Each is a usage error: nothing is sent.
        /// </summary>
        private static readonly Dictionary<string, string> Unsupported = new Dictionary<string, string>(StringComparer.Ordinal)
        {
            ["mask-write-register"] = "NModbus has no Mask Write Register (22)",
            ["diagnostics"] = "NModbus has no Diagnostics (08) for Modbus TCP: ReturnQueryData is on the serial master only",
            ["report-server-id"] = "NModbus has no Report Server ID (17)",
            ["read-file-record"] = "NModbus has no Read File Record (20)",
            ["read-fifo-queue"] = "NModbus has no Read FIFO Queue (24)",
        };

        private static readonly string[] Common = { "host", "port", "unit-id", "connect-timeout-ms", "timeout-ms", "repeat" };

        public static int Run(string[] args)
        {
            if (args.Length < 2)
            {
                throw new UsageException("client needs an operation");
            }

            string operation = args[1];
            if (Unsupported.TryGetValue(operation, out string? reason))
            {
                throw new UsageException(operation + " is not supported: " + reason);
            }

            if (!Operations.Contains(operation))
            {
                throw new UsageException("unknown operation '" + operation + "'");
            }

            var flags = new Flags(args, 2, Common.Concat(OperationFlags(operation)).ToArray());
            string host = flags.Text("host");
            if (host.Length == 0)
            {
                throw new UsageException("--host is empty");
            }

            int port = flags.Number("port", 1, 65535, 1502);
            byte unitId = (byte)flags.Number("unit-id", 0, 255, 1);
            int connectTimeoutMs = flags.Number("connect-timeout-ms", 1, 3600000, 5000);
            int timeoutMs = flags.Number("timeout-ms", 1, 3600000, 5000);
            int repeat = flags.Number("repeat", 1, 1000, 1);
            Func<Session, string> exchange = Plan(operation, flags, unitId);
            int function = FunctionOf(operation, flags);

            // Everything above is argument parsing. From here on the result
            // document is printed whatever happens.
            var clock = Stopwatch.StartNew();
            bool connected = false;
            int requests = 0;
            int responses = 0;
            string? response = null;
            string? errorCode = null;
            string? errorMessage = null;
            int exceptionFunction = 0;
            int exceptionCode = 0;

            TcpClient? tcp = Connect(host, port, connectTimeoutMs, out string? connectError);
            if (tcp == null)
            {
                errorCode = "connect-failed";
                errorMessage = connectError;
            }
            else
            {
                connected = true;
                Session? session = null;
                try
                {
                    tcp.NoDelay = true;
                    var factory = new ModbusFactory(functionServices: null, includeBuiltIn: true, logger: new StderrLogger());
                    IModbusMaster master = factory.CreateMaster(tcp);

                    // One request is one request: no retries, and "slave device
                    // busy" is an answer like any other exception (by default
                    // NModbus resends for ever on exception 6).
                    master.Transport.Retries = 0;
                    master.Transport.SlaveBusyUsesRetryCount = true;
                    master.Transport.WaitToRetryMilliseconds = 0;
                    master.Transport.ReadTimeout = timeoutMs;
                    master.Transport.WriteTimeout = timeoutMs;
                    session = new Session(tcp, master, unitId, timeoutMs);

                    for (int i = 0; i < repeat; i++)
                    {
                        string next = exchange(session);
                        if (response != null && next != response)
                        {
                            response = next;
                            errorCode = "failed";
                            errorMessage = "response " + (i + 1) + " differs from the one before";
                            break;
                        }

                        response = next;
                    }
                }
                catch (SlaveException e) when (e.FunctionCode != function + 128 || e.SlaveAddress != unitId)
                {
                    // NModbus raises an exception response before it compares
                    // the response with the request, so one that belongs to
                    // another function or unit identifier arrives here too.
                    errorCode = "invalid-response";
                    errorMessage = "exception response of function " + e.FunctionCode + " from unit " + e.SlaveAddress
                                   + " (code " + e.SlaveExceptionCode + ") to a request of function " + function
                                   + " for unit " + unitId;
                }
                catch (SlaveException e)
                {
                    errorCode = "exception";
                    errorMessage = "exception response, function " + e.FunctionCode + ", code " + e.SlaveExceptionCode;
                    exceptionFunction = e.FunctionCode;
                    exceptionCode = e.SlaveExceptionCode;
                }
                catch (Exception e)
                {
                    errorCode = Classify(e, session != null && session.Expired);
                    errorMessage = e.GetType().Name + ": " + e.Message;
                    Output.Diagnostic(e.ToString());
                }
                finally
                {
                    if (session != null)
                    {
                        requests = session.Requests;
                        responses = session.Responses;
                        session.Dispose();
                    }
                    else
                    {
                        tcp.Dispose();
                    }
                }
            }

            clock.Stop();
            Output.Line(writer =>
            {
                writer.WriteString("schemaVersion", "1.0");
                writer.WriteString("adapter", Program.Adapter);
                writer.WriteString("operation", operation);
                writer.WriteBoolean("ok", errorCode == null);
                if (errorCode == null)
                {
                    writer.WriteNull("error");
                }
                else
                {
                    writer.WriteStartObject("error");
                    writer.WriteString("code", errorCode);
                    writer.WriteString("message", errorMessage ?? string.Empty);
                    writer.WriteEndObject();
                }

                writer.WriteBoolean("connected", connected);
                writer.WriteNumber("unitId", unitId);
                writer.WriteNumber("requests", requests);
                writer.WriteNumber("responses", responses);
                writer.WritePropertyName("response");
                if (response == null)
                {
                    writer.WriteNullValue();
                }
                else
                {
                    writer.WriteRawValue(response);
                }

                if (errorCode == "exception")
                {
                    writer.WriteStartObject("exception");
                    writer.WriteNumber("function", exceptionFunction);
                    writer.WriteNumber("code", exceptionCode);
                    writer.WriteEndObject();
                }
                else
                {
                    writer.WriteNull("exception");
                }

                writer.WriteNumber("elapsedMs", Math.Round(clock.Elapsed.TotalMilliseconds, 3));
            });

            if (errorCode == null)
            {
                return 0;
            }

            return errorCode == "connect-failed" ? 3 : 1;
        }

        private static int FunctionOf(string operation, Flags flags)
        {
            switch (operation)
            {
                case "read-coils": return 1;
                case "read-discrete-inputs": return 2;
                case "read-holding-registers": return 3;
                case "read-input-registers": return 4;
                case "write-single-coil": return 5;
                case "write-single-register": return 6;
                case "write-multiple-coils": return 15;
                case "write-multiple-registers": return 16;
                case "read-write-multiple-registers": return 23;
                case "read-device-identification": return 43;
                case "write-file-record": return 21;
                default: return flags.Number("function", 1, 127);
            }
        }

        private static string[] OperationFlags(string operation)
        {
            switch (operation)
            {
                case "read-coils":
                case "read-discrete-inputs":
                case "read-holding-registers":
                case "read-input-registers":
                    return new[] { "address", "quantity" };
                case "write-single-coil":
                case "write-single-register":
                    return new[] { "address", "value" };
                case "write-multiple-coils":
                case "write-multiple-registers":
                    return new[] { "address", "values" };
                case "read-write-multiple-registers":
                    return new[] { "read-address", "read-quantity", "write-address", "values" };
                case "read-device-identification":
                    return new[] { "category" };
                case "write-file-record":
                    return new[] { "file", "record", "values" };
                default:
                    return new[] { "function", "data" };
            }
        }

        /// <summary>
        /// Checks the flags of the operation and returns what performs it once
        /// on a session and yields the "response" object of the result.
        /// </summary>
        private static Func<Session, string> Plan(string operation, Flags flags, byte unit)
        {
            switch (operation)
            {
                case "read-coils":
                case "read-discrete-inputs":
                {
                    ushort address = (ushort)flags.Number("address", 0, 65535);
                    ushort quantity = (ushort)flags.Number("quantity", 1, 2000);
                    bool coils = operation == "read-coils";
                    return session =>
                    {
                        bool[] bits = session.Exchange(m => coils
                            ? m.ReadCoils(unit, address, quantity)
                            : m.ReadInputs(unit, address, quantity));
                        if (bits.Length != quantity)
                        {
                            throw new InvalidResponseException("got " + bits.Length + " bits, asked for " + quantity);
                        }

                        return Output.Text(w =>
                        {
                            w.WriteNumber("function", coils ? 1 : 2);
                            w.WriteStartArray("bits");
                            foreach (bool bit in bits)
                            {
                                w.WriteBooleanValue(bit);
                            }

                            w.WriteEndArray();
                        });
                    };
                }

                case "read-holding-registers":
                case "read-input-registers":
                {
                    ushort address = (ushort)flags.Number("address", 0, 65535);
                    ushort quantity = (ushort)flags.Number("quantity", 1, 125);
                    bool holding = operation == "read-holding-registers";
                    return session =>
                    {
                        ushort[] registers = session.Exchange(m => holding
                            ? m.ReadHoldingRegisters(unit, address, quantity)
                            : m.ReadInputRegisters(unit, address, quantity));
                        return Registers(holding ? 3 : 4, registers, quantity);
                    };
                }

                case "write-single-coil":
                {
                    ushort address = (ushort)flags.Number("address", 0, 65535);
                    bool value = Flags.ParseBool("--value", flags.Text("value"));
                    return session =>
                    {
                        // NModbus compares the echo with the request and hands
                        // nothing out: these are the values it confirmed.
                        session.Exchange(m =>
                        {
                            m.WriteSingleCoil(unit, address, value);
                            return true;
                        });
                        return Output.Text(w =>
                        {
                            w.WriteNumber("function", 5);
                            w.WriteNumber("address", address);
                            w.WriteBoolean("value", value);
                        });
                    };
                }

                case "write-single-register":
                {
                    ushort address = (ushort)flags.Number("address", 0, 65535);
                    ushort value = (ushort)flags.Number("value", 0, 65535);
                    return session =>
                    {
                        session.Exchange(m =>
                        {
                            m.WriteSingleRegister(unit, address, value);
                            return true;
                        });
                        return Output.Text(w =>
                        {
                            w.WriteNumber("function", 6);
                            w.WriteNumber("address", address);
                            w.WriteNumber("value", value);
                        });
                    };
                }

                case "write-multiple-coils":
                {
                    ushort address = (ushort)flags.Number("address", 0, 65535);
                    bool[] values = List(flags.Text("values"), 1968).Select(v => Flags.ParseBool("--values", v)).ToArray();
                    return session =>
                    {
                        session.Exchange(m =>
                        {
                            m.WriteMultipleCoils(unit, address, values);
                            return true;
                        });
                        return Written(15, address, values.Length);
                    };
                }

                case "write-multiple-registers":
                {
                    ushort address = (ushort)flags.Number("address", 0, 65535);
                    ushort[] values = RegisterList(flags.Text("values"), 123);
                    return session =>
                    {
                        session.Exchange(m =>
                        {
                            m.WriteMultipleRegisters(unit, address, values);
                            return true;
                        });
                        return Written(16, address, values.Length);
                    };
                }

                case "read-write-multiple-registers":
                {
                    ushort readAddress = (ushort)flags.Number("read-address", 0, 65535);
                    ushort readQuantity = (ushort)flags.Number("read-quantity", 1, 125);
                    ushort writeAddress = (ushort)flags.Number("write-address", 0, 65535);
                    ushort[] values = RegisterList(flags.Text("values"), 121);
                    return session =>
                    {
                        ushort[] registers = session.Exchange(
                            m => m.ReadWriteMultipleRegisters(unit, readAddress, readQuantity, writeAddress, values));
                        return Registers(23, registers, readQuantity);
                    };
                }

                case "read-device-identification":
                {
                    DeviceIdCategory category;
                    switch (flags.Text("category", "basic"))
                    {
                        case "basic":
                            category = DeviceIdCategory.Basic;
                            break;
                        case "regular":
                            category = DeviceIdCategory.Regular;
                            break;
                        case "extended":
                            category = DeviceIdCategory.Extended;
                            break;
                        default:
                            throw new UsageException("--category must be basic, regular or extended");
                    }

                    return session => DeviceIdentification(session, unit, category);
                }

                case "write-file-record":
                {
                    ushort file = (ushort)flags.Number("file", 1, 65535);
                    ushort record = (ushort)flags.Number("record", 0, 9999);
                    ushort[] values = RegisterList(flags.Text("values"), 122);
                    byte[] data = new byte[values.Length * 2];
                    for (int i = 0; i < values.Length; i++)
                    {
                        data[2 * i] = (byte)(values[i] >> 8);
                        data[2 * i + 1] = (byte)values[i];
                    }

                    return session =>
                    {
                        // One sub-request, reference type 6. NModbus compares
                        // the file number and record number of the echo with
                        // the request and hands nothing out: these are the
                        // values it confirmed.
                        session.Exchange(m =>
                        {
                            m.WriteFileRecord(unit, file, record, data);
                            return true;
                        });
                        return Output.Text(w =>
                        {
                            w.WriteNumber("function", 21);
                            w.WriteNumber("file", file);
                            w.WriteNumber("record", record);
                            w.WriteNumber("quantity", values.Length);
                        });
                    };
                }

                default:
                {
                    byte function = (byte)flags.Number("function", 1, 127);
                    byte[] data = Flags.ParseHex("--data", flags.Text("data"));
                    if (data.Length > 65533)
                    {
                        throw new UsageException("--data is longer than a Modbus TCP frame can carry");
                    }

                    return session =>
                    {
                        // ExecuteCustomMessage is the NModbus entry point for a
                        // message of the application's own.
                        RawResponse answer = session.Exchange(
                            m => m.ExecuteCustomMessage<RawResponse>(new RawRequest(unit, function, data)));
                        byte[] pdu = answer.ProtocolDataUnit;
                        return Output.Text(w =>
                        {
                            w.WriteNumber("function", pdu[0]);
                            w.WriteString("data", Convert.ToHexString(pdu, 1, pdu.Length - 1).ToLowerInvariant());
                        });
                    };
                }
            }
        }

        /// <summary>
        /// Read Device Identification, stream access from object 0, following
        /// "more follows". NModbus's ReadDeviceIdentification returns only the
        /// objects of one response, so the request and response classes it is
        /// built from are used through ExecuteCustomMessage.
        /// </summary>
        private static string DeviceIdentification(Session session, byte unit, DeviceIdCategory category)
        {
            var objects = new SortedDictionary<byte, string>();
            byte conformity = 0;
            byte objectId = 0;
            while (true)
            {
                byte from = objectId;
                ReadDeviceIdResponse part = session.Exchange(
                    m => m.ExecuteCustomMessage<ReadDeviceIdResponse>(new ReadDeviceIdRequest(unit, category, from)));
                if (part.ReadDeviceIdCode != category)
                {
                    throw new InvalidResponseException(
                        "read device id code " + (byte)part.ReadDeviceIdCode + " in the response, " + (byte)category + " in the request");
                }

                conformity = part.ConformityLevel;
                foreach (KeyValuePair<byte, string> item in part.Objects)
                {
                    objects[item.Key] = item.Value;
                }

                if (!part.MoreFollows)
                {
                    break;
                }

                if (part.NextObjectId <= from)
                {
                    throw new InvalidResponseException("more follows, but the next object id " + part.NextObjectId + " does not advance");
                }

                objectId = part.NextObjectId;
            }

            return Output.Text(w =>
            {
                w.WriteNumber("function", 43);
                w.WriteNumber("conformityLevel", conformity);
                w.WriteStartArray("objects");
                foreach (KeyValuePair<byte, string> item in objects)
                {
                    w.WriteStartObject();
                    w.WriteNumber("id", item.Key);
                    w.WriteString("value", item.Value);
                    w.WriteEndObject();
                }

                w.WriteEndArray();
            });
        }

        private static string Registers(int function, ushort[] registers, int quantity)
        {
            if (registers.Length != quantity)
            {
                throw new InvalidResponseException("got " + registers.Length + " registers, asked for " + quantity);
            }

            return Output.Text(w =>
            {
                w.WriteNumber("function", function);
                w.WriteStartArray("registers");
                foreach (ushort register in registers)
                {
                    w.WriteNumberValue(register);
                }

                w.WriteEndArray();
            });
        }

        private static string Written(int function, int address, int quantity)
        {
            return Output.Text(w =>
            {
                w.WriteNumber("function", function);
                w.WriteNumber("address", address);
                w.WriteNumber("quantity", quantity);
            });
        }

        private static string[] List(string text, int max)
        {
            string[] items = text.Split(',');
            if (text.Length == 0 || items.Length > max)
            {
                throw new UsageException("--values needs 1.." + max + " values");
            }

            return items;
        }

        private static ushort[] RegisterList(string text, int max)
        {
            return List(text, max).Select(v => (ushort)Flags.ParseNumber("--values", v, 0, 65535)).ToArray();
        }

        private static TcpClient? Connect(string host, int port, int timeoutMs, out string? error)
        {
            error = null;
            var tcp = new TcpClient();
            try
            {
                using var deadline = new CancellationTokenSource(timeoutMs);
                tcp.ConnectAsync(host, port, deadline.Token).AsTask().GetAwaiter().GetResult();
                return tcp;
            }
            catch (OperationCanceledException)
            {
                error = "no connection to " + host + ":" + port + " within " + timeoutMs + " ms";
            }
            catch (Exception e) when (e is SocketException || e is IOException || e is ArgumentException
                                      || e is ObjectDisposedException || e is InvalidOperationException)
            {
                error = "no connection to " + host + ":" + port + ": " + e.Message;
            }

            tcp.Dispose();
            return null;
        }

        /// <summary>
        /// NModbus reports everything that goes wrong after the request as an
        /// IOException, a SocketException or a parsing exception. This maps
        /// them to the error codes of the contract.
        /// </summary>
        private static string Classify(Exception e, bool expired)
        {
            if (expired)
            {
                return "timeout";
            }

            for (Exception? inner = e; inner != null; inner = inner.InnerException)
            {
                if (inner is SocketException socket)
                {
                    return socket.SocketErrorCode == SocketError.TimedOut || socket.SocketErrorCode == SocketError.WouldBlock
                        ? "timeout"
                        : "connection-lost";
                }
            }

            if (e is TimeoutException)
            {
                return "timeout";
            }

            if (e is ObjectDisposedException)
            {
                return "connection-lost";
            }

            if (e is IOException)
            {
                // ModbusIpTransport: the peer closed the connection.
                if (e.InnerException != null || e.Message.StartsWith("Read resulted in 0 bytes", StringComparison.Ordinal))
                {
                    return "connection-lost";
                }

                // ModbusTransport.ValidateResponse and the ValidateResponse of
                // the request: wrong function code, unit identifier, transaction
                // identifier, byte count or echo.
                return "invalid-response";
            }

            if (e is InvalidResponseException || e is SlaveException || e is FormatException || e is InvalidCastException
                || e is IndexOutOfRangeException || e is ArgumentException || e is OverflowException)
            {
                return "invalid-response";
            }

            return "failed";
        }
    }
}
