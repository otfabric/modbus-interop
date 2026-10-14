// SPDX-License-Identifier: MIT

using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json;
using NModbus;
using NModbus.Device;
using NModbus.Device.MessageHandlers;
using NModbus.Message;

namespace ModbusInterop.NModbusAdapter
{
    /// <summary>
    /// One table of the fixture as an NModbus point source: the block the
    /// fixture defines and nothing else. NModbus leaves address checking to
    /// the point source; an InvalidModbusRequestException thrown here becomes
    /// the exception response.
    /// </summary>
    internal sealed class BlockPointSource<T> : IPointSource<T>
    {
        private readonly object _gate;
        private readonly int _address;
        private readonly T[] _values;

        public BlockPointSource(Block<T>? block, object gate)
        {
            _gate = gate;
            _address = block?.Address ?? 0;
            _values = block?.Values.ToArray() ?? Array.Empty<T>();
        }

        public bool Contains(int startAddress, int count)
        {
            return count >= 0 && startAddress >= _address && startAddress + count <= _address + _values.Length;
        }

        public T[] ReadPoints(ushort startAddress, ushort numberOfPoints)
        {
            lock (_gate)
            {
                Check(startAddress, numberOfPoints);
                var points = new T[numberOfPoints];
                Array.Copy(_values, startAddress - _address, points, 0, numberOfPoints);
                return points;
            }
        }

        public void WritePoints(ushort startAddress, T[] points)
        {
            lock (_gate)
            {
                Check(startAddress, points.Length);
                Array.Copy(points, 0, _values, startAddress - _address, points.Length);
            }
        }

        private void Check(int startAddress, int count)
        {
            if (!Contains(startAddress, count))
            {
                throw new InvalidModbusRequestException(SlaveExceptionCodes.IllegalDataAddress);
            }
        }
    }

    /// <summary>The device state: shared by every connection, alive as long as the process.</summary>
    internal sealed class FixtureDataStore : ISlaveDataStore
    {
        public FixtureDataStore(Fixture fixture)
        {
            Coils = new BlockPointSource<bool>(fixture.Coils, Gate);
            Inputs = new BlockPointSource<bool>(fixture.DiscreteInputs, Gate);
            Holding = new BlockPointSource<ushort>(fixture.HoldingRegisters, Gate);
            InputRegs = new BlockPointSource<ushort>(fixture.InputRegisters, Gate);
        }

        /// <summary>One lock for the whole device, so that FC23 is one step.</summary>
        public object Gate { get; } = new object();

        public BlockPointSource<bool> Coils { get; }

        public BlockPointSource<bool> Inputs { get; }

        public BlockPointSource<ushort> Holding { get; }

        public BlockPointSource<ushort> InputRegs { get; }

        public IPointSource<bool> CoilDiscretes => Coils;

        public IPointSource<bool> CoilInputs => Inputs;

        public IPointSource<ushort> HoldingRegisters => Holding;

        public IPointSource<ushort> InputRegisters => InputRegs;
    }

    /// <summary>
    /// A request the adapter does not interpret: one for a function code
    /// without a service of its own, or one whose quantity the NModbus message
    /// class refused to hold.
    /// </summary>
    internal sealed class OpaqueRequest : IModbusMessage
    {
        private byte[] _frame = new byte[2];

        public OpaqueRequest()
        {
        }

        public OpaqueRequest(byte[] frame, byte rejection)
        {
            Initialize(frame);
            Rejection = rejection;
        }

        /// <summary>Exception code this request is answered with, 0 for none.</summary>
        public byte Rejection { get; }

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

        public byte[] MessageFrame => _frame;

        public byte[] ProtocolDataUnit => _frame.Skip(1).ToArray();

        public ushort TransactionId { get; set; }

        public void Initialize(byte[] frame)
        {
            if (frame == null || frame.Length < 2)
            {
                throw new FormatException("Message frame must contain at least 2 bytes of data.");
            }

            _frame = frame;
        }
    }

    /// <summary>
    /// The function service for a function code that is always refused with
    /// one exception code. Registered in the factory for every function the
    /// device does not have (illegal function), and as the only kind of
    /// service of the slaves that stand for the other unit identifiers
    /// (gateway target device failed to respond).
    /// </summary>
    internal sealed class RefusingService : IModbusFunctionService
    {
        private readonly byte _exceptionCode;

        public RefusingService(byte functionCode, byte exceptionCode)
        {
            FunctionCode = functionCode;
            _exceptionCode = exceptionCode;
        }

        public byte FunctionCode { get; }

        public IModbusMessage CreateRequest(byte[] frame)
        {
            return new OpaqueRequest(frame, 0);
        }

        public IModbusMessage HandleSlaveRequest(IModbusMessage request, ISlaveDataStore dataStore)
        {
            throw new InvalidModbusRequestException(_exceptionCode);
        }

        public int GetRtuRequestBytesToRead(byte[] frameStart)
        {
            throw new NotSupportedException();
        }

        public int GetRtuResponseBytesToRead(byte[] frameStart)
        {
            throw new NotSupportedException();
        }
    }

    /// <summary>
    /// Wraps one of NModbus's own function services and adds the checks of the
    /// request that docs/FIXTURES.md asks for and the service does not make.
    /// The service itself still parses the request, changes the data store and
    /// builds the response.
    /// </summary>
    internal sealed class CheckedService : IModbusFunctionService
    {
        private const ushort CoilOn = 0xFF00;
        private const ushort CoilOff = 0x0000;

        private readonly IModbusFunctionService _inner;

        public CheckedService(IModbusFunctionService inner)
        {
            _inner = inner;
        }

        public byte FunctionCode => _inner.FunctionCode;

        public IModbusMessage CreateRequest(byte[] frame)
        {
            byte rejection = CheckFrame(frame);
            if (rejection != 0)
            {
                return new OpaqueRequest(frame, rejection);
            }

            try
            {
                return _inner.CreateRequest(frame);
            }
            catch (ArgumentOutOfRangeException)
            {
                // The NModbus request classes refuse a quantity above their own
                // limit (2040 bits, 127 registers) with this exception, which
                // would end the connection. Keep the request and answer it.
                return new OpaqueRequest(frame, SlaveExceptionCodes.IllegalDataValue);
            }
        }

        public IModbusMessage HandleSlaveRequest(IModbusMessage request, ISlaveDataStore dataStore)
        {
            if (request is OpaqueRequest opaque)
            {
                throw new InvalidModbusRequestException(opaque.Rejection);
            }

            var store = (FixtureDataStore)dataStore;
            lock (store.Gate)
            {
                Check(request, store);
                return _inner.HandleSlaveRequest(request, dataStore);
            }
        }

        public int GetRtuRequestBytesToRead(byte[] frameStart)
        {
            return _inner.GetRtuRequestBytesToRead(frameStart);
        }

        public int GetRtuResponseBytesToRead(byte[] frameStart)
        {
            return _inner.GetRtuResponseBytesToRead(frameStart);
        }

        /// <summary>
        /// Checks made on the frame (unit identifier, function code, data)
        /// before the NModbus message class sees it, for requests whose
        /// message class is internal to NModbus. Returns the exception code to
        /// answer with, 0 to hand the frame to the service.
        /// </summary>
        private static byte CheckFrame(byte[] frame)
        {
            if (frame.Length >= 2 && frame[1] == ModbusFunctionCodes.Diagnostics)
            {
                // DiagnosticsService answers every sub-function with the echo
                // of the request, and its message class keeps exactly one data
                // word: a longer request is answered with the first word only,
                // a shorter one ends the connection (FormatException). Only
                // Return Query Data (sub-function 0) with one data word is
                // handed to it.
                if (frame.Length < 4)
                {
                    return SlaveExceptionCodes.IllegalDataValue;
                }

                if (frame[2] != 0 || frame[3] != 0)
                {
                    return SlaveExceptionCodes.IllegalFunction;
                }

                if (frame.Length != 6)
                {
                    return SlaveExceptionCodes.IllegalDataValue;
                }
            }

            return 0;
        }

        private static void Check(IModbusMessage request, FixtureDataStore store)
        {
            switch (request)
            {
                case ReadCoilsInputsRequest r:
                    Value(r.NumberOfPoints >= 1 && r.NumberOfPoints <= 2000);
                    break;
                case ReadHoldingInputRegistersRequest r:
                    Value(r.NumberOfPoints >= 1 && r.NumberOfPoints <= 125);
                    break;
                case WriteSingleCoilRequestResponse r:
                    Value(r.Data.Count == 1 && (r.Data[0] == CoilOn || r.Data[0] == CoilOff));
                    break;
                case WriteMultipleCoilsRequest r:
                    Value(r.NumberOfPoints >= 1 && r.NumberOfPoints <= 1968
                          && r.ByteCount == (r.NumberOfPoints + 7) / 8 && r.Data.Count >= r.NumberOfPoints);
                    break;
                case WriteMultipleRegistersRequest r:
                    Value(r.NumberOfPoints >= 1 && r.NumberOfPoints <= 123
                          && r.ByteCount == r.NumberOfPoints * 2 && r.Data.Count == r.NumberOfPoints);
                    break;
                case ReadWriteMultipleRegistersRequest r:
                    Value(r.ReadRequest.NumberOfPoints >= 1 && r.ReadRequest.NumberOfPoints <= 125
                          && r.WriteRequest.NumberOfPoints >= 1 && r.WriteRequest.NumberOfPoints <= 121
                          && r.WriteRequest.ByteCount == r.WriteRequest.NumberOfPoints * 2
                          && r.WriteRequest.Data.Count == r.WriteRequest.NumberOfPoints);

                    // The NModbus service writes and then reads. Both ranges are
                    // checked first, so that a request whose read range is
                    // outside the block writes nothing.
                    if (!store.Holding.Contains(r.ReadRequest.StartAddress, r.ReadRequest.NumberOfPoints)
                        || !store.Holding.Contains(r.WriteRequest.StartAddress, r.WriteRequest.NumberOfPoints))
                    {
                        throw new InvalidModbusRequestException(SlaveExceptionCodes.IllegalDataAddress);
                    }

                    break;
            }
        }

        private static void Value(bool valid)
        {
            if (!valid)
            {
                throw new InvalidModbusRequestException(SlaveExceptionCodes.IllegalDataValue);
            }
        }
    }

    /// <summary>
    /// An NModbus slave that prints the "request" event for every request the
    /// slave it wraps answered, exception responses included.
    /// </summary>
    internal sealed class ReportingSlave : IModbusSlave
    {
        private readonly IModbusSlave _inner;

        public ReportingSlave(IModbusSlave inner)
        {
            _inner = inner;
        }

        public byte UnitId => _inner.UnitId;

        public ISlaveDataStore DataStore => _inner.DataStore;

        public IModbusMessage ApplyRequest(IModbusMessage request)
        {
            IModbusMessage response = _inner.ApplyRequest(request);
            try
            {
                Output.Line(writer => Describe(writer, request, response));
            }
            catch (Exception e)
            {
                Output.Diagnostic("request event not written: " + e.Message);
            }

            return response;
        }

        private static void Describe(Utf8JsonWriter writer, IModbusMessage request, IModbusMessage response)
        {
            writer.WriteString("event", "request");
            writer.WriteNumber("unitId", request.SlaveAddress);
            writer.WriteNumber("function", request.FunctionCode);
            switch (request)
            {
                case ReadCoilsInputsRequest r:
                    Range(writer, r.StartAddress, r.NumberOfPoints);
                    break;
                case ReadHoldingInputRegistersRequest r:
                    Range(writer, r.StartAddress, r.NumberOfPoints);
                    break;
                case WriteSingleCoilRequestResponse r:
                    Range(writer, r.StartAddress, 1);
                    Bits(writer, "bits", new[] { r.Data[0] == 0xFF00 });
                    break;
                case WriteSingleRegisterRequestResponse r:
                    Range(writer, r.StartAddress, 1);
                    Registers(writer, "registers", r.Data);
                    break;
                case WriteMultipleCoilsRequest r:
                    Range(writer, r.StartAddress, r.NumberOfPoints);
                    Bits(writer, "bits", r.Data.Take(r.NumberOfPoints));
                    break;
                case WriteMultipleRegistersRequest r:
                    Range(writer, r.StartAddress, r.NumberOfPoints);
                    Registers(writer, "registers", r.Data);
                    break;
                case ReadWriteMultipleRegistersRequest r:
                    Range(writer, r.ReadRequest.StartAddress, r.ReadRequest.NumberOfPoints);
                    writer.WriteNumber("writeAddress", r.WriteRequest.StartAddress);
                    Registers(writer, "writeRegisters", r.WriteRequest.Data);
                    break;
                case OpaqueRequest r when r.Rejection != 0 && r.FunctionCode != ModbusFunctionCodes.Diagnostics
                                          && r.MessageFrame.Length >= 6:
                    // A request of a function the device has, kept as bytes
                    // because its quantity is out of range.
                    Range(writer, (r.MessageFrame[2] << 8) | r.MessageFrame[3], (r.MessageFrame[4] << 8) | r.MessageFrame[5]);
                    break;
            }

            if (response.FunctionCode > 127)
            {
                byte[] pdu = response.ProtocolDataUnit;
                writer.WriteNumber("exception", pdu.Length > 1 ? pdu[1] : 0);
            }
            else
            {
                writer.WriteNull("exception");
            }
        }

        private static void Range(Utf8JsonWriter writer, int address, int quantity)
        {
            writer.WriteNumber("address", address);
            writer.WriteNumber("quantity", quantity);
        }

        private static void Bits(Utf8JsonWriter writer, string name, IEnumerable<bool> bits)
        {
            writer.WriteStartArray(name);
            foreach (bool bit in bits)
            {
                writer.WriteBooleanValue(bit);
            }

            writer.WriteEndArray();
        }

        private static void Registers(Utf8JsonWriter writer, string name, IEnumerable<ushort> registers)
        {
            writer.WriteStartArray(name);
            foreach (ushort register in registers)
            {
                writer.WriteNumberValue(register);
            }

            writer.WriteEndArray();
        }
    }

    /// <summary>Builds the NModbus objects that make up the device of a fixture.</summary>
    internal static class FixtureDevice
    {
        /// <summary>Function codes answered with something other than exception 1.</summary>
        public static readonly int[] Functions = { 1, 2, 3, 4, 5, 6, 8, 15, 16, 23 };

        /// <summary>
        /// The factory of the server: NModbus's services for the functions the
        /// device has, each behind its checks, and a refusing service for every
        /// other function code. Without one NModbus cannot create the request
        /// and ends the connection instead of answering exception 1. Its
        /// built-in Write File Record (21) and Read Device Identification (43)
        /// are left out: both have no server side (HandleSlaveRequest throws
        /// NotImplementedException).
        /// </summary>
        public static ModbusFactory CreateFactory(IModbusLogger logger)
        {
            var services = new List<IModbusFunctionService>
            {
                new CheckedService(new ReadCoilsService()),
                new CheckedService(new ReadInputsService()),
                new CheckedService(new ReadHoldingRegistersService()),
                new CheckedService(new ReadInputRegistersService()),
                new CheckedService(new WriteSingleCoilService()),
                new CheckedService(new WriteSingleRegisterService()),
                new CheckedService(new DiagnosticsService()),
                new CheckedService(new WriteMultipleCoilsService()),
                new CheckedService(new WriteMultipleRegistersService()),
                new CheckedService(new ReadWriteMultipleRegistersService()),
            };

            var served = new HashSet<byte>(services.Select(s => s.FunctionCode));
            for (int function = 1; function <= 127; function++)
            {
                if (!served.Contains((byte)function))
                {
                    services.Add(new RefusingService((byte)function, SlaveExceptionCodes.IllegalFunction));
                }
            }

            return new ModbusFactory(functionServices: services, includeBuiltIn: false, logger: logger);
        }

        /// <summary>
        /// Adds the slave of the fixture's unit identifier, and for every other
        /// unit identifier a slave that answers each request with exception 11.
        /// NModbus drops a request for a unit identifier it has no slave for
        /// without answering.
        /// </summary>
        public static void Populate(IModbusSlaveNetwork network, ModbusFactory factory, Fixture fixture)
        {
            var store = new FixtureDataStore(fixture);
            network.AddSlave(new ReportingSlave(factory.CreateSlave(fixture.UnitId, store)));

            const byte gatewayTargetDeviceFailedToRespond = 11;
            var refusing = new List<IModbusFunctionService>();
            for (int function = 1; function <= 127; function++)
            {
                refusing.Add(new RefusingService((byte)function, gatewayTargetDeviceFailedToRespond));
            }

            for (int unit = 0; unit <= 255; unit++)
            {
                if (unit != fixture.UnitId)
                {
                    network.AddSlave(new ReportingSlave(new ModbusSlave((byte)unit, store, refusing)));
                }
            }
        }
    }
}
