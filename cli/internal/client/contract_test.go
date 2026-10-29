// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"encoding/json"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
)

var apiLiteral = regexp.MustCompile(`"(/api/[^"?]*)"`)

// TestEveryClientPathIsInTheServerContract fails when the client calls a path the server's
// committed OpenAPI document (ui/openapi.json, kept current by OpenApiSnapshotTest) does not
// have, so a renamed or removed endpoint breaks the CLI build instead of a user's script.
// ponytail: paths only, not methods or response fields; add a method check when drift shows there.
func TestEveryClientPathIsInTheServerContract(t *testing.T) {
	raw, err := os.ReadFile("../../../ui/openapi.json")
	if err != nil {
		t.Fatal(err)
	}
	var doc struct {
		Paths map[string]json.RawMessage `json:"paths"`
	}
	if err := json.Unmarshal(raw, &doc); err != nil {
		t.Fatal(err)
	}
	var served [][]string
	for p := range doc.Paths {
		served = append(served, strings.Split(p, "/"))
	}

	files, _ := filepath.Glob("*.go")
	checked := 0
	for _, f := range files {
		if strings.HasSuffix(f, "_test.go") {
			continue
		}
		src, err := os.ReadFile(f)
		if err != nil {
			t.Fatal(err)
		}
		for _, m := range apiLiteral.FindAllStringSubmatch(string(src), -1) {
			// A trailing slash is a prefix that executionSignal completes with its verb.
			paths := []string{m[1]}
			if strings.HasSuffix(m[1], "/") {
				paths = []string{m[1] + "pause", m[1] + "resume"}
			}
			for _, p := range paths {
				checked++
				if !servedPath(served, strings.Split(p, "/")) {
					t.Errorf("%s: %s is not in ui/openapi.json", f, p)
				}
			}
		}
	}
	if checked < 30 {
		t.Fatalf("found only %d client paths; the scan is broken", checked)
	}
}

// servedPath tells whether some OpenAPI path has the same segments, where {x} matches the client's %s.
func servedPath(served [][]string, want []string) bool {
next:
	for _, segs := range served {
		if len(segs) != len(want) {
			continue
		}
		for i, s := range segs {
			if s != want[i] && (!strings.HasPrefix(s, "{") || want[i] != "%s") {
				continue next
			}
		}
		return true
	}
	return false
}
