// SPDX-License-Identifier: MIT

package main

import (
	"bytes"
	"context"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"reflect"
	"sort"
	"strconv"
	"syscall"
	"time"

	modbus "github.com/otfabric/go-modbus"
)

// result is the result document of docs/CONTAINER_CONTRACT.md.
type result struct {
	SchemaVersion string     `json:"schemaVersion"`
	Adapter       string     `json:"adapter"`
	Operation     string     `json:"operation"`
	OK            bool       `json:"ok"`
	Error         *failure   `json:"error"`
	Connected     bool       `json:"connected"`
	UnitID        int        `json:"unitId"`
	Requests      int        `json:"requests"`
	Responses     int        `json:"responses"`
	Response      response   `json:"response"`
	Exception     *exception `json:"exception"`
	ElapsedMs     int64      `json:"elapsedMs"`
}

type failure struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

type exception struct {
	Function int `json:"function"`
	Code     int `json:"code"`
}

type response map[string]any

// MarshalJSON writes function first, then subFunction, then the rest by name:
// the order of the contract's tables, which a consumer may compare as text.
func (r response) MarshalJSON() ([]byte, error) {
	if r == nil {
		return []byte("null"), nil
	}
	rank := func(k string) string {
		switch k {
		case "function":
			return "0"
		case "subFunction":
			return "1"
		}
		return "2" + k
	}
	keys := make([]string, 0, len(r))
	for k := range r {
		keys = append(keys, k)
	}
	sort.Slice(keys, func(i, j int) bool { return rank(keys[i]) < rank(keys[j]) })
	var b bytes.Buffer
	b.WriteByte('{')
	for i, k := range keys {
		if i > 0 {
			b.WriteByte(',')
		}
		name, err := json.Marshal(k)
		if err != nil {
			return nil, err
		}
		value, err := json.Marshal(r[k])
		if err != nil {
			return nil, err
		}
		b.Write(name)
		b.WriteByte(':')
		b.Write(value)
	}
	b.WriteByte('}')
	return b.Bytes(), nil
}

// operation sends one request and returns the normal response.
type operation func(ctx context.Context, c *modbus.Client, unit uint8) (response, error)

// builder reads the flags of an operation; nothing has been sent yet.
type builder func(f *flags) operation

var operations = []struct {
	name  string
	build builder
}{
	{"read-coils", readBits(1, func(c *modbus.Client) bitReader { return c.ReadCoils })},
	{"read-discrete-inputs", readBits(2, func(c *modbus.Client) bitReader { return c.ReadDiscreteInputs })},
	{"read-holding-registers", readRegisters(3, modbus.HoldingRegister)},
	{"read-input-registers", readRegisters(4, modbus.InputRegister)},
	{"write-single-coil", writeSingleCoil},
	{"write-single-register", writeSingleRegister},
	{"write-multiple-coils", writeMultipleCoils},
	{"write-multiple-registers", writeMultipleRegisters},
	{"mask-write-register", maskWrite},
	{"read-write-multiple-registers", readWriteMultiple},
	{"read-device-identification", readDeviceIdentification},
	{"diagnostics", diagnostics},
	{"report-server-id", reportServerID},
	{"read-file-record", readFileRecord},
	{"write-file-record", writeFileRecord},
	{"read-fifo-queue", readFIFOQueue},
}

func operationNames() []string {
	names := make([]string, len(operations))
	for i, o := range operations {
		names[i] = o.name
	}
	return names
}

func runClient(args []string) int {
	if len(args) == 0 {
		return usage("client needs an operation")
	}
	name := args[0]
	var build builder
	for _, o := range operations {
		if o.name == name {
			build = o.build
		}
	}
	if build == nil {
		return usage("unknown operation " + name)
	}

	f := parseFlags(args[1:])
	host, hasHost := f.take("host")
	if !hasHost {
		f.fail("--host is required")
	}
	port := f.optional("port", 1502, 1, 65535)
	unit := f.optional("unit-id", 1, 0, 255)
	connectMs := f.optional("connect-timeout-ms", 5000, 1, 3600000)
	timeoutMs := f.optional("timeout-ms", 5000, 1, 3600000)
	repeat := f.optional("repeat", 1, 1, 1000)
	scheme, known := clientSchemes[f.text("transport", "tcp")]
	if !known {
		f.fail("--transport must be one of tcp, udp, rtu-over-tcp")
	}
	op := build(f)
	if err := f.finish(); err != nil {
		return usage(err.Error())
	}

	started := time.Now()
	res := &result{SchemaVersion: "1.0", Adapter: adapterName, Operation: name, UnitID: unit}
	code := exchange(res, op, scheme+"://"+net.JoinHostPort(host, strconv.Itoa(port)), uint8(unit), repeat,
		time.Duration(connectMs)*time.Millisecond, time.Duration(timeoutMs)*time.Millisecond)
	res.ElapsedMs = time.Since(started).Milliseconds()
	printJSON(res)
	return code
}

// clientSchemes maps the contract's --transport values (1.2) to go-modbus URL
// schemes.
var clientSchemes = map[string]string{"tcp": "tcp", "udp": "udp", "rtu-over-tcp": "rtuovertcp"}

// exchange connects and sends the request repeat times on that connection.
func exchange(res *result, op operation, url string, unit uint8, repeat int, connect, timeout time.Duration) int {
	client, err := modbus.New(modbus.Config{
		URL:         url,
		Timeout:     timeout,
		DialTimeout: connect,
	})
	if err != nil {
		res.Error = &failure{"failed", err.Error()}
		return exitFailed
	}
	if err := client.Open(); err != nil {
		res.Error = &failure{"connect-failed", err.Error()}
		return exitConnect
	}
	defer func() { _ = client.Close() }()
	res.Connected = true

	for i := 0; i < repeat; i++ {
		res.Requests++
		got, err := op(context.Background(), client, unit)
		if err != nil {
			report(res, err)
			return exitFailed
		}
		res.Responses++
		if res.Response != nil && !reflect.DeepEqual(res.Response, got) {
			res.Response = got
			res.Error = &failure{"failed", "responses differ under --repeat"}
			return exitFailed
		}
		res.Response = got
	}
	res.OK = true
	return exitOK
}

// report maps a go-modbus error (ERRORS.md of that module) to error.code.
func report(res *result, err error) {
	code := "failed"
	var exc *modbus.ExceptionError
	switch {
	case errors.As(err, &exc):
		code = "exception"
		res.Exception = &exception{Function: int(exc.FunctionCode) | 0x80, Code: int(exc.ExceptionCode)}
	case errors.Is(err, modbus.ErrRequestTimedOut):
		code = "timeout"
	case errors.Is(err, modbus.ErrProtocolError),
		errors.Is(err, modbus.ErrBadUnitID),
		errors.Is(err, modbus.ErrBadTransactionID),
		errors.Is(err, modbus.ErrUnknownProtocolID),
		errors.Is(err, modbus.ErrInvalidMBAPLength):
		code = "invalid-response"
	case errors.Is(err, io.EOF), errors.Is(err, io.ErrUnexpectedEOF),
		errors.Is(err, net.ErrClosed), errors.Is(err, syscall.ECONNRESET), errors.Is(err, syscall.EPIPE):
		code = "connection-lost"
	}
	res.Error = &failure{code, err.Error()}
}

type bitReader func(ctx context.Context, unit uint8, addr, quantity uint16) ([]bool, error)

func readBits(function int, reader func(*modbus.Client) bitReader) builder {
	return func(f *flags) operation {
		addr := f.integer("address", 0, 65535)
		quantity := f.integer("quantity", 1, 2000)
		return func(ctx context.Context, c *modbus.Client, unit uint8) (response, error) {
			bits, err := reader(c)(ctx, unit, uint16(addr), uint16(quantity))
			return response{"function": function, "bits": bits}, err
		}
	}
}

func readRegisters(function int, kind modbus.RegType) builder {
	return func(f *flags) operation {
		addr := f.integer("address", 0, 65535)
		quantity := f.integer("quantity", 1, 125)
		return func(ctx context.Context, c *modbus.Client, unit uint8) (response, error) {
			regs, err := c.ReadRegisters(ctx, unit, uint16(addr), uint16(quantity), kind)
			return response{"function": function, "registers": regs}, err
		}
	}
}

func writeSingleCoil(f *flags) operation {
	addr := f.integer("address", 0, 65535)
	s, ok := f.take("value")
	value, valid := parseBool(s)
	if !ok || !valid {
		f.fail("--value must be a coil value")
	}
	return func(ctx context.Context, c *modbus.Client, unit uint8) (response, error) {
		err := c.WriteCoil(ctx, unit, uint16(addr), value)
		return response{"function": 5, "address": addr, "value": value}, err
	}
}

func writeSingleRegister(f *flags) operation {
	addr := f.integer("address", 0, 65535)
	value := f.integer("value", 0, 65535)
	return func(ctx context.Context, c *modbus.Client, unit uint8) (response, error) {
		err := c.WriteRegister(ctx, unit, uint16(addr), uint16(value))
		return response{"function": 6, "address": addr, "value": value}, err
	}
}

func writeMultipleCoils(f *flags) operation {
	addr := f.integer("address", 0, 65535)
	values := f.bools("values", 1, 1968)
	return func(ctx context.Context, c *modbus.Client, unit uint8) (response, error) {
		err := c.WriteCoils(ctx, unit, uint16(addr), values)
		return response{"function": 15, "address": addr, "quantity": len(values)}, err
	}
}

func writeMultipleRegisters(f *flags) operation {
	addr := f.integer("address", 0, 65535)
	values := f.registers("values", 1, 123)
	return func(ctx context.Context, c *modbus.Client, unit uint8) (response, error) {
		err := c.WriteRegisters(ctx, unit, uint16(addr), values)
		return response{"function": 16, "address": addr, "quantity": len(values)}, err
	}
}

func maskWrite(f *flags) operation {
	addr := f.integer("address", 0, 65535)
	and := f.integer("and-mask", 0, 65535)
	or := f.integer("or-mask", 0, 65535)
	return func(ctx context.Context, c *modbus.Client, unit uint8) (response, error) {
		err := c.MaskWriteRegister(ctx, unit, uint16(addr), uint16(and), uint16(or))
		return response{"function": 22, "address": addr, "andMask": and, "orMask": or}, err
	}
}

func readWriteMultiple(f *flags) operation {
	readAddr := f.integer("read-address", 0, 65535)
	readQuantity := f.integer("read-quantity", 1, 125)
	writeAddr := f.integer("write-address", 0, 65535)
	values := f.registers("values", 1, 121)
	return func(ctx context.Context, c *modbus.Client, unit uint8) (response, error) {
		regs, err := c.ReadWriteMultipleRegisters(ctx, unit, uint16(readAddr), uint16(readQuantity), uint16(writeAddr), values)
		return response{"function": 23, "registers": regs}, err
	}
}

func readDeviceIdentification(f *flags) operation {
	var category modbus.DeviceIDCategory
	switch s := f.text("category", "basic"); s {
	case "basic":
		category = modbus.DeviceIDBasic
	case "regular":
		category = modbus.DeviceIDRegular
	case "extended":
		category = modbus.DeviceIDExtended
	default:
		f.fail("--category %s is not basic, regular or extended", s)
	}
	return func(ctx context.Context, c *modbus.Client, unit uint8) (response, error) {
		id, err := c.ReadDeviceIdentification(ctx, unit, category, 0)
		if err != nil {
			return nil, err
		}
		objects := make([]map[string]any, len(id.Objects))
		for i, o := range id.Objects {
			objects[i] = map[string]any{"id": int(o.ID), "value": o.Value}
		}
		return response{"function": 43, "conformityLevel": int(id.ConformityLevel), "objects": objects}, nil
	}
}

func diagnostics(f *flags) operation {
	sub := f.integer("sub-function", 0, 65535)
	text, ok := f.take("data")
	data, err := hex.DecodeString(text)
	if !ok || err != nil {
		f.fail("--data must be hexadecimal")
	}
	return func(ctx context.Context, c *modbus.Client, unit uint8) (response, error) {
		res, err := c.Diagnostics(ctx, unit, modbus.DiagnosticSubFunction(sub), data)
		if err != nil {
			return nil, err
		}
		return response{"function": 8, "subFunction": int(res.SubFunction), "data": hex.EncodeToString(res.Data)}, nil
	}
}

func reportServerID(*flags) operation {
	return func(ctx context.Context, c *modbus.Client, unit uint8) (response, error) {
		res, err := c.ReportServerID(ctx, unit)
		if err != nil {
			return nil, err
		}
		return response{"function": 17, "data": hex.EncodeToString(res.Data)}, nil
	}
}

func readFileRecord(f *flags) operation {
	file := f.integer("file", 1, 65535)
	record := f.integer("record", 0, 9999)
	length := f.integer("length", 1, 121)
	return func(ctx context.Context, c *modbus.Client, unit uint8) (response, error) {
		records, err := c.ReadFileRecords(ctx, unit, []modbus.FileRecordRequest{
			{FileNumber: uint16(file), RecordNumber: uint16(record), RecordLength: uint16(length)},
		})
		if err != nil {
			return nil, err
		}
		if len(records) != 1 {
			return nil, fmt.Errorf("%w: %d sub-responses to one sub-request", modbus.ErrProtocolError, len(records))
		}
		return response{"function": 20, "registers": records[0]}, nil
	}
}

func writeFileRecord(f *flags) operation {
	file := f.integer("file", 1, 65535)
	record := f.integer("record", 0, 9999)
	values := f.registers("values", 1, 122)
	return func(ctx context.Context, c *modbus.Client, unit uint8) (response, error) {
		err := c.WriteFileRecords(ctx, unit, []modbus.FileRecord{
			{FileNumber: uint16(file), RecordNumber: uint16(record), Data: values},
		})
		return response{"function": 21, "file": file, "record": record, "quantity": len(values)}, err
	}
}

func readFIFOQueue(f *flags) operation {
	addr := f.integer("address", 0, 65535)
	return func(ctx context.Context, c *modbus.Client, unit uint8) (response, error) {
		values, err := c.ReadFIFOQueue(ctx, unit, uint16(addr))
		if values == nil {
			values = []uint16{}
		}
		return response{"function": 24, "registers": values}, err
	}
}
