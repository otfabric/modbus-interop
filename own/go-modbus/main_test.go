// SPDX-License-Identifier: MIT

package main

import (
	"io"
	"net"
	"testing"
	"time"
)

// A peer that answers with another unit identifier: no reference server does,
// so clientUnitIdCheck is verified here instead of in tests/cases.tsv.
func TestClientRejectsAnotherUnitIdentifier(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = ln.Close() }()
	go func() {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		defer func() { _ = conn.Close() }()
		request := make([]byte, 12)
		if _, err := io.ReadFull(conn, request); err != nil {
			return
		}
		// FC03, one register, from unit identifier + 1.
		_, _ = conn.Write([]byte{request[0], request[1], 0, 0, 0, 5, request[6] + 1, 3, 2, 0, 1})
	}()

	f := parseFlags([]string{"--address", "0", "--quantity", "1"})
	op := readRegisters(3, 0)(f)
	if err := f.finish(); err != nil {
		t.Fatal(err)
	}
	res := &result{}
	code := exchange(res, op, ln.Addr().String(), 1, 1, time.Second, time.Second)
	if code != exitFailed || res.Error == nil || res.Error.Code != "invalid-response" {
		t.Fatalf("exit %d, error %+v, want exit 1 and invalid-response", code, res.Error)
	}
}
