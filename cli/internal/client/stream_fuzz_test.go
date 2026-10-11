// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"context"
	"strings"
	"testing"
)

// FuzzScanSSE feeds arbitrary bytes to the SSE parser, which reads untrusted server output.
// It must never panic, and every emitted event must be backed by at least one data: line.
func FuzzScanSSE(f *testing.F) {
	for _, seed := range []string{
		"data: hello\n\n",
		"event: log\ndata: a\ndata: b\n\n",
		"data:\n\n",
		": comment\nid: 1\nretry: 10\n\n",
		"data: no terminator",
		"data: crlf\r\n\r\n",
		"\n\n\n",
	} {
		f.Add(seed)
	}

	f.Fuzz(func(t *testing.T, input string) {
		events := 0
		_ = ScanSSE(context.Background(), strings.NewReader(input), func(SSEEvent) error {
			events++
			return nil
		})

		dataLines := 0
		for _, line := range strings.Split(input, "\n") {
			if strings.HasPrefix(line, "data:") {
				dataLines++
			}
		}
		if events > dataLines {
			t.Fatalf("got %d events from only %d data lines", events, dataLines)
		}
	})
}
