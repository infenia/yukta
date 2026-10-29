// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"sort"
	"strings"
	"sync"
	"testing"
)

// The package's tests drive every client function against fake servers (100% coverage is
// enforced), so recording what they send covers every endpoint the CLI calls. After the run each
// method and path is checked against the server's OpenAPI snapshot (ui/openapi.json, kept equal
// to the real /v3/api-docs by OpenApiSnapshotTest): a renamed, removed or re-methoded endpoint
// fails the CLI build instead of a user's script.
// ponytail: method and path only, not response fields; add a real-server CI job if field drift shows.

// genericHelperPaths are made-up paths that tests of the HTTP plumbing use.
var genericHelperPaths = map[string]bool{"/api/test": true, "/api/test/stream": true, "/api/x": true}

// recorder is a transport that notes each "METHOD /escaped/path" under /api and forwards it.
type recorder struct {
	mu   sync.Mutex
	seen map[string]bool
	next http.RoundTripper
}

func (r *recorder) RoundTrip(req *http.Request) (*http.Response, error) {
	if p := req.URL.EscapedPath(); strings.HasPrefix(p, "/api/") && !genericHelperPaths[p] {
		r.mu.Lock()
		r.seen[req.Method+" "+p] = true
		r.mu.Unlock()
	}
	return r.next.RoundTrip(req)
}

func TestMain(m *testing.M) {
	rec := &recorder{seen: map[string]bool{}, next: http.DefaultTransport}
	http.DefaultTransport = rec
	code := m.Run()
	if code == 0 {
		if bad := rec.contractViolations(); len(bad) > 0 {
			fmt.Fprintf(os.Stderr, "CONTRACT: the client sends requests ui/openapi.json does not have:\n  %s\n", strings.Join(bad, "\n  "))
			code = 1
		}
	}
	os.Exit(code)
}

func (r *recorder) contractViolations() []string {
	raw, err := os.ReadFile("../../../ui/openapi.json")
	if err != nil {
		return []string{err.Error()}
	}
	var doc struct {
		Paths map[string]map[string]json.RawMessage `json:"paths"`
	}
	if err := json.Unmarshal(raw, &doc); err != nil {
		return []string{err.Error()}
	}
	var bad []string
	for call := range r.seen {
		method, path, _ := strings.Cut(call, " ")
		if !served(doc.Paths, strings.ToLower(method), path) {
			bad = append(bad, call)
		}
	}
	if len(r.seen) < 30 {
		bad = append(bad, fmt.Sprintf("only %d distinct calls recorded; the recorder is broken", len(r.seen)))
	}
	sort.Strings(bad)
	return bad
}

// served tells whether the document has an operation with this method on a path of the same
// segments, where a {x} segment matches any one segment of the request.
func served(paths map[string]map[string]json.RawMessage, method, path string) bool {
	want := strings.Split(path, "/")
next:
	for p, ops := range paths {
		segs := strings.Split(p, "/")
		if len(segs) != len(want) {
			continue
		}
		for i, s := range segs {
			if s != want[i] && !strings.HasPrefix(s, "{") {
				continue next
			}
		}
		if _, ok := ops[method]; ok {
			return true
		}
	}
	return false
}
