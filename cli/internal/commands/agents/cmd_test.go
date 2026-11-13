// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package agents

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	ag "com.infenia.yukta/go-cli/internal/agents"
	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands"
)

// pc is a fake machine: its folders, the agent CLIs it has and the commands run against them.
type pc struct {
	t     *testing.T
	home  string
	cfg   string
	bins  map[string]bool
	calls []string
}

func newPC(t *testing.T) *pc {
	t.Helper()
	root := t.TempDir()
	p := &pc{t: t, home: filepath.Join(root, "home"), cfg: filepath.Join(root, "cfg"), bins: map[string]bool{}}
	old := defaultEnv
	defaultEnv = func() (ag.Env, error) {
		return ag.Env{Home: p.home, Config: p.cfg,
			LookPath: func(n string) (string, error) {
				if p.bins[n] {
					return n, nil
				}
				return "", errors.New("not found")
			},
			Run: func(name string, args ...string) error {
				p.calls = append(p.calls, strings.Join(append([]string{name}, args...), " "))
				return nil
			}}, nil
	}
	t.Cleanup(func() { defaultEnv = old })
	t.Setenv("YUKTA_TOKEN", "")
	t.Setenv("YUKTA_SERVER_URL", "")
	return p
}

func (p *pc) write(rel, content string) string {
	p.t.Helper()
	f := filepath.Join(p.home, rel)
	if err := os.MkdirAll(filepath.Dir(f), 0o750); err != nil {
		p.t.Fatal(err)
	}
	if err := os.WriteFile(f, []byte(content), 0o600); err != nil {
		p.t.Fatal(err)
	}
	return f
}

// breakManifest makes the manifest unreadable: a folder where the file should be.
func (p *pc) breakManifest() {
	p.t.Helper()
	m := filepath.Join(p.cfg, "yukta", "agents.json")
	_ = os.RemoveAll(m)
	if err := os.MkdirAll(m, 0o750); err != nil {
		p.t.Fatal(err)
	}
}

// run executes "yukta-cli <args>" and returns what it printed to stdout and stderr.
func run(t *testing.T, args ...string) (string, string, error) {
	t.Helper()
	c := client.NewClient("")
	root := commands.RootCmd(c)
	root.AddCommand(AgentsCmd(c))
	var errBuf bytes.Buffer
	root.SetErr(&errBuf)
	root.SetArgs(args)
	r, w, _ := os.Pipe()
	old := os.Stdout
	os.Stdout = w
	root.SetOut(w)
	err := root.Execute()
	w.Close()
	os.Stdout = old
	var out bytes.Buffer
	_, _ = io.Copy(&out, r)
	return out.String(), errBuf.String(), err
}

func server(t *testing.T, authEnabled string) string {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte(`{"data":{"role":"ADMIN","authEnabled":` + authEnabled + `}}`))
	}))
	t.Cleanup(srv.Close)
	return srv.URL
}

func TestHelpWithoutSubcommand(t *testing.T) {
	newPC(t)
	out, _, err := run(t, "agents")
	if err != nil || !strings.Contains(out, "install") || !strings.Contains(out, "cursor") {
		t.Errorf("%v %q", err, out)
	}
}

func TestInstall_nothingFound(t *testing.T) {
	newPC(t)
	out, _, err := run(t, "agents", "install")
	if err != nil || !strings.Contains(out, "No supported AI agent found") {
		t.Errorf("%v %q", err, out)
	}
}

func TestInstall_namedAgentAndServerNotes(t *testing.T) {
	p := newPC(t)
	open := server(t, "false")
	out, stderr, err := run(t, "--url", open, "agents", "install", "--agent", "cursor")
	if err != nil || !strings.Contains(out, "configured") || strings.Contains(stderr, "note:") {
		t.Fatalf("%v out=%q err=%q", err, out, stderr)
	}
	cfg, _ := os.ReadFile(filepath.Join(p.home, ".cursor", "mcp.json"))
	if !strings.Contains(string(cfg), open+"/mcp") {
		t.Errorf("the MCP URL must follow --url: %s", cfg)
	}
	secured := server(t, "true")
	if _, stderr, _ := run(t, "--url", secured, "agents", "install", "--agent", "cursor"); !strings.Contains(stderr, "needs an API key") {
		t.Errorf("stderr = %q", stderr)
	}
	if _, stderr, _ := run(t, "--url", secured, "agents", "install", "--agent", "cursor", "--token-env", "YUKTA_TOKEN"); strings.Contains(stderr, "note:") {
		t.Errorf("a key is given: %q", stderr)
	}
	if _, stderr, _ := run(t, "--url", "http://127.0.0.1:1", "agents", "install", "--agent", "cursor"); !strings.Contains(stderr, "no Yukta server answered") {
		t.Errorf("stderr = %q", stderr)
	}
}

func TestInstall_tokenFileAndDryRunJSON(t *testing.T) {
	p := newPC(t)
	keyFile := filepath.Join(t.TempDir(), "key")
	if err := os.WriteFile(keyFile, []byte("s3cret\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	out, stderr, err := run(t, "-o", "json", "--url", "http://127.0.0.1:1", "--token-file", keyFile, "agents", "install", "--agent", "cursor", "--dry-run")
	if err != nil {
		t.Fatal(err)
	}
	var rs []ag.Result
	if err := json.Unmarshal([]byte(out), &rs); err != nil || len(rs) != 1 || rs[0].Status != "dry-run" {
		t.Errorf("stdout must be clean JSON: %v %q", err, out)
	}
	if !strings.Contains(stderr, "would copy skills") || strings.Contains(out+stderr, "s3cret") {
		t.Errorf("stderr = %q", stderr)
	}
	if _, err := os.Stat(filepath.Join(p.home, ".cursor")); err == nil {
		t.Error("dry run wrote files")
	}
	if _, _, err := run(t, "--url", "http://127.0.0.1:1", "--token-file", keyFile, "agents", "install", "--agent", "cursor"); err != nil {
		t.Fatal(err)
	}
	cfg, _ := os.ReadFile(filepath.Join(p.home, ".cursor", "mcp.json"))
	if !strings.Contains(string(cfg), "Bearer s3cret") {
		t.Errorf("the key from --token-file must reach the agent: %s", cfg)
	}
}

func TestInstall_detectedClaudeUsesPlugin(t *testing.T) {
	p := newPC(t)
	p.bins["claude"] = true
	if _, _, err := run(t, "agents", "install"); err != nil {
		t.Fatal(err)
	}
	if len(p.calls) != 2 || p.calls[1] != "claude plugin install yukta@yukta" {
		t.Errorf("calls = %v", p.calls)
	}
}

func TestInstall_errors(t *testing.T) {
	p := newPC(t)
	if _, _, err := run(t, "agents", "install", "--skills-only", "--mcp-only"); err == nil {
		t.Error("both only-flags")
	}
	if _, _, err := run(t, "agents", "install", "--agent", "nope"); err == nil {
		t.Error("unknown agent")
	}
	p.write(".cursor/mcp.json", "{ // hand edited\n}")
	out, _, err := run(t, "agents", "install", "--agent", "cursor")
	if err == nil || !strings.Contains(out, "failed") {
		t.Errorf("a failed agent must fail the command: %v %q", err, out)
	}
	p.breakManifest()
	if _, _, err := run(t, "agents", "install", "--agent", "codex"); err == nil {
		t.Error("an unreadable manifest")
	}
	defaultEnv = func() (ag.Env, error) { return ag.Env{}, errors.New("no home") }
	for _, args := range [][]string{{"install"}, {"uninstall", "--all"}, {"list"}} {
		if _, _, err := run(t, append([]string{"agents"}, args...)...); err == nil {
			t.Errorf("%v with no home", args)
		}
	}
}

func TestRefreshAndUninstall(t *testing.T) {
	p := newPC(t)
	if _, _, err := run(t, "agents", "install", "--agent", "cursor,generic"); err != nil {
		t.Fatal(err)
	}
	skill := filepath.Join(p.home, ".cursor", "skills", "yukta-workflows", "SKILL.md")
	if err := os.WriteFile(skill, []byte("stale"), 0o600); err != nil {
		t.Fatal(err)
	}
	out, _, err := run(t, "agents", "install", "--refresh")
	if err != nil || !strings.Contains(out, "skills refreshed") {
		t.Errorf("%v %q", err, out)
	}
	if b, _ := os.ReadFile(skill); string(b) == "stale" {
		t.Error("not refreshed")
	}

	if _, _, err := run(t, "agents", "uninstall"); err == nil {
		t.Error("uninstall must be told what to remove")
	}
	if _, _, err := run(t, "agents", "uninstall", "--agent", "nope"); err == nil {
		t.Error("unknown agent")
	}
	out, _, err = run(t, "agents", "uninstall", "--agent", "generic")
	if err != nil || !strings.Contains(out, "removed") {
		t.Errorf("%v %q", err, out)
	}
	out, _, err = run(t, "agents", "uninstall", "--all")
	if err != nil || !strings.Contains(out, "cursor") {
		t.Errorf("%v %q", err, out)
	}
	if _, err := os.Stat(skill); err == nil {
		t.Error("skill left behind")
	}
	p.breakManifest()
	if _, _, err := run(t, "agents", "uninstall", "--all"); err == nil {
		t.Error("--all with an unreadable manifest")
	}
	if _, _, err := run(t, "agents", "install", "--refresh"); err == nil {
		t.Error("--refresh with an unreadable manifest")
	}
}

func TestList(t *testing.T) {
	p := newPC(t)
	p.bins["codex"] = true
	out, _, err := run(t, "agents", "list")
	if err != nil || !strings.Contains(out, "CONNECTED") || !strings.Contains(out, "Codex") {
		t.Errorf("%v %q", err, out)
	}
	out, _, err = run(t, "-o", "json", "agents", "list")
	var st []ag.Status
	if err != nil || json.Unmarshal([]byte(out), &st) != nil || len(st) < 7 {
		t.Errorf("%v %q", err, out)
	}
	p.breakManifest()
	if _, _, err := run(t, "agents", "list"); err == nil {
		t.Error("an unreadable manifest")
	}
}

func TestYesNo(t *testing.T) {
	if yesNo(true) != "yes" || yesNo(false) != "no" {
		t.Error("yesNo")
	}
}
