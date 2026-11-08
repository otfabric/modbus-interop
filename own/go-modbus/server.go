// SPDX-License-Identifier: MIT

package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net"
	"os"
	"os/signal"
	"path/filepath"
	"regexp"
	"strconv"
	"sync"
	"syscall"

	modbus "github.com/otfabric/go-modbus"
)

// fixture is docs/FIXTURES.md. The typed decode refuses a value out of range.
type fixture struct {
	SchemaVersion    string         `json:"schemaVersion"`
	Name             string         `json:"name"`
	Description      string         `json:"description"`
	UnitID           uint8          `json:"unitId"`
	Coils            *bitBlock      `json:"coils"`
	DiscreteInputs   *bitBlock      `json:"discreteInputs"`
	HoldingRegisters *registerBlock `json:"holdingRegisters"`
	InputRegisters   *registerBlock `json:"inputRegisters"`
	DeviceID         *struct {
		Objects []struct {
			ID    uint8  `json:"id"`
			Value string `json:"value"`
		} `json:"objects"`
	} `json:"deviceIdentification"`
	ServerID *struct {
		ServerID     uint8 `json:"serverId"`
		RunIndicator bool  `json:"runIndicator"`
	} `json:"reportServerId"`
	Files []struct {
		File    uint16   `json:"file"`
		Records []uint16 `json:"records"`
	} `json:"fileRecords"`
	FIFOs []struct {
		Address uint16   `json:"address"`
		Values  []uint16 `json:"values"`
	} `json:"fifoQueues"`
}

type bitBlock struct {
	Address uint16 `json:"address"`
	Values  []bool `json:"values"`
}

type registerBlock struct {
	Address uint16   `json:"address"`
	Values  []uint16 `json:"values"`
}

var fixtureName = regexp.MustCompile(`^[a-z0-9][a-z0-9-]*$`)

// loadFixture applies the rules of scripts/validate-fixtures.py.
func loadFixture(path string) (*fixture, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	fx := &fixture{}
	if err := json.Unmarshal(b, fx); err != nil {
		return nil, err
	}
	if fx.SchemaVersion != "1.0" {
		return nil, fmt.Errorf("schemaVersion %q", fx.SchemaVersion)
	}
	if !fixtureName.MatchString(fx.Name) {
		return nil, fmt.Errorf("name %q", fx.Name)
	}
	if abs, err := filepath.Abs(path); err != nil || filepath.Base(filepath.Dir(abs)) != fx.Name {
		return nil, fmt.Errorf("name %q is not the directory the fixture is in", fx.Name)
	}
	if fx.UnitID < 1 || fx.UnitID > 247 {
		return nil, fmt.Errorf("unitId %d is not in 1..247", fx.UnitID)
	}
	for _, block := range []struct {
		name    string
		address uint16
		count   int
		present bool
	}{
		{"coils", addrOfBits(fx.Coils), lenOfBits(fx.Coils), fx.Coils != nil},
		{"discreteInputs", addrOfBits(fx.DiscreteInputs), lenOfBits(fx.DiscreteInputs), fx.DiscreteInputs != nil},
		{"holdingRegisters", addrOfRegs(fx.HoldingRegisters), lenOfRegs(fx.HoldingRegisters), fx.HoldingRegisters != nil},
		{"inputRegisters", addrOfRegs(fx.InputRegisters), lenOfRegs(fx.InputRegisters), fx.InputRegisters != nil},
	} {
		if block.present && int(block.address)+block.count-1 > 65535 {
			return nil, fmt.Errorf("%s ends past address 65535", block.name)
		}
	}
	if fx.DeviceID != nil {
		seen := map[uint8]bool{}
		last := -1
		for _, o := range fx.DeviceID.Objects {
			if int(o.ID) <= last {
				return nil, errors.New("deviceIdentification: object ids must be unique and ascending")
			}
			if o.ID >= 7 && o.ID <= 127 {
				return nil, fmt.Errorf("deviceIdentification: object id %d is reserved", o.ID)
			}
			last = int(o.ID)
			seen[o.ID] = true
		}
		if !seen[0] || !seen[1] || !seen[2] {
			return nil, errors.New("deviceIdentification: a basic object is missing")
		}
	}
	files := map[uint16]bool{}
	for _, f := range fx.Files {
		if f.File == 0 || files[f.File] || len(f.Records) > 10000 {
			return nil, fmt.Errorf("fileRecords: file %d", f.File)
		}
		files[f.File] = true
	}
	queues := map[uint16]bool{}
	for _, q := range fx.FIFOs {
		if queues[q.Address] || len(q.Values) > 31 {
			return nil, fmt.Errorf("fifoQueues: address %d", q.Address)
		}
		queues[q.Address] = true
	}
	return fx, nil
}

func addrOfBits(b *bitBlock) uint16 {
	if b == nil {
		return 0
	}
	return b.Address
}

func lenOfBits(b *bitBlock) int {
	if b == nil {
		return 0
	}
	return len(b.Values)
}

func addrOfRegs(b *registerBlock) uint16 {
	if b == nil {
		return 0
	}
	return b.Address
}

func lenOfRegs(b *registerBlock) int {
	if b == nil {
		return 0
	}
	return len(b.Values)
}

func runServer(args []string) int {
	f := parseFlags(args)
	path := f.text("fixture", fixtureRoot+"/baseline/fixture.json")
	bind := f.text("bind-address", "0.0.0.0")
	port := f.optional("port", 1502, 1, 65535)
	readyFile := f.text("ready-file", "/run/modbus-interop/ready")
	// Contract 1.2: the go-modbus server has Modbus TCP (and TLS) only.
	if transport := f.text("transport", "tcp"); transport != "tcp" {
		f.fail("--transport %s: the server has only tcp", transport)
	}
	if err := f.finish(); err != nil {
		return usage(err.Error())
	}

	fx, err := loadFixture(path)
	if err != nil {
		fmt.Fprintln(os.Stderr, "fixture:", err)
		return exitInvalidFixture
	}

	address := net.JoinHostPort(bind, strconv.Itoa(port))
	dev := newDevice(fx)
	server, err := modbus.NewServer(&modbus.ServerConfig{
		URL:        "tcp://" + address,
		MaxClients: 64,
		Logger:     modbus.NewStdLogger(log.New(os.Stderr, "", log.LstdFlags)),
	}, dev)
	if err != nil {
		fmt.Fprintln(os.Stderr, "server:", err)
		return exitFailed
	}
	if err := server.Start(); err != nil {
		fmt.Fprintln(os.Stderr, "listen:", err)
		return exitFailed
	}

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGTERM, syscall.SIGINT)

	if err := os.WriteFile(readyFile, nil, 0o644); err != nil {
		fmt.Fprintln(os.Stderr, "ready file:", err)
		_ = server.Stop()
		return exitFailed
	}
	dev.emit(map[string]any{"event": "ready", "adapter": adapterName, "address": address, "fixture": fx.Name, "unitId": fx.UnitID})

	<-stop
	_ = os.Remove(readyFile)
	if err := server.Stop(); err != nil {
		fmt.Fprintln(os.Stderr, "stop:", err)
	}
	dev.emit(map[string]any{"event": "stopped"})
	return exitOK
}

// device serves a fixture by the rules of FIXTURES.md § Behaviour of the
// device. Framing, quantity limits and the function codes without a handler
// are the library's: the adapter does not fill in for it.
type device struct {
	fx *fixture

	mu      sync.Mutex
	coils   []bool
	holding []uint16
	files   map[uint16][]uint16

	out sync.Mutex
	enc *json.Encoder
}

func newDevice(fx *fixture) *device {
	d := &device{fx: fx, files: map[uint16][]uint16{}, enc: json.NewEncoder(os.Stdout)}
	if fx.Coils != nil {
		d.coils = append([]bool(nil), fx.Coils.Values...)
	}
	if fx.HoldingRegisters != nil {
		d.holding = append([]uint16(nil), fx.HoldingRegisters.Values...)
	}
	for _, file := range fx.Files {
		d.files[file.File] = append([]uint16(nil), file.Records...)
	}
	return d
}

func (d *device) emit(event map[string]any) {
	d.out.Lock()
	defer d.out.Unlock()
	if err := d.enc.Encode(event); err != nil {
		fmt.Fprintln(os.Stderr, "event:", err)
	}
}

// answered prints the request event of err, the outcome of the request.
func (d *device) answered(unit uint8, function modbus.FunctionCode, err error, fields map[string]any) {
	event := map[string]any{"event": "request", "unitId": unit, "function": uint8(function), "exception": exceptionOf(err)}
	for k, v := range fields {
		event[k] = v
	}
	d.emit(event)
}

// exceptionOf is the exception code a handler error is answered with, or nil.
func exceptionOf(err error) any {
	switch {
	case err == nil:
		return nil
	case errors.Is(err, modbus.ErrIllegalFunction):
		return 1
	case errors.Is(err, modbus.ErrIllegalDataAddress):
		return 2
	case errors.Is(err, modbus.ErrIllegalDataValue):
		return 3
	case errors.Is(err, modbus.ErrGWTargetFailedToRespond):
		return 11
	}
	return 4
}

// unit answers a request for any other unit identifier with exception 11.
func (d *device) unit(id uint8) error {
	if id != d.fx.UnitID {
		return modbus.ErrGWTargetFailedToRespond
	}
	return nil
}

// within reports whether addr..addr+quantity-1 lies in a block of n elements
// starting at base.
func within(base uint16, n int, addr, quantity uint16) bool {
	return n > 0 && addr >= base && int(addr)+int(quantity) <= int(base)+n
}

func (d *device) HandleCoils(_ context.Context, req *modbus.CoilsRequest) ([]bool, error) {
	res, err := d.coilsAccess(req)
	fields := map[string]any{"address": req.Addr, "quantity": req.Quantity}
	if req.IsWrite {
		fields["bits"] = req.Args
	}
	d.answered(req.UnitID, req.FunctionCode, err, fields)
	return res, err
}

func (d *device) coilsAccess(req *modbus.CoilsRequest) ([]bool, error) {
	if err := d.unit(req.UnitID); err != nil {
		return nil, err
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.fx.Coils == nil || !within(d.fx.Coils.Address, len(d.coils), req.Addr, req.Quantity) {
		return nil, modbus.ErrIllegalDataAddress
	}
	at := int(req.Addr - d.fx.Coils.Address)
	if req.IsWrite {
		copy(d.coils[at:], req.Args)
		return nil, nil
	}
	return append([]bool(nil), d.coils[at:at+int(req.Quantity)]...), nil
}

func (d *device) HandleDiscreteInputs(_ context.Context, req *modbus.DiscreteInputsRequest) ([]bool, error) {
	res, err := d.discreteInputs(req)
	d.answered(req.UnitID, req.FunctionCode, err, map[string]any{"address": req.Addr, "quantity": req.Quantity})
	return res, err
}

func (d *device) discreteInputs(req *modbus.DiscreteInputsRequest) ([]bool, error) {
	if err := d.unit(req.UnitID); err != nil {
		return nil, err
	}
	b := d.fx.DiscreteInputs
	if b == nil || !within(b.Address, len(b.Values), req.Addr, req.Quantity) {
		return nil, modbus.ErrIllegalDataAddress
	}
	at := int(req.Addr - b.Address)
	return append([]bool(nil), b.Values[at:at+int(req.Quantity)]...), nil
}

func (d *device) HandleHoldingRegisters(_ context.Context, req *modbus.HoldingRegistersRequest) ([]uint16, error) {
	res, err := d.holdingAccess(req)
	fields := map[string]any{"address": req.Addr, "quantity": req.Quantity}
	if req.IsWrite {
		fields["registers"] = req.Args
	}
	d.answered(req.UnitID, req.FunctionCode, err, fields)
	return res, err
}

func (d *device) holdingAccess(req *modbus.HoldingRegistersRequest) ([]uint16, error) {
	if err := d.unit(req.UnitID); err != nil {
		return nil, err
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.fx.HoldingRegisters == nil || !within(d.fx.HoldingRegisters.Address, len(d.holding), req.Addr, req.Quantity) {
		return nil, modbus.ErrIllegalDataAddress
	}
	at := int(req.Addr - d.fx.HoldingRegisters.Address)
	if req.IsWrite {
		copy(d.holding[at:], req.Args)
		return nil, nil
	}
	return append([]uint16(nil), d.holding[at:at+int(req.Quantity)]...), nil
}

func (d *device) HandleInputRegisters(_ context.Context, req *modbus.InputRegistersRequest) ([]uint16, error) {
	res, err := d.inputRegisters(req)
	d.answered(req.UnitID, req.FunctionCode, err, map[string]any{"address": req.Addr, "quantity": req.Quantity})
	return res, err
}

func (d *device) inputRegisters(req *modbus.InputRegistersRequest) ([]uint16, error) {
	if err := d.unit(req.UnitID); err != nil {
		return nil, err
	}
	b := d.fx.InputRegisters
	if b == nil || !within(b.Address, len(b.Values), req.Addr, req.Quantity) {
		return nil, modbus.ErrIllegalDataAddress
	}
	at := int(req.Addr - b.Address)
	return append([]uint16(nil), b.Values[at:at+int(req.Quantity)]...), nil
}

// HandleMaskWrite sets the register to (current AND andMask) OR (orMask AND
// NOT andMask).
func (d *device) HandleMaskWrite(_ context.Context, req *modbus.MaskWriteRequest) error {
	err := d.maskWrite(req)
	d.answered(req.UnitID, req.FunctionCode, err, map[string]any{"address": req.Addr, "quantity": 1, "andMask": req.AndMask, "orMask": req.OrMask})
	return err
}

func (d *device) maskWrite(req *modbus.MaskWriteRequest) error {
	if err := d.unit(req.UnitID); err != nil {
		return err
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.fx.HoldingRegisters == nil || !within(d.fx.HoldingRegisters.Address, len(d.holding), req.Addr, 1) {
		return modbus.ErrIllegalDataAddress
	}
	at := int(req.Addr - d.fx.HoldingRegisters.Address)
	d.holding[at] = (d.holding[at] & req.AndMask) | (req.OrMask &^ req.AndMask)
	return nil
}

// HandleReadWriteRegisters checks both ranges before anything is written, and
// writes before it reads.
func (d *device) HandleReadWriteRegisters(_ context.Context, req *modbus.ReadWriteRegistersRequest) ([]uint16, error) {
	res, err := d.readWrite(req)
	d.answered(req.UnitID, req.FunctionCode, err, map[string]any{"address": req.ReadAddr, "quantity": req.ReadQty, "writeAddress": req.WriteAddr, "writeRegisters": req.WriteValues})
	return res, err
}

func (d *device) readWrite(req *modbus.ReadWriteRegistersRequest) ([]uint16, error) {
	if err := d.unit(req.UnitID); err != nil {
		return nil, err
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.fx.HoldingRegisters == nil {
		return nil, modbus.ErrIllegalDataAddress
	}
	base := d.fx.HoldingRegisters.Address
	if !within(base, len(d.holding), req.ReadAddr, req.ReadQty) ||
		!within(base, len(d.holding), req.WriteAddr, uint16(len(req.WriteValues))) {
		return nil, modbus.ErrIllegalDataAddress
	}
	copy(d.holding[req.WriteAddr-base:], req.WriteValues)
	at := int(req.ReadAddr - base)
	return append([]uint16(nil), d.holding[at:at+int(req.ReadQty)]...), nil
}

// HandleDeviceIdentification returns the objects of the fixture. Category
// filtering, framing, "more follows" and the conformity level are the
// library's.
func (d *device) HandleDeviceIdentification(_ context.Context, req *modbus.DeviceIdentificationRequest) (*modbus.DeviceIdentificationResponse, error) {
	err := d.unit(req.UnitID)
	if err == nil && d.fx.DeviceID == nil {
		err = modbus.ErrIllegalFunction
	}
	d.answered(req.UnitID, req.FunctionCode, err, nil)
	if err != nil {
		return nil, err
	}
	res := &modbus.DeviceIdentificationResponse{}
	for _, o := range d.fx.DeviceID.Objects {
		res.Objects = append(res.Objects, modbus.DeviceIdentificationObject{ID: modbus.DeviceIDObjectID(o.ID), Value: o.Value})
	}
	return res, nil
}

// HandleDiagnostics serves Return Query Data (sub-function 0) only.
func (d *device) HandleDiagnostics(_ context.Context, req *modbus.DiagnosticsRequest) ([]byte, error) {
	err := d.unit(req.UnitID)
	if err == nil && req.SubFunction != modbus.DiagReturnQueryData {
		err = modbus.ErrIllegalFunction
	}
	d.answered(req.UnitID, req.FunctionCode, err, nil)
	if err != nil {
		return nil, err
	}
	return req.Data, nil
}

// HandleReportServerID serves the fixture's server ID and run indicator.
func (d *device) HandleReportServerID(_ context.Context, req *modbus.ReportServerIDRequest) ([]byte, error) {
	err := d.unit(req.UnitID)
	if err == nil && d.fx.ServerID == nil {
		err = modbus.ErrIllegalFunction
	}
	d.answered(req.UnitID, req.FunctionCode, err, nil)
	if err != nil {
		return nil, err
	}
	run := byte(0x00)
	if d.fx.ServerID.RunIndicator {
		run = 0xFF
	}
	return []byte{d.fx.ServerID.ServerID, run}, nil
}

func (d *device) HandleReadFileRecords(_ context.Context, req *modbus.ReadFileRecordsRequest) ([][]uint16, error) {
	res, err := d.readFiles(req)
	d.answered(req.UnitID, req.FunctionCode, err, nil)
	return res, err
}

func (d *device) readFiles(req *modbus.ReadFileRecordsRequest) ([][]uint16, error) {
	if err := d.unit(req.UnitID); err != nil {
		return nil, err
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	out := make([][]uint16, len(req.Requests))
	for i, r := range req.Requests {
		file, ok := d.files[r.FileNumber]
		if !ok || int(r.RecordNumber)+int(r.RecordLength) > len(file) {
			return nil, modbus.ErrIllegalDataAddress
		}
		out[i] = append([]uint16(nil), file[r.RecordNumber:int(r.RecordNumber)+int(r.RecordLength)]...)
	}
	return out, nil
}

// HandleWriteFileRecords checks every sub-request before it writes any.
func (d *device) HandleWriteFileRecords(_ context.Context, req *modbus.WriteFileRecordsRequest) error {
	err := d.writeFiles(req)
	d.answered(req.UnitID, req.FunctionCode, err, nil)
	return err
}

func (d *device) writeFiles(req *modbus.WriteFileRecordsRequest) error {
	if err := d.unit(req.UnitID); err != nil {
		return err
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	for _, r := range req.Records {
		file, ok := d.files[r.FileNumber]
		if !ok || int(r.RecordNumber)+len(r.Data) > len(file) {
			return modbus.ErrIllegalDataAddress
		}
	}
	for _, r := range req.Records {
		copy(d.files[r.FileNumber][r.RecordNumber:], r.Data)
	}
	return nil
}

func (d *device) HandleReadFIFOQueue(_ context.Context, req *modbus.FIFOQueueRequest) ([]uint16, error) {
	res, err := d.readFIFO(req)
	d.answered(req.UnitID, req.FunctionCode, err, nil)
	return res, err
}

func (d *device) readFIFO(req *modbus.FIFOQueueRequest) ([]uint16, error) {
	if err := d.unit(req.UnitID); err != nil {
		return nil, err
	}
	for _, q := range d.fx.FIFOs {
		if q.Address == req.Addr {
			return q.Values, nil
		}
	}
	return nil, modbus.ErrIllegalDataAddress
}
