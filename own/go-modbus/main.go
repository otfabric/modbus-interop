// SPDX-License-Identifier: MIT

// Command modbus-interop is the modbus-interop-otfabric image: go-modbus behind the
// container contract (docs/CONTAINER_CONTRACT.md).
//
// It is OT Fabric's own stack, not an independent implementation: results
// against it are never independent evidence for go-modbus (README.md).
package main

import (
	"encoding/json"
	"fmt"
	"os"
	"strconv"
	"strings"
)

const (
	adapterName = "otfabric"
	fixtureRoot = "/fixtures"
)

// Set with -ldflags by the Dockerfile.
var (
	adapterVersion   = "dev"
	upstreamVersion  = "dev"
	upstreamRevision = "dev"
)

const (
	exitOK             = 0
	exitFailed         = 1
	exitUsage          = 2
	exitConnect        = 3
	exitInvalidFixture = 4
)

func main() {
	os.Exit(run(os.Args[1:]))
}

func run(args []string) int {
	if len(args) == 0 {
		return usage("no command")
	}
	switch args[0] {
	case "server":
		return runServer(args[1:])
	case "client":
		return runClient(args[1:])
	case "print-capabilities":
		return printJSON(capabilities())
	case "print-fixture":
		return printFixture(args[1:])
	case "version":
		fmt.Printf("%s %s (go-modbus %s)\n", adapterName, adapterVersion, upstreamVersion)
		return exitOK
	}
	return usage("unknown command " + args[0])
}

func usage(message string) int {
	fmt.Fprintln(os.Stderr, "modbus-interop:", message)
	return exitUsage
}

func printJSON(v any) int {
	b, err := json.Marshal(v)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return exitFailed
	}
	fmt.Println(string(b))
	return exitOK
}

func printFixture(args []string) int {
	name := "baseline"
	switch {
	case len(args) == 1:
		name = args[0]
	case len(args) > 1:
		return usage("print-fixture takes at most one name")
	}
	if strings.ContainsAny(name, "/\\") {
		fmt.Fprintln(os.Stderr, "no fixture named", name)
		return exitFailed
	}
	b, err := os.ReadFile(fixtureRoot + "/" + name + "/fixture.json")
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return exitFailed
	}
	_, _ = os.Stdout.Write(b)
	return exitOK
}

// capabilities is the document of docs/CAPABILITIES.md. A feature is true only
// when the self-tests exercise it for this adapter (or, for clientUnitIdCheck,
// main_test.go does).
func capabilities() map[string]any {
	return map[string]any{
		"schemaVersion":  "1.0",
		"adapter":        adapterName,
		"adapterVersion": adapterVersion,
		"protocol":       "modbus-tcp",
		"upstream": map[string]any{
			"name":              "go-modbus",
			"version":           upstreamVersion,
			"revision":          upstreamRevision,
			"license":           "MIT",
			"language":          "Go",
			"independentEngine": false,
		},
		"roles": map[string]bool{"server": true, "client": true},
		"features": map[string]bool{
			"serverUnitIdFiltering":        true,
			"serverQuantityCheck":          true,
			"serverMaskWrite":              true,
			"serverReadWriteMultiple":      true,
			"serverDeviceIdentification":   true,
			"requestEvents":                true,
			"connectionEvents":             false,
			"clientMaskWrite":              true,
			"clientReadWriteMultiple":      true,
			"clientDeviceIdentification":   true,
			"clientRaw":                    false,
			"clientRepeat":                 true,
			"clientUnitIdCheck":            true,
			"serverDiagnostics":            true,
			"serverReportServerId":         true,
			"serverFileRecord":             true,
			"serverFifoQueue":              true,
			"clientDiagnostics":            true,
			"clientReportServerId":         true,
			"clientReadFileRecord":         true,
			"clientWriteFileRecord":        true,
			"clientFifoQueue":              true,
			"clientRawAnyResponse":         false,
			"serverIllegalFunction":        true,
			"serverRejectedWriteUnchanged": true,
			"clientLargestRegisterWrites":  true,
			"serverHighestAddress":         true,
			"serverLargestRegisterWrites":  true,
		},
		"serverFunctions":  []int{1, 2, 3, 4, 5, 6, 8, 15, 16, 17, 20, 21, 22, 23, 24, 43},
		"clientOperations": operationNames(),
		"transports": map[string][]string{
			"server": {"tcp"},
			"client": {"tcp", "udp", "rtu-over-tcp"},
		},
	}
}

// flags holds "--name value" pairs. An operation takes what it needs and
// finish refuses the rest: an unknown flag is a usage error, never ignored.
type flags struct {
	values map[string]string
	err    error
}

func parseFlags(args []string) *flags {
	f := &flags{values: map[string]string{}}
	for i := 0; i < len(args); i++ {
		if !strings.HasPrefix(args[i], "--") {
			f.fail("unexpected argument %s", args[i])
			return f
		}
		if i+1 == len(args) {
			f.fail("%s needs a value", args[i])
			return f
		}
		f.values[strings.TrimPrefix(args[i], "--")] = args[i+1]
		i++
	}
	return f
}

func (f *flags) fail(format string, a ...any) {
	if f.err == nil {
		f.err = fmt.Errorf(format, a...)
	}
}

func (f *flags) take(name string) (string, bool) {
	v, ok := f.values[name]
	delete(f.values, name)
	return v, ok
}

// finish returns the first usage error, including any flag nobody took.
func (f *flags) finish() error {
	for name := range f.values {
		f.fail("unknown flag --%s", name)
	}
	return f.err
}

// number parses decimal, or hexadecimal with a 0x prefix.
func number(s string) (int, error) {
	base := 10
	if strings.HasPrefix(s, "0x") || strings.HasPrefix(s, "0X") {
		s, base = s[2:], 16
	}
	v, err := strconv.ParseInt(s, base, 32)
	return int(v), err
}

// integer returns a required integer flag within lo..hi.
func (f *flags) integer(name string, lo, hi int) int {
	s, ok := f.take(name)
	if !ok {
		f.fail("--%s is required", name)
		return 0
	}
	return f.check(name, s, lo, hi)
}

// optional returns an integer flag within lo..hi, or def without one.
func (f *flags) optional(name string, def, lo, hi int) int {
	s, ok := f.take(name)
	if !ok {
		return def
	}
	return f.check(name, s, lo, hi)
}

func (f *flags) check(name, s string, lo, hi int) int {
	v, err := number(s)
	if err != nil || v < lo || v > hi {
		f.fail("--%s %s is not in %d..%d", name, s, lo, hi)
		return 0
	}
	return v
}

func (f *flags) text(name, def string) string {
	if s, ok := f.take(name); ok {
		return s
	}
	return def
}

func parseBool(s string) (value, ok bool) {
	switch strings.ToLower(s) {
	case "true", "1", "on":
		return true, true
	case "false", "0", "off":
		return false, true
	}
	return false, false
}

// registers returns a required comma-separated register list of lo..hi values.
func (f *flags) registers(name string, lo, hi int) []uint16 {
	s, ok := f.take(name)
	if !ok {
		f.fail("--%s is required", name)
		return nil
	}
	parts := strings.Split(s, ",")
	if len(parts) < lo || len(parts) > hi {
		f.fail("--%s has %d values, not %d..%d", name, len(parts), lo, hi)
		return nil
	}
	out := make([]uint16, len(parts))
	for i, p := range parts {
		v, err := number(p)
		if err != nil || v < 0 || v > 65535 {
			f.fail("--%s: %s is not a register value", name, p)
			return nil
		}
		out[i] = uint16(v)
	}
	return out
}

// bools returns a required comma-separated coil list of lo..hi values.
func (f *flags) bools(name string, lo, hi int) []bool {
	s, ok := f.take(name)
	if !ok {
		f.fail("--%s is required", name)
		return nil
	}
	parts := strings.Split(s, ",")
	if len(parts) < lo || len(parts) > hi {
		f.fail("--%s has %d values, not %d..%d", name, len(parts), lo, hi)
		return nil
	}
	out := make([]bool, len(parts))
	for i, p := range parts {
		v, ok := parseBool(p)
		if !ok {
			f.fail("--%s: %s is not a coil value", name, p)
			return nil
		}
		out[i] = v
	}
	return out
}
