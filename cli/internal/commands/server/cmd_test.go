// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package server

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands"
)

func stateDir(t *testing.T) string {
	d := t.TempDir()
	t.Setenv("YUKTA_HOME", d)
	return d
}

func write(t *testing.T, dir, name, body string) {
	if err := os.WriteFile(filepath.Join(dir, name), []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
}

func runStatus(t *testing.T, url, format string) (string, error) {
	orig := commands.GetOutputFormat()
	commands.SetTestOutputFormat(format)
	defer commands.SetTestOutputFormat(orig)
	r, w, _ := os.Pipe()
	old := os.Stdout
	os.Stdout = w
	cmd := statusCmd(client.NewClient(url))
	err := cmd.RunE(cmd, nil)
	w.Close()
	os.Stdout = old
	var buf bytes.Buffer
	_, _ = io.Copy(&buf, r)
	return buf.String(), err
}

func srv(code int) *httptest.Server {
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(code) }))
}

func TestHomeResolution(t *testing.T) {
	t.Setenv("YUKTA_HOME", "/a")
	if home() != "/a" {
		t.Error(home())
	}
	t.Setenv("YUKTA_HOME", "")
	t.Setenv("XDG_STATE_HOME", "/x")
	if home() != filepath.Join("/x", "yukta") {
		t.Error(home())
	}
	t.Setenv("XDG_STATE_HOME", "")
	t.Setenv("HOME", "/h")
	if home() != filepath.Join("/h", ".local", "state", "yukta") {
		t.Error(home())
	}
}

func TestReadPidErrors(t *testing.T) {
	d := t.TempDir()
	for name, body := range map[string]string{"empty": "", "bad": "abc"} {
		write(t, d, name, body)
		if _, _, err := readPid(filepath.Join(d, name)); err == nil {
			t.Errorf("%s: want error", name)
		}
	}
	if _, _, err := readPid(filepath.Join(d, "missing")); err == nil {
		t.Error("missing: want error")
	}
	write(t, d, "nostart", "42")
	if pid, started, err := readPid(filepath.Join(d, "nostart")); err != nil || pid != 42 || !started.IsZero() {
		t.Error(pid, started, err)
	}
	write(t, d, "badstart", "42 x")
	if _, started, _ := readPid(filepath.Join(d, "badstart")); !started.IsZero() {
		t.Error("bad start should be ignored")
	}
}

func TestStatusNotRunning(t *testing.T) {
	d := stateDir(t)
	if _, err := runStatus(t, "http://x", "table"); err == nil {
		t.Error("no pid file: want error")
	}
	write(t, d, "yukta.pid", "2147483646")
	if _, err := runStatus(t, "http://x", "table"); err == nil {
		t.Error("dead pid: want error")
	}
}

func TestStatusRunning(t *testing.T) {
	d := stateDir(t)
	ms := time.Now().Add(-ownAge(t)).UnixMilli()
	write(t, d, "yukta.pid", fmt.Sprintf("%d %d", os.Getpid(), ms))
	s := srv(200)
	defer s.Close()
	out, err := runStatus(t, s.URL, "table")
	if err != nil || !strings.Contains(out, "UP") || !strings.Contains(out, "uptime") {
		t.Errorf("%v %s", err, out)
	}
	out, _ = runStatus(t, s.URL, "json")
	if !strings.Contains(out, `"running": true`) && !strings.Contains(out, `"running":true`) {
		t.Error(out)
	}
	write(t, d, "yukta.pid", fmt.Sprint(os.Getpid()))
	if out, _ = runStatus(t, s.URL, "table"); !strings.Contains(out, "-") {
		t.Error(out)
	}
	s503 := srv(503)
	defer s503.Close()
	if out, _ = runStatus(t, s503.URL, "table"); !strings.Contains(out, "HTTP 503") {
		t.Error(out)
	}
	if out, _ = runStatus(t, "http://127.0.0.1:1", "table"); !strings.Contains(out, "not answering") {
		t.Error(out)
	}
}

func runLogs(ctx context.Context, t *testing.T, args ...string) (string, error) {
	cmd := logsCmd()
	var buf bytes.Buffer
	cmd.SetOut(&buf)
	cmd.SetArgs(args)
	err := cmd.ExecuteContext(ctx)
	return buf.String(), err
}

func TestLogsTail(t *testing.T) {
	d := stateDir(t)
	if _, err := runLogs(context.Background(), t); err == nil {
		t.Error("no log: want error")
	}
	var sb strings.Builder
	for i := 1; i <= 150; i++ {
		fmt.Fprintf(&sb, "line %d\n", i)
	}
	write(t, d, "yukta.log", sb.String())
	out, err := runLogs(context.Background(), t)
	if err != nil || strings.Contains(out, "line 50\n") || !strings.Contains(out, "line 51\n") || !strings.Contains(out, "line 150") {
		t.Errorf("%v %q", err, out)
	}
	write(t, d, "yukta.log", "one\ntwo\n")
	if out, _ = runLogs(context.Background(), t); out != "one\ntwo\n" {
		t.Errorf("%q", out)
	}
}

func TestLogsFollow(t *testing.T) {
	d := stateDir(t)
	write(t, d, "yukta.log", "first\n")
	followPoll = 10 * time.Millisecond
	ctx, cancel := context.WithCancel(context.Background())
	go func() {
		time.Sleep(50 * time.Millisecond)
		f, _ := os.OpenFile(filepath.Join(d, "yukta.log"), os.O_APPEND|os.O_WRONLY, 0o600)
		_, _ = f.WriteString("second\n")
		_ = f.Close()
		time.Sleep(100 * time.Millisecond)
		cancel()
	}()
	out, err := runLogs(ctx, t, "-f")
	if err != nil || !strings.Contains(out, "first\n") || !strings.Contains(out, "second\n") {
		t.Errorf("%v %q", err, out)
	}
}

func TestServerCmdHasSubcommands(t *testing.T) {
	cmd := ServerCmd(client.NewClient(""))
	if len(cmd.Commands()) != 2 {
		t.Error("want status and logs")
	}
}

func TestParseEtime(t *testing.T) {
	for in, want := range map[string]time.Duration{
		"05": 5 * time.Second, "01:05": 65 * time.Second, "02:01:05": 2*time.Hour + 65*time.Second,
		"3-02:01:05": 72*time.Hour + 2*time.Hour + 65*time.Second,
	} {
		if got, ok := parseEtime(in); !ok || got != want {
			t.Errorf("%s: %v %v", in, got, ok)
		}
	}
	for _, in := range []string{"", "x-01:00", "1:2:3:4", "a:b"} {
		if _, ok := parseEtime(in); ok {
			t.Errorf("%q should not parse", in)
		}
	}
}

func TestAliveRejectsRecycledPid(t *testing.T) {
	if _, err := exec.LookPath("ps"); err != nil {
		t.Skip("no ps")
	}
	if alive(os.Getpid(), time.Now().Add(-48*time.Hour)) {
		t.Error("a pid file written 2 days before this process started must not match")
	}
}

func TestAliveWithoutStartOrPs(t *testing.T) {
	if !alive(os.Getpid(), time.Time{}) {
		t.Error("no recorded start: trust the pid")
	}
	t.Setenv("PATH", "")
	if !alive(os.Getpid(), time.Now().Add(-48*time.Hour)) {
		t.Error("ps missing: cannot verify, trust the pid")
	}
}

// ownAge is how long this test process has been running, as ps reports it.
func ownAge(t *testing.T) time.Duration {
	out, err := exec.Command("ps", "-o", "etime=", "-p", fmt.Sprint(os.Getpid())).Output()
	if err != nil {
		t.Skip("no ps")
	}
	d, _ := parseEtime(strings.TrimSpace(string(out)))
	return d
}
