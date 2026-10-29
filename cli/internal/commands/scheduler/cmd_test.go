// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package scheduler

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands"
)

// serve answers GET with the stored limits and POST by changing them, like the server.
func serve(t *testing.T, calls *[]string) *httptest.Server {
	limits := map[string]int{"maxConcurrent": 4, "maxPerWorkflow": 2, "running": 3, "queued": 1, "queueCapacity": 100}
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		*calls = append(*calls, r.Method+" "+r.URL.RequestURI())
		if r.URL.Path != "/api/scheduler/limits" {
			t.Errorf("unexpected path %s", r.URL.Path)
		}
		if r.Method == "POST" {
			limits["maxConcurrent"], _ = atoi(r.URL.Query().Get("maxConcurrent"))
			limits["maxPerWorkflow"], _ = atoi(r.URL.Query().Get("maxPerWorkflow"))
		}
		_ = json.NewEncoder(w).Encode(map[string]interface{}{"data": limits})
	}))
}

func atoi(s string) (int, error) {
	var n int
	err := json.Unmarshal([]byte(s), &n)
	return n, err
}

// run executes the command with args and returns what it printed.
func run(t *testing.T, c *client.Client, format string, args ...string) (string, error) {
	orig := commands.GetOutputFormat()
	commands.SetTestOutputFormat(format)
	defer commands.SetTestOutputFormat(orig)

	r, w, _ := os.Pipe()
	old := os.Stdout
	os.Stdout = w
	cmd := SchedulerCmd(c)
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

func TestLimitsShowsTheSnapshot(t *testing.T) {
	var calls []string
	server := serve(t, &calls)
	defer server.Close()

	out, err := run(t, client.NewClient(server.URL), "table", "limits")
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"maxConcurrent", "4", "queued", "100"} {
		if !strings.Contains(out, want) {
			t.Errorf("output lacks %q: %s", want, out)
		}
	}
	if len(calls) != 1 || calls[0] != "GET /api/scheduler/limits" {
		t.Errorf("calls = %v", calls)
	}
}

func TestLimitsSetChangesThemAndShowsTheResult(t *testing.T) {
	var calls []string
	server := serve(t, &calls)
	defer server.Close()

	out, err := run(t, client.NewClient(server.URL), "json", "limits", "set", "--max-concurrent", "8", "--max-per-workflow", "0")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(out, `"maxConcurrent": 8`) {
		t.Errorf("output = %s", out)
	}
	want := []string{"POST /api/scheduler/limits?maxConcurrent=8&maxPerWorkflow=0", "GET /api/scheduler/limits"}
	if strings.Join(calls, "|") != strings.Join(want, "|") {
		t.Errorf("calls = %v, want %v", calls, want)
	}
}

func TestLimitsSetNeedsBothFlagsAndNonNegativeValues(t *testing.T) {
	c := client.NewClient("http://127.0.0.1:1")
	if _, err := run(t, c, "table", "limits", "set", "--max-concurrent", "1"); err == nil {
		t.Error("expected an error without --max-per-workflow")
	}
	if _, err := run(t, c, "table", "limits", "set", "--max-concurrent", "-1", "--max-per-workflow", "0"); err == nil {
		t.Error("expected an error for a negative limit")
	}
}

func TestLimitsReportsServerFailures(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, `{"message":"no"}`, http.StatusForbidden)
	}))
	defer server.Close()
	c := client.NewClient(server.URL)

	if _, err := run(t, c, "table", "limits"); err == nil {
		t.Error("expected an error on GET")
	}
	if _, err := run(t, c, "table", "limits", "set", "--max-concurrent", "1", "--max-per-workflow", "1"); err == nil {
		t.Error("expected an error on POST")
	}
}

func TestLimitsReportsAnUnreadableBody(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte("not json"))
	}))
	defer server.Close()
	if _, err := run(t, client.NewClient(server.URL), "table", "limits"); err == nil {
		t.Error("expected an error")
	}
}
