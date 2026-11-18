// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

//go:build e2e

// Package e2e drives yukta-cli against a real server, in process, the way a user runs it: through
// app.Main with os.Args. Start the server with release/e2e-server.sh, then
//
//	YUKTA_E2E_URL=$(release/e2e-server.sh url) YUKTA_E2E_KEY=$(release/e2e-server.sh key) make e2e
package e2e

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"

	"com.infenia.yukta/go-cli/internal/app"
	"com.infenia.yukta/go-cli/internal/testutil"
)

var (
	serverURL string
	serverKey string
)

func TestMain(m *testing.M) {
	serverURL = strings.TrimRight(os.Getenv("YUKTA_E2E_URL"), "/")
	serverKey = os.Getenv("YUKTA_E2E_KEY")
	if serverURL == "" || serverKey == "" {
		fmt.Fprintln(os.Stderr, "YUKTA_E2E_URL and YUKTA_E2E_KEY must be set: start a server with release/e2e-server.sh")
		os.Exit(1)
	}
	os.Exit(m.Run())
}

// result is what one run of the CLI printed and its exit code.
type result struct {
	stdout string
	stderr string
	code   int
}

// yukta runs the CLI with the server URL and token of the tests.
func yukta(t *testing.T, args ...string) result {
	t.Helper()
	return yuktaWith(t, []string{"--url", serverURL, "--token", serverKey}, args...)
}

// yuktaWith runs the CLI with the given global flags first, then args.
func yuktaWith(t *testing.T, global []string, args ...string) result {
	t.Helper()
	oldArgs, oldStderr := os.Args, os.Stderr
	defer func() { os.Args, os.Stderr = oldArgs, oldStderr }()
	os.Args = append(append([]string{"yukta-cli"}, global...), args...)

	errRead, errWrite, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	os.Stderr = errWrite
	var code int
	stdout := testutil.Capture(t, func() { code = app.Main() })
	_ = errWrite.Close()
	stderr, _ := io.ReadAll(errRead)
	return result{stdout: stdout, stderr: string(stderr), code: code}
}

// ok runs the CLI and fails the test unless it exits 0.
func ok(t *testing.T, args ...string) string {
	t.Helper()
	r := yukta(t, args...)
	if r.code != 0 {
		t.Fatalf("yukta-cli %s exited %d\nstdout: %s\nstderr: %s", strings.Join(args, " "), r.code, r.stdout, r.stderr)
	}
	return r.stdout
}

// unique names a resource of a test: e2e-<test>-<8 hex>, so tests share one server.
func unique(t *testing.T) string {
	t.Helper()
	name := strings.ToLower(strings.NewReplacer("Test", "", "/", "-", "_", "-").Replace(t.Name()))
	return fmt.Sprintf("e2e-%s-%08x", name, time.Now().UnixNano()&0xffffffff)
}

// session is a one-workflow session ("w"): trigger -> step -> terminal.
func session(id, stepType, stepConfig string) string {
	return fmt.Sprintf(`{"sessionId":%q,"name":"e2e","description":"d","initiator":"e2e","projectPath":"/tmp",
"workflows":{"w":{"workflowId":"w","description":"d","nodes":[
{"nodeId":"t","type":"CONSTANT_SOURCE","config":{}},
{"nodeId":"s","type":%q,"config":%s},
{"nodeId":"e","type":"CONSOLE_TERMINAL","config":{}}],
"edges":[{"source":"t","target":"s"},{"source":"s","target":"e"}]}}}`, id, stepType, stepConfig)
}

// echoSession prints text and ends.
func echoSession(id, text string) string {
	return session(id, "PROCESS_EXECUTOR", fmt.Sprintf(`{"command":["echo",%q],"outputFormat":"RAW"}`, text))
}

// apply writes a session definition to a file, applies it through the CLI and deletes the session
// when the test ends.
func apply(t *testing.T, definition string) string {
	t.Helper()
	var head struct {
		SessionID string `json:"sessionId"`
	}
	if err := json.Unmarshal([]byte(definition), &head); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(t.TempDir(), head.SessionID+".json")
	if err := os.WriteFile(path, []byte(definition), 0o600); err != nil {
		t.Fatal(err)
	}
	ok(t, "session", "apply", path)
	t.Cleanup(func() { _ = yukta(t, "session", "delete", head.SessionID, "--yes", "--force") })
	return head.SessionID
}

// start starts workflow w of a session and returns the execution id.
func start(t *testing.T, session string) string {
	t.Helper()
	var started struct {
		ExecutionID string `json:"executionId"`
	}
	decode(t, ok(t, "-o", "json", "workflow", "start", session, "w"), &started)
	if started.ExecutionID == "" {
		t.Fatal("workflow start printed no execution id")
	}
	return started.ExecutionID
}

// follow reads the status stream of a run until the server ends it, and returns what it sent.
func follow(t *testing.T, session, execution string) string {
	t.Helper()
	return ok(t, "workflow", "status-stream", session, execution)
}

func decode(t *testing.T, text string, into any) {
	t.Helper()
	if err := json.Unmarshal([]byte(text), into); err != nil {
		t.Fatalf("not the JSON expected: %v\n%s", err, text)
	}
}

// eventually polls fn until it returns true, or fails the test after a time limit.
func eventually(t *testing.T, what string, fn func() bool) {
	t.Helper()
	deadline := time.Now().Add(30 * time.Second)
	for time.Now().Before(deadline) {
		if fn() {
			return
		}
		time.Sleep(250 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %s", what)
}

// rest reads a path of the server directly and returns the "data" of the envelope.
func rest(t *testing.T, path string) map[string]any {
	t.Helper()
	req, err := http.NewRequest(http.MethodGet, serverURL+path, nil)
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("X-API-Key", serverKey)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var envelope struct {
		Data map[string]any `json:"data"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&envelope); err != nil {
		t.Fatal(err)
	}
	return envelope.Data
}

func TestSessionLifecycle(t *testing.T) {
	id := unique(t)
	apply(t, echoSession(id, "hi"))

	if list := ok(t, "session", "list"); !strings.Contains(list, id) {
		t.Errorf("session list lacks %s:\n%s", id, list)
	}
	if got := ok(t, "session", "get", id); !strings.Contains(got, id) || !strings.Contains(got, "w") {
		t.Errorf("session get: %s", got)
	}
	var config struct {
		SessionID string         `json:"sessionId"`
		Workflows map[string]any `json:"workflows"`
	}
	decode(t, ok(t, "-o", "json", "session", "config", id), &config)
	if config.SessionID != id || config.Workflows["w"] == nil {
		t.Errorf("session config: %+v", config)
	}

	ok(t, "session", "delete", id, "--yes")
	if r := yukta(t, "session", "get", id); r.code != 3 {
		t.Errorf("a deleted session: exit %d, want 3\n%s", r.code, r.stderr)
	}
}

func TestARunIsFollowedToTheEndAndRead(t *testing.T) {
	id := unique(t)
	apply(t, echoSession(id, "said-by-the-cli"))
	execution := start(t, id)

	if events := follow(t, id, execution); !strings.Contains(events, "SUCCESS") {
		t.Errorf("the status stream did not end with SUCCESS:\n%s", events)
	}

	var status struct {
		Status string           `json:"status"`
		Tasks  []map[string]any `json:"tasks"`
	}
	decode(t, ok(t, "-o", "json", "workflow", "status", id, execution), &status)
	if status.Status != "SUCCESS" || len(status.Tasks) != 3 {
		t.Errorf("status: %+v", status)
	}
	if tree := ok(t, "workflow", "tree", execution); !strings.Contains(tree, execution) {
		t.Errorf("tree lacks the run:\n%s", tree)
	}
	if history := ok(t, "workflow", "history", id); !strings.Contains(history, execution) {
		t.Errorf("history lacks the run:\n%s", history)
	}
	if found := ok(t, "workflow", "executions", "search", "--session", id); !strings.Contains(found, execution) {
		t.Errorf("search lacks the run:\n%s", found)
	}
	if logs := ok(t, "logs", id, execution); !strings.Contains(logs, "said-by-the-cli") {
		t.Errorf("logs:\n%s", logs)
	}
	if out := ok(t, "logs", id, execution, "--node", "s"); !strings.Contains(out, "said-by-the-cli") {
		t.Errorf("node output:\n%s", out)
	}
	if result := ok(t, "result", id, execution); !strings.Contains(result, "EXECUTION_END") {
		t.Errorf("result:\n%s", result)
	}
}

func TestAnInputRequestIsListedAndAnswered(t *testing.T) {
	id := unique(t)
	apply(t, session(id, "ASK_INPUT", `{"prompt":"ok?","kind":"APPROVAL","ttlSeconds":600}`))
	execution := start(t, id)

	var request string
	eventually(t, "the run to ask", func() bool {
		var listed []struct {
			ID string `json:"id"`
		}
		r := yukta(t, "-o", "json", "inputs", "list", "--session", id)
		if r.code != 0 || json.Unmarshal([]byte(r.stdout), &listed) != nil || len(listed) == 0 {
			return false
		}
		request = listed[0].ID
		return true
	})

	if shown := ok(t, "inputs", "show", execution, request); !strings.Contains(shown, "ok?") {
		t.Errorf("inputs show:\n%s", shown)
	}
	if r := yukta(t, "inputs", "answer", execution, request, "--decision", "maybe"); r.code == 0 {
		t.Error("an answer that is not an option was accepted")
	}
	ok(t, "inputs", "answer", execution, request, "--decision", "approve")
	if events := follow(t, id, execution); !strings.Contains(events, "SUCCESS") {
		t.Errorf("the run did not finish after the answer:\n%s", events)
	}
}

func TestANotificationIsListedReadAndDismissed(t *testing.T) {
	id := unique(t)
	title := "e2e " + id
	apply(t, fmt.Sprintf(`{"sessionId":%q,"name":"e2e","description":"d","initiator":"e2e","projectPath":"/tmp",
"workflows":{"w":{"workflowId":"w","description":"d","nodes":[
{"nodeId":"t","type":"CONSTANT_SOURCE","config":{}},
{"nodeId":"n","type":"NOTIFY_TERMINAL","config":{"target":"inbox","title":%q,"severity":"INFO","text":"from the cli test"}}],
"edges":[{"source":"t","target":"n"}]}}}`, id, title))
	start(t, id)

	var notificationID string
	eventually(t, "the notification", func() bool {
		var listed []struct {
			ID    string `json:"id"`
			Title string `json:"title"`
		}
		r := yukta(t, "-o", "json", "notifications", "list", "--kind", "INFO")
		if r.code != 0 || json.Unmarshal([]byte(r.stdout), &listed) != nil {
			return false
		}
		for _, n := range listed {
			if n.Title == title {
				notificationID = n.ID
			}
		}
		return notificationID != ""
	})

	ok(t, "notifications", "read", notificationID)
	ok(t, "notifications", "dismiss", notificationID)
}

func TestSupervisorsAreSavedListedShownAndDeleted(t *testing.T) {
	id := unique(t)
	file := filepath.Join(t.TempDir(), "supervisor.json")
	body := fmt.Sprintf(`{"id":%q,"type":"rules","settings":{"rules":[]}}`, id)
	if err := os.WriteFile(file, []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
	ok(t, "supervisors", "save", "-f", file)
	t.Cleanup(func() { _ = yukta(t, "supervisors", "delete", id) })

	if list := ok(t, "supervisors", "list"); !strings.Contains(list, id) {
		t.Errorf("supervisors list lacks %s:\n%s", id, list)
	}
	var shown struct {
		ID   string `json:"id"`
		Type string `json:"type"`
	}
	decode(t, ok(t, "-o", "json", "supervisors", "show", id), &shown)
	if shown.ID != id || shown.Type != "rules" {
		t.Errorf("supervisors show: %+v", shown)
	}
	if r := yukta(t, "supervisors", "show", "e2e-no-such-supervisor"); r.code != 3 {
		t.Errorf("an unknown supervisor: exit %d, want 3\n%s", r.code, r.stderr)
	}
	ok(t, "supervisors", "delete", id)
}

func TestWhoamiAndTheSchedulerLimits(t *testing.T) {
	var me struct {
		Authenticated bool   `json:"authenticated"`
		Role          string `json:"role"`
	}
	decode(t, ok(t, "-o", "json", "whoami"), &me)
	if !me.Authenticated || me.Role != "ADMIN" {
		t.Errorf("whoami: %+v", me)
	}
	if table := ok(t, "whoami"); !strings.Contains(table, "ADMIN") {
		t.Errorf("whoami table:\n%s", table)
	}
	if limits := ok(t, "scheduler", "limits"); !strings.Contains(limits, "maxConcurrent") {
		t.Errorf("scheduler limits:\n%s", limits)
	}
}

// present returns the top-level keys of a JSON object whose value is not null (the CLI leaves a
// null out), minus the ones in skip.
func present(m map[string]any, skip ...string) []string {
	out := []string{}
	for k, v := range m {
		if v != nil && !slices.Contains(skip, k) {
			out = append(out, k)
		}
	}
	slices.Sort(out)
	return out
}

// The JSON a command prints has the fields of the REST response it reads, so a field the server
// adds, renames or drops is seen here (the client's contract test checks only method and path).
func TestJSONOutputHasTheFieldsOfTheRESTResponse(t *testing.T) {
	id := unique(t)
	apply(t, echoSession(id, "fields"))
	execution := start(t, id)
	follow(t, id, execution)

	// gaps are fields the server sends and the CLI does not print yet: findings of this test, listed
	// so that the test passes today and fails when a new field is dropped.
	cases := []struct {
		name string
		path string
		args []string
		gaps []string
	}{
		{"whoami", "/api/me", []string{"whoami"}, []string{"supervision"}},
		{"workflow status", "/api/executions/" + execution, []string{"workflow", "status", id, execution},
			[]string{"definitionVersion", "traceId"}},
		{"scheduler limits", "/api/scheduler/limits", []string{"scheduler", "limits"}, nil},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			var fromCLI map[string]any
			decode(t, ok(t, append([]string{"-o", "json"}, c.args...)...), &fromCLI)
			fromREST := rest(t, c.path)

			if got, want := present(fromCLI), present(fromREST, c.gaps...); !slices.Equal(got, want) {
				t.Errorf("the CLI prints the fields\n  %v\nthe server sends (minus the known gaps %v)\n  %v", got, c.gaps, want)
			}
		})
	}
}

func TestExitCodes(t *testing.T) {
	id := unique(t)
	cases := []struct {
		name string
		args []string
		want int
	}{
		{"success", []string{"whoami"}, 0},
		{"a usage error", []string{"workflow", "status"}, 2},
		{"an unknown session", []string{"session", "get", id}, 3},
		{"an unknown execution", []string{"workflow", "status", id, "e2e-nope"}, 3},
		// 1ns cannot be met by any server: a deterministic stand-in for a slow call
		{"a timeout", []string{"--timeout", "1ns", "session", "list"}, 5},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if r := yukta(t, c.args...); r.code != c.want {
				t.Errorf("exit %d, want %d\nstdout: %s\nstderr: %s", r.code, c.want, r.stdout, r.stderr)
			}
		})
	}
}

func TestTheTokenComesFromAFlagAFileOrTheEnvironment(t *testing.T) {
	file := filepath.Join(t.TempDir(), "token")
	if err := os.WriteFile(file, []byte(serverKey+"\n"), 0o600); err != nil {
		t.Fatal(err)
	}

	byFlag := yuktaWith(t, []string{"--url", serverURL, "--token", serverKey}, "whoami")
	byFile := yuktaWith(t, []string{"--url", serverURL, "--token-file", file}, "whoami")
	t.Setenv("YUKTA_TOKEN", serverKey)
	byEnv := yuktaWith(t, []string{"--url", serverURL}, "whoami")

	for name, r := range map[string]result{"--token": byFlag, "--token-file": byFile, "YUKTA_TOKEN": byEnv} {
		if r.code != 0 || !strings.Contains(r.stdout, "ADMIN") {
			t.Errorf("%s: exit %d\nstdout: %s\nstderr: %s", name, r.code, r.stdout, r.stderr)
		}
	}

	t.Setenv("YUKTA_TOKEN", "")
	wrong := yuktaWith(t, []string{"--url", serverURL, "--token", "e2e-not-the-key"}, "whoami")
	if wrong.code == 0 || !strings.Contains(wrong.stderr, "401") {
		t.Errorf("a wrong token: exit %d, stderr %q", wrong.code, wrong.stderr)
	}
}
