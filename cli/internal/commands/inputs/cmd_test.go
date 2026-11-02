// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package inputs

import (
	"bytes"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands"
)

func run(t *testing.T, c *client.Client, format string, args ...string) (string, error) {
	t.Helper()
	orig := commands.GetOutputFormat()
	commands.SetTestOutputFormat(format)
	defer commands.SetTestOutputFormat(orig)

	r, w, _ := os.Pipe()
	old := os.Stdout
	os.Stdout = w
	cmd := InputsCmd(c)
	cmd.SetArgs(args)
	cmd.SetOut(io.Discard)
	cmd.SetErr(io.Discard)
	err := cmd.Execute()
	w.Close()
	os.Stdout = old
	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	return buf.String(), err
}

// recorder answers every call with body and records the last request.
type recorder struct {
	method, path, body string
}

func (rec *recorder) client(t *testing.T, status int, body string) *client.Client {
	t.Helper()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		rec.method, rec.path = r.Method, r.URL.Path
		b, _ := io.ReadAll(r.Body)
		rec.body = string(b)
		w.WriteHeader(status)
		_, _ = w.Write([]byte(body))
	}))
	t.Cleanup(server.Close)
	return client.NewClient(server.URL)
}

const answered = `{"data":{"id":"r1","sessionId":"s1","workflowId":"w","executionId":"e1","nodeId":"gate",` +
	`"state":"ANSWERED","version":2,"kind":"CHOICE","prompt":"Ship\u001b[31m it?","context":{"b":2,"a":"x"},` +
	`"options":[{"id":"ok","label":"Go"}],"responseSchema":"{}","risk":"LOW","tier":"default","advice":"check",` +
	`"answer":{"decision":"ok","text":"fine","data":{"n":1},"responderType":"HUMAN","responderId":"key:ann",` +
	`"rationale":"because","answeredAt":"t"}}}`

const waiting = `{"data":{"id":"r2","executionId":"e1","state":"OPEN","kind":"TEXT","prompt":"Why?"}}`

func TestListOneSessionAsTable(t *testing.T) {
	rec := &recorder{}
	body := `{"data":[{"sessionId":"s1","executionId":"e1","id":"r1","nodeId":"gate","kind":"TEXT","risk":"LOW",` +
		`"state":"OPEN","tier":"default","prompt":"` + strings.Repeat("p", 70) + `"}]}`
	out, err := run(t, rec.client(t, 200, body), "table", "list", "--session", "s1", "--state", "OPEN")
	if err != nil {
		t.Fatal(err)
	}
	if rec.path != "/api/sessions/s1/inputs" || !strings.Contains(out, "gate") || !strings.Contains(out, "…") {
		t.Errorf("path %q, output %q", rec.path, out)
	}
}

func TestListAllSessionsAsJSON(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/sessions" {
			_, _ = w.Write([]byte(`{"data":["s1"]}`))
			return
		}
		_, _ = w.Write([]byte(`{"data":[{"id":"r1","sessionId":"s1","prompt":"short"}]}`))
	}))
	defer server.Close()

	out, err := run(t, client.NewClient(server.URL), "json", "list")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(out, `"id": "r1"`) {
		t.Errorf("output %q", out)
	}
}

func TestListReportsAFailure(t *testing.T) {
	rec := &recorder{}
	if _, err := run(t, rec.client(t, 403, `{"message":"no"}`), "table", "list", "--session", "s1"); err == nil {
		t.Error("expected an error")
	}
}

func TestShowPrintsEveryPartWithoutControlCharacters(t *testing.T) {
	rec := &recorder{}
	out, err := run(t, rec.client(t, 200, answered), "table", "show", "e1", "r1")
	if err != nil {
		t.Fatal(err)
	}
	if rec.path != "/api/executions/e1/inputs/r1" {
		t.Errorf("path %q", rec.path)
	}
	for _, want := range []string{"Ship [31m it?", `{"a":"x","b":2}`, "ok (Go)", "HUMAN key:ann", "because",
		`{"n":1}`, "fine", "check", "Schema"} {
		if !strings.Contains(out, want) {
			t.Errorf("output lacks %q:\n%s", want, out)
		}
	}
	if strings.ContainsRune(out, '\u001b') {
		t.Error("an escape character was printed")
	}
}

func TestShowOfAWaitingRequestAsTableAndJSON(t *testing.T) {
	rec := &recorder{}
	c := rec.client(t, 200, waiting)
	out, err := run(t, c, "table", "show", "e1", "r2")
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(out, "Decision") || !strings.Contains(out, "Why?") {
		t.Errorf("output %q", out)
	}
	out, err = run(t, c, "json", "show", "e1", "r2")
	if err != nil || !strings.Contains(out, `"state": "OPEN"`) {
		t.Errorf("output %q, error %v", out, err)
	}
}

func TestShowReportsAFailure(t *testing.T) {
	rec := &recorder{}
	if _, err := run(t, rec.client(t, 404, `{"message":"not found"}`), "table", "show", "e1", "r9"); err == nil {
		t.Error("expected an error")
	}
	if _, err := run(t, rec.client(t, 200, waiting), "table", "show", "e1"); err == nil {
		t.Error("expected a usage error")
	}
}

func TestAnswerSendsEveryFlag(t *testing.T) {
	rec := &recorder{}
	out, err := run(t, rec.client(t, 200, answered), "table", "answer", "e1", "r1", "--decision", "ok",
		"--text", "fine", "--data", `{"n":1}`, "--rationale", "because", "--expected-version", "1")
	if err != nil {
		t.Fatal(err)
	}
	want := `{"decision":"ok","text":"fine","data":{"n":1},"rationale":"because","expectedVersion":1}`
	if rec.method != "POST" || rec.path != "/api/executions/e1/inputs/r1/answer" || rec.body != want {
		t.Errorf("%s %s %s", rec.method, rec.path, rec.body)
	}
	if !strings.Contains(out, "ANSWERED") {
		t.Errorf("output %q", out)
	}
}

func TestAnswerReadsDataFromAFile(t *testing.T) {
	file := filepath.Join(t.TempDir(), "data.json")
	if err := os.WriteFile(file, []byte(`{"from":"file"}`), 0o600); err != nil {
		t.Fatal(err)
	}
	rec := &recorder{}
	if _, err := run(t, rec.client(t, 200, answered), "json", "answer", "e1", "r1", "--decision", "ok",
		"--data", file); err != nil {
		t.Fatal(err)
	}
	if rec.body != `{"decision":"ok","data":{"from":"file"}}` {
		t.Errorf("body %s", rec.body)
	}
}

func TestAnswerFailures(t *testing.T) {
	rec := &recorder{}
	c := rec.client(t, 409, `{"message":"Input request r1 is already answered: ok by HUMAN:key:ann"}`)
	if _, err := run(t, c, "table", "answer", "e1", "r1", "--decision", "ok"); err == nil ||
		!strings.Contains(err.Error(), "already answered") {
		t.Errorf("expected the conflict, got %v", err)
	}
	if _, err := run(t, c, "table", "answer", "e1", "r1", "--decision", "ok", "--data", "missing.json"); err == nil ||
		!strings.Contains(err.Error(), "--data") {
		t.Errorf("expected a data error, got %v", err)
	}
	if _, err := run(t, c, "table", "answer", "e1", "r1"); err == nil {
		t.Error("expected the missing --decision to be refused")
	}
}

func TestEscalate(t *testing.T) {
	rec := &recorder{}
	if _, err := run(t, rec.client(t, 200, waiting), "table", "escalate", "e1", "r2", "--reason", "not mine",
		"--advice", "ask ops"); err != nil {
		t.Fatal(err)
	}
	if rec.path != "/api/executions/e1/inputs/r2/escalate" || rec.body != `{"reason":"not mine","advice":"ask ops"}` {
		t.Errorf("%s %s", rec.path, rec.body)
	}
	if _, err := run(t, rec.client(t, 403, `{"message":"rule tier"}`), "table", "escalate", "e1", "r2",
		"--reason", "x"); err == nil {
		t.Error("expected an error")
	}
}

func TestPrintInputWithoutARequest(t *testing.T) {
	if err := printInput(nil); err == nil {
		t.Error("expected an error")
	}
}

func TestShortAndClean(t *testing.T) {
	if short("abc", 5) != "abc" || short("abcdef", 4) != "abc…" {
		t.Error("short")
	}
	if clean("a\nb\tc") != "a b c" {
		t.Error("clean")
	}
}
