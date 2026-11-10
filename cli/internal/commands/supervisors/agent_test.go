// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package supervisors

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"com.infenia.yukta/go-cli/internal/client"
)

func TestDispatchRoutesEachDirectiveKind(t *testing.T) {
	var paths, bodies []string
	send := func(path string, body []byte) error {
		paths = append(paths, path)
		bodies = append(bodies, string(body))
		return nil
	}
	lines := []string{
		`{"type":"command","key":"k","cause":"tick","reason":"r","command":{"type":"PauseWorkflow","executionId":"e"}}`,
		`{"type":"verdict","answers":"r 1","verdict":"DENY","reason":"no"}`,
		`{"type":"noOpinion","answers":"r2"}`,
		`{"type":"delegationAnswer","answers":"d1","answer":{"ok":true}}`,
		``,
	}
	for _, line := range lines {
		if err := dispatch(line, send); err != nil {
			t.Fatalf("%q: %v", line, err)
		}
	}
	want := []string{
		"/api/agent/v1/directives",
		"/api/agent/v1/reviews/r%201",
		"/api/agent/v1/reviews/r2",
		"/api/agent/v1/delegations/d1",
	}
	if strings.Join(paths, ",") != strings.Join(want, ",") {
		t.Fatalf("paths %v, want %v", paths, want)
	}
	if strings.Contains(bodies[0], `"type":"command"`) || !strings.Contains(bodies[0], `"key":"k"`) {
		t.Errorf("a command is sent without its line type: %s", bodies[0])
	}
	if bodies[2] != `{"verdict":"NO_OPINION"}` {
		t.Errorf("no opinion body: %s", bodies[2])
	}
}

func TestDispatchRefusesWhatIsNotADirective(t *testing.T) {
	send := func(string, []byte) error { return nil }
	for _, line := range []string{`not json`, `{"type":"mystery"}`, `{}`} {
		if err := dispatch(line, send); err == nil {
			t.Errorf("%q should be refused", line)
		}
	}
	fail := func(string, []byte) error { return fmt.Errorf("boom") }
	if err := dispatch(`{"type":"noOpinion"}`, fail); err == nil {
		t.Error("a send error should come back")
	}
}

func TestAgentBridgesSignalsToTheProgramAndBack(t *testing.T) {
	var mu sync.Mutex
	var posted []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer secret" {
			http.Error(w, "no", http.StatusUnauthorized)
			return
		}
		if r.Method == "GET" {
			w.Header().Set("Content-Type", "text/event-stream")
			_, _ = io.WriteString(w, "id: 1\ndata: {\"cursor\":1,\"kind\":\"TICK\",\"signal\":{}}\n\n")
			w.(http.Flusher).Flush()
			<-r.Context().Done()
			return
		}
		body, _ := io.ReadAll(r.Body)
		mu.Lock()
		posted = append(posted, r.URL.Path+" "+string(body))
		mu.Unlock()
		_, _ = io.WriteString(w, `{"data":null}`)
	}))
	defer srv.Close()

	c := client.NewClient(srv.URL)
	c.Token = "secret"
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	// answers every signal it reads with a no-opinion for review "r9", then stays until stopped
	done := make(chan error, 1)
	go func() {
		done <- runAgent(ctx, c, "sh", []string{"-c",
			`while read line; do echo '{"type":"noOpinion","answers":"r9"}'; done`}, io.Discard)
	}()
	deadline := time.Now().Add(4 * time.Second)
	for time.Now().Before(deadline) {
		mu.Lock()
		n := len(posted)
		mu.Unlock()
		if n > 0 {
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	cancel()
	<-done
	mu.Lock()
	defer mu.Unlock()
	if len(posted) == 0 || !strings.HasPrefix(posted[0], "/api/agent/v1/reviews/r9 ") {
		t.Fatalf("posted %v", posted)
	}
}

func TestAgentReportsAProgramThatCannotStart(t *testing.T) {
	c := client.NewClient("http://127.0.0.1:1")
	if err := runAgent(context.Background(), c, "/no/such/program", nil, io.Discard); err == nil {
		t.Error("a missing program should be an error")
	}
}

func TestAgentEndsWhenTheProgramEndsAndSaysWhatItCouldNotSend(t *testing.T) {
	c := client.NewClient("http://127.0.0.1:1") // down: the stream ends at once and is retried
	var log strings.Builder
	err := runAgent(context.Background(), c, "sh", []string{"-c", "echo not-json; sleep 0.2"}, &log)
	if err == nil || !strings.Contains(err.Error(), "the program ended") {
		t.Fatalf("want the program ended, got %v", err)
	}
	if !strings.Contains(log.String(), "directive not sent") || !strings.Contains(log.String(), "reconnecting") {
		t.Errorf("the log must say what went wrong: %q", log.String())
	}
}

func TestAgentStopsTheStreamWhenTheProgramCannotTakeASignal(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		time.Sleep(400 * time.Millisecond) // the program has closed its input by now
		_, _ = io.WriteString(w, "id: 1\ndata: {\"cursor\":1}\n\n")
	}))
	defer srv.Close()
	var log strings.Builder
	err := runAgent(context.Background(), client.NewClient(srv.URL), "sh", []string{"-c", "exec 0<&-; sleep 3"}, &log)
	if err == nil || !strings.Contains(err.Error(), "the program ended") {
		t.Errorf("want the program ended, got %v", err)
	}
	if !strings.Contains(log.String(), "stream ended") {
		t.Errorf("a signal the program could not take must end the stream: %q", log.String())
	}
}

func TestAgentStopsWhileWaitingToReconnect(t *testing.T) {
	c := client.NewClient("http://127.0.0.1:1") // down: the stream fails and the agent backs off
	ctx, cancel := context.WithTimeout(context.Background(), 300*time.Millisecond)
	defer cancel()
	var log strings.Builder
	if err := runAgent(ctx, c, "sleep", []string{"5"}, &log); err != nil {
		t.Fatalf("a stop during the backoff is a clean end, got %v", err)
	}
	if !strings.Contains(log.String(), "reconnecting") {
		t.Errorf("it must have tried to reconnect first: %q", log.String())
	}
}

func TestAgentCommandNeedsAReadableCredentialFile(t *testing.T) {
	cmd := agentCmd(client.NewClient("http://127.0.0.1:1"))
	cmd.SetArgs([]string{"--credential-file", "/no/such/file", "--exec", "true"})
	cmd.SilenceUsage, cmd.SilenceErrors = true, true
	if err := cmd.ExecuteContext(context.Background()); err == nil || !strings.Contains(err.Error(), "credential file") {
		t.Errorf("want a credential file error, got %v", err)
	}

	file := t.TempDir() + "/token"
	if err := os.WriteFile(file, []byte(" yag_secret \n"), 0o600); err != nil {
		t.Fatal(err)
	}
	c := client.NewClient("http://127.0.0.1:1")
	cmd = agentCmd(c)
	cmd.SetArgs([]string{"--credential-file", file, "--exec", "true"})
	cmd.SilenceUsage, cmd.SilenceErrors = true, true
	cmd.SetErr(io.Discard)
	err := cmd.ExecuteContext(context.Background())
	if c.Token != "yag_secret" {
		t.Errorf("the credential must be read without its spaces: %q", c.Token)
	}
	if err == nil || !strings.Contains(err.Error(), "the program ended") {
		t.Errorf("want the program ended, got %v", err)
	}
}
