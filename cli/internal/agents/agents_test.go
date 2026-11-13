// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package agents

import (
	"bytes"
	"errors"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

const url = "http://localhost:9858/mcp"

// machine is a fake PC: a home and config folder in a temp dir, the agent CLIs it "has", and a record of
// the commands run against them.
type machine struct {
	t     *testing.T
	home  string
	cfg   string
	bins  map[string]bool
	fail  []string // a command starting with one of these fails
	calls []string
	out   bytes.Buffer
}

func newMachine(t *testing.T) *machine {
	t.Helper()
	root := t.TempDir()
	return &machine{t: t, home: filepath.Join(root, "home"), cfg: filepath.Join(root, "cfg"), bins: map[string]bool{}}
}

func (m *machine) env() Env {
	return Env{
		Home:   m.home,
		Config: m.cfg,
		LookPath: func(n string) (string, error) {
			if m.bins[n] {
				return n, nil
			}
			return "", errors.New("not found")
		},
		Run: func(name string, args ...string) error {
			call := strings.Join(append([]string{name}, args...), " ")
			m.calls = append(m.calls, call)
			for _, p := range m.fail {
				if strings.HasPrefix(call, p) {
					return errors.New("boom")
				}
			}
			return nil
		},
	}
}

func (m *machine) installer(o Options) *Installer {
	if o.URL == "" {
		o.URL = url
	}
	return &Installer{Env: m.env(), Opts: o, Out: &m.out}
}

func (m *machine) path(parts ...string) string {
	return filepath.Join(append([]string{m.home}, parts...)...)
}

func (m *machine) write(p, content string) {
	m.t.Helper()
	if err := os.MkdirAll(filepath.Dir(p), 0o750); err != nil {
		m.t.Fatal(err)
	}
	if err := os.WriteFile(p, []byte(content), 0o600); err != nil {
		m.t.Fatal(err)
	}
}

func (m *machine) read(p string) string {
	m.t.Helper()
	b, err := os.ReadFile(p)
	if err != nil {
		m.t.Fatal(err)
	}
	return string(b)
}

func (m *machine) exists(p string) bool {
	_, err := os.Stat(p)
	return err == nil
}

func (m *machine) agents(ids ...string) []Agent {
	m.t.Helper()
	as, err := Select(m.env(), ids)
	if err != nil {
		m.t.Fatal(err)
	}
	return as
}

// install runs Install for the agents and fails the test on a Go error.
func (m *machine) install(o Options, ids ...string) []Result {
	m.t.Helper()
	rs, err := m.installer(o).Install(m.agents(ids...))
	if err != nil {
		m.t.Fatal(err)
	}
	return rs
}

func one(t *testing.T, rs []Result) Result {
	t.Helper()
	if len(rs) != 1 {
		t.Fatalf("want one result, got %+v", rs)
	}
	return rs[0]
}

func entryOf(t *testing.T, m *machine, file, root string) map[string]any {
	t.Helper()
	doc, _, err := readJSON(file)
	if err != nil {
		t.Fatal(err)
	}
	s, _ := doc[root].(map[string]any)
	e, _ := s[serverName].(map[string]any)
	return e
}

func TestHeaders(t *testing.T) {
	if h, err := (Options{}).headers("${env:%s}"); h != nil || err != nil {
		t.Errorf("no key: %v %v", h, err)
	}
	if h, _ := (Options{Token: "k"}).headers("${env:%s}"); h["Authorization"] != "Bearer k" {
		t.Errorf("literal: %v", h)
	}
	if h, _ := (Options{Token: "k", TokenEnv: "V"}).headers("${env:%s}"); h["Authorization"] != "Bearer ${env:V}" {
		t.Errorf("env wins: %v", h)
	}
	if h, _ := (Options{Token: "k", TokenEnv: "V"}).headers(""); h["Authorization"] != "Bearer k" {
		t.Errorf("no env syntax, key present: %v", h)
	}
	if _, err := (Options{TokenEnv: "V"}).headers(""); err == nil {
		t.Error("an env var without a way to write it must fail")
	}
}

func TestInstall_jsonAgents(t *testing.T) {
	cases := []struct {
		id    string
		file  func(*machine) string
		root  string
		want  map[string]any
		skill string
	}{
		{"cursor", func(m *machine) string { return m.path(".cursor", "mcp.json") }, "mcpServers",
			map[string]any{"url": url}, ".cursor/skills"},
		{"windsurf", func(m *machine) string { return m.path(".codeium", "windsurf", "mcp_config.json") }, "mcpServers",
			map[string]any{"serverUrl": url}, ".codeium/windsurf/skills"},
		{"antigravity", func(m *machine) string { return m.path(".gemini", "config", "mcp_config.json") }, "mcpServers",
			map[string]any{"serverUrl": url}, ".gemini/skills"},
		{"vscode", func(m *machine) string { return filepath.Join(m.cfg, "Code", "User", "mcp.json") }, "servers",
			map[string]any{"type": "http", "url": url}, ".copilot/skills"},
		{"opencode", func(m *machine) string { return m.path(".config", "opencode", "opencode.json") }, "mcp",
			map[string]any{"type": "remote", "url": url, "enabled": true}, ".config/opencode/skills"},
	}
	for _, c := range cases {
		t.Run(c.id, func(t *testing.T) {
			m := newMachine(t)
			r := one(t, m.install(Options{}, c.id))
			if r.Status != "configured" {
				t.Fatalf("%+v", r)
			}
			if !same(entryOf(t, m, c.file(m), c.root), c.want) {
				t.Errorf("entry = %v, want %v", entryOf(t, m, c.file(m), c.root), c.want)
			}
			for _, name := range []string{"yukta-workflows", "yukta-supervisor"} {
				if !m.exists(filepath.Join(m.home, filepath.FromSlash(c.skill), name, "SKILL.md")) {
					t.Errorf("skill %s missing under %s", name, c.skill)
				}
			}
			if !m.exists(filepath.Join(m.cfg, "yukta", "agents.json")) {
				t.Error("manifest missing")
			}
		})
	}
}

func TestInstall_keepsOtherEntriesAndBacksUp(t *testing.T) {
	m := newMachine(t)
	file := m.path(".cursor", "mcp.json")
	original := `{"mcpServers":{"other":{"url":"http://x"}},"version":1.0}`
	m.write(file, original)
	m.install(Options{NoSkills: true}, "cursor")
	doc, _, _ := readJSON(file)
	if _, ok := doc["mcpServers"].(map[string]any)["other"]; !ok {
		t.Error("another server was dropped")
	}
	if !strings.Contains(m.read(file), "1.0") {
		t.Error("number formatting changed")
	}
	if m.read(file+backupSuffix) != original {
		t.Error("no backup of the original")
	}
	r := one(t, m.install(Options{NoSkills: true}, "cursor"))
	if !strings.Contains(r.Detail, "already in") {
		t.Errorf("a rerun should find it done: %+v", r)
	}
	if m.read(file+backupSuffix) != original {
		t.Error("the backup must stay the first original")
	}
}

func TestInstall_keys(t *testing.T) {
	m := newMachine(t)
	file := m.path(".cursor", "mcp.json")
	m.install(Options{Token: "s3cret", NoSkills: true}, "cursor")
	if got := entryOf(t, m, file, "mcpServers")["headers"].(map[string]any)["Authorization"]; got != "Bearer s3cret" {
		t.Errorf("literal key: %v", got)
	}
	if runtime.GOOS != "windows" {
		if info, _ := os.Stat(file); info.Mode().Perm() != 0o600 {
			t.Errorf("a file holding a key must be private, got %v", info.Mode())
		}
	}
	m.install(Options{Token: "s3cret", TokenEnv: "YUKTA_TOKEN", NoSkills: true}, "cursor", "opencode")
	if got := entryOf(t, m, file, "mcpServers")["headers"].(map[string]any)["Authorization"]; got != "Bearer ${env:YUKTA_TOKEN}" {
		t.Errorf("env reference: %v", got)
	}
	if strings.Contains(m.read(file), "s3cret") {
		t.Error("the key must not be written when an env var is named")
	}
	oc := entryOf(t, m, m.path(".config", "opencode", "opencode.json"), "mcp")
	if oc["headers"].(map[string]any)["Authorization"] != "Bearer {env:YUKTA_TOKEN}" {
		t.Errorf("opencode env syntax: %v", oc)
	}
	// Antigravity has no env syntax: without a key it fails, with one it writes the key.
	if r := one(t, m.install(Options{TokenEnv: "V", NoSkills: true}, "antigravity")); r.Status != "failed" {
		t.Errorf("%+v", r)
	}
	if r := one(t, m.install(Options{Token: "k", TokenEnv: "V", NoSkills: true}, "antigravity")); r.Status != "configured" {
		t.Errorf("%+v", r)
	}
}

func TestInstall_plainJSONOnly(t *testing.T) {
	m := newMachine(t)
	file := m.path(".cursor", "mcp.json")
	commented := "{\n  // my servers\n  \"mcpServers\": {}\n}\n"
	m.write(file, commented)
	r := one(t, m.install(Options{Token: "s3cret"}, "cursor"))
	if r.Status != "failed" || !strings.Contains(r.Detail, "add this yourself") || !strings.Contains(r.Detail, "<your key>") ||
		strings.Contains(r.Detail, "s3cret") {
		t.Errorf("want a hand-edit snippet without the key: %+v", r)
	}
	if m.read(file) != commented {
		t.Error("a file that is not plain JSON must not be touched")
	}
	m.write(file, `{"mcpServers": 1}`)
	if r := one(t, m.install(Options{}, "cursor")); r.Status != "failed" {
		t.Errorf("servers key that is not an object: %+v", r)
	}
	m.write(file, "")
	if r := one(t, m.install(Options{NoSkills: true}, "cursor")); r.Status != "configured" {
		t.Errorf("an empty file is fine: %+v", r)
	}
}

func TestInstall_skillsFolderBlocked(t *testing.T) {
	m := newMachine(t)
	m.write(m.path(".cursor", "skills"), "i am a file")
	r := one(t, m.install(Options{}, "cursor"))
	if r.Status != "failed" {
		t.Errorf("%+v", r)
	}
	if !Failed([]Result{r}) || Failed(nil) {
		t.Error("Failed")
	}
}

func TestInstall_onlyWhatWasAsked(t *testing.T) {
	m := newMachine(t)
	m.install(Options{NoSkills: true}, "cursor")
	if m.exists(m.path(".cursor", "skills")) {
		t.Error("--mcp-only copied skills")
	}
	m2 := newMachine(t)
	m2.install(Options{NoMCP: true}, "cursor", "generic")
	if m2.exists(m2.path(".cursor", "mcp.json")) {
		t.Error("--skills-only wrote MCP config")
	}
	if !m2.exists(m2.path(".agents", "skills", "yukta-workflows", "SKILL.md")) {
		t.Error("generic skills missing")
	}
}

func TestInstall_claudePlugin(t *testing.T) {
	m := newMachine(t)
	r := one(t, m.install(Options{}, "claude"))
	if r.Status != "configured" || !strings.Contains(r.Detail, "plugin") {
		t.Fatalf("%+v", r)
	}
	want := []string{"claude plugin marketplace add infenia/yukta", "claude plugin install yukta@yukta"}
	if strings.Join(m.calls, "|") != strings.Join(want, "|") {
		t.Errorf("calls = %v", m.calls)
	}
	if m.exists(m.path(".claude", "skills")) {
		t.Error("the plugin brings the skills; none should be copied")
	}
}

func TestInstall_claudeFallsBackToMCPAdd(t *testing.T) {
	m := newMachine(t)
	m.fail = []string{"claude plugin install"}
	r := one(t, m.install(Options{}, "claude"))
	if r.Status != "configured" {
		t.Fatalf("%+v", r)
	}
	if got := m.calls[len(m.calls)-1]; got != "claude mcp add --scope user --transport http yukta "+url {
		t.Errorf("last call = %q", got)
	}
	if !m.exists(m.path(".claude", "skills", "yukta-workflows", "SKILL.md")) {
		t.Error("the fallback must copy the skills")
	}
}

func TestInstall_claudeCustomServerSkipsPlugin(t *testing.T) {
	m := newMachine(t)
	m.install(Options{URL: "https://y.example/mcp", Token: "k"}, "claude")
	if got := m.calls[len(m.calls)-1]; !strings.HasSuffix(got, "https://y.example/mcp --header Authorization: Bearer k") {
		t.Errorf("last call = %q", got)
	}
	if strings.Contains(strings.Join(m.calls, "|"), "plugin") {
		t.Error("a plugin cannot carry a custom URL or key")
	}
	m.calls = nil
	m.install(Options{TokenEnv: "YUKTA_TOKEN"}, "claude")
	if got := m.calls[len(m.calls)-1]; !strings.HasSuffix(got, "Authorization: Bearer ${YUKTA_TOKEN}") {
		t.Errorf("last call = %q", got)
	}
	m.fail = []string{"claude mcp add"}
	if r := one(t, m.install(Options{TokenEnv: "YUKTA_TOKEN"}, "claude")); r.Status != "failed" {
		t.Errorf("%+v", r)
	}
}

func TestInstall_codex(t *testing.T) {
	m := newMachine(t)
	m.install(Options{NoSkills: true}, "codex")
	if got := m.calls[0]; got != "codex mcp add yukta --url "+url {
		t.Errorf("call = %q", got)
	}
	m.calls = nil
	m.install(Options{TokenEnv: "YUKTA_TOKEN", NoSkills: true}, "codex")
	if got := m.calls[0]; got != "codex mcp add yukta --url "+url+" --bearer-token-env-var YUKTA_TOKEN" {
		t.Errorf("call = %q", got)
	}
	if m.exists(m.path(".codex", "config.toml")) {
		t.Error("the CLI worked, so the file must stay alone")
	}
}

func TestInstall_codexConfigFile(t *testing.T) {
	m := newMachine(t)
	cfg := m.path(".codex", "config.toml")
	m.write(cfg, "model = \"x\"\n\n[mcp_servers.other]\nurl = \"http://o\"\n")
	m.fail = []string{"codex"}
	m.install(Options{Token: "k", NoSkills: true}, "codex")
	got := m.read(cfg)
	for _, want := range []string{"model = \"x\"", "[mcp_servers.other]", "[mcp_servers.yukta]", `http_headers = { Authorization = "Bearer k" }`} {
		if !strings.Contains(got, want) {
			t.Errorf("config lacks %q:\n%s", want, got)
		}
	}
	if len(m.calls) != 0 {
		t.Errorf("a literal key cannot go through the CLI: %v", m.calls)
	}
	// A rerun with another URL replaces our table instead of adding a second one.
	m.install(Options{URL: "http://other/mcp", Token: "k", NoSkills: true}, "codex")
	if n := strings.Count(m.read(cfg), "[mcp_servers.yukta]"); n != 1 {
		t.Errorf("%d tables", n)
	}
	m.install(Options{TokenEnv: "YUKTA_TOKEN", NoSkills: true}, "codex")
	if !strings.Contains(m.read(cfg), `bearer_token_env_var = "YUKTA_TOKEN"`) {
		t.Errorf("env var form missing:\n%s", m.read(cfg))
	}
	// Broken: the config is a folder.
	m2 := newMachine(t)
	if err := os.MkdirAll(m2.path(".codex", "config.toml"), 0o750); err != nil {
		t.Fatal(err)
	}
	if r := one(t, m2.install(Options{Token: "k"}, "codex")); r.Status != "failed" {
		t.Errorf("%+v", r)
	}
}

func TestDryRun(t *testing.T) {
	m := newMachine(t)
	rs := m.install(Options{DryRun: true, URL: "https://y.example/mcp", Token: "s3cret"}, "claude", "codex", "cursor")
	for _, r := range rs {
		if r.Status != "dry-run" {
			t.Errorf("%+v", r)
		}
	}
	out := m.out.String()
	for _, want := range []string{"would run: claude mcp add", "Authorization: <hidden>", "would copy skills"} {
		if !strings.Contains(out, want) {
			t.Errorf("output lacks %q:\n%s", want, out)
		}
	}
	if strings.Contains(out, "s3cret") {
		t.Error("a dry run must not print the key")
	}
	if len(m.calls) != 0 || m.exists(m.path(".cursor")) || m.exists(filepath.Join(m.cfg, "yukta")) {
		t.Errorf("a dry run changed something: calls=%v", m.calls)
	}
}

func TestUninstall(t *testing.T) {
	m := newMachine(t)
	cursor := m.path(".cursor", "mcp.json")
	m.write(cursor, `{"mcpServers":{"other":{"url":"http://x"}}}`)
	toml := m.path(".codex", "config.toml")
	m.write(toml, "model = \"x\"\n")
	m.fail = []string{"codex"} // so codex uses the file
	m.install(Options{Token: "k"}, "cursor", "codex", "generic")
	m.fail = nil
	m.install(Options{}, "claude")
	in := m.installer(Options{})
	all, err := in.Installed()
	if err != nil || len(all) != 4 {
		t.Fatalf("installed = %v, %v", all, err)
	}
	m.calls = nil
	rs, err := in.Uninstall(all)
	if err != nil {
		t.Fatal(err)
	}
	for _, r := range rs {
		if r.Status != "removed" {
			t.Errorf("%+v", r)
		}
	}
	if doc, _, _ := readJSON(cursor); doc["mcpServers"].(map[string]any)["other"] == nil || doc["mcpServers"].(map[string]any)[serverName] != nil {
		t.Errorf("cursor config after uninstall: %v", doc)
	}
	if got := m.read(toml); strings.Contains(got, "yukta") || !strings.Contains(got, "model") {
		t.Errorf("toml after uninstall: %q", got)
	}
	if m.exists(m.path(".cursor", "skills", "yukta-workflows")) || m.exists(m.path(".agents", "skills", "yukta-workflows")) {
		t.Error("skills left behind")
	}
	if len(m.calls) != 1 || m.calls[0] != "claude plugin uninstall yukta@yukta" {
		t.Errorf("undo calls = %v", m.calls)
	}
	rs, _ = in.Uninstall(all)
	if rs[0].Status != "skipped" {
		t.Errorf("second uninstall: %+v", rs)
	}
}

func TestUninstall_failures(t *testing.T) {
	m := newMachine(t)
	m.install(Options{NoSkills: true}, "codex", "cursor")
	m.fail = []string{"codex mcp remove"}
	in := m.installer(Options{})
	rs, _ := in.Uninstall(m.agents("codex"))
	if one(t, rs).Status != "failed" {
		t.Errorf("%+v", rs)
	}
	if got, _ := in.Installed(); len(got) != 2 {
		t.Error("a failed removal must stay in the manifest")
	}
	m.write(m.path(".cursor", "mcp.json"), "{ // c\n}")
	if rs, _ := in.Uninstall(m.agents("cursor")); one(t, rs).Status != "failed" {
		t.Errorf("cursor with a hand-edited file: %+v", rs)
	}
	m2 := newMachine(t)
	m2.fail = []string{"codex"}
	m2.install(Options{Token: "k", NoSkills: true}, "codex")
	if err := os.Remove(m2.path(".codex", "config.toml")); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(m2.path(".codex", "config.toml"), 0o750); err != nil {
		t.Fatal(err)
	}
	if rs, _ := m2.installer(Options{}).Uninstall(m2.agents("codex")); one(t, rs).Status != "failed" {
		t.Errorf("config.toml turned into a folder: %+v", rs)
	}
}

func TestManifestProblems(t *testing.T) {
	m := newMachine(t)
	manifest := filepath.Join(m.cfg, "yukta", "agents.json")
	for _, c := range []struct {
		name  string
		setup func()
	}{
		{"damaged", func() { _ = os.RemoveAll(manifest); m.write(manifest, "{nope") }},
		{"unreadable", func() { _ = os.RemoveAll(manifest); _ = os.MkdirAll(manifest, 0o750) }},
	} {
		name := c.name
		c.setup()
		in := m.installer(Options{})
		if _, err := in.Install(m.agents("cursor")); err == nil {
			t.Errorf("%s: Install", name)
		}
		if _, err := in.Uninstall(m.agents("cursor")); err == nil {
			t.Errorf("%s: Uninstall", name)
		}
		if _, err := in.Installed(); err == nil {
			t.Errorf("%s: Installed", name)
		}
		if _, err := in.Refresh(); err == nil {
			t.Errorf("%s: Refresh", name)
		}
		if _, err := in.List(); err == nil {
			t.Errorf("%s: List", name)
		}
	}
}

func TestRefresh(t *testing.T) {
	m := newMachine(t)
	m.install(Options{}, "cursor", "generic")
	skill := m.path(".cursor", "skills", "yukta-workflows", "SKILL.md")
	m.write(skill, "stale")
	rs, err := m.installer(Options{}).Refresh()
	if err != nil || len(rs) != 2 || rs[0].Status != "configured" {
		t.Fatalf("%+v %v", rs, err)
	}
	if m.read(skill) == "stale" {
		t.Error("skills not refreshed")
	}
	if err := os.RemoveAll(m.path(".agents", "skills")); err != nil {
		t.Fatal(err)
	}
	m.write(m.path(".agents", "skills"), "a file now")
	rs, _ = m.installer(Options{}).Refresh()
	if !Failed(rs) {
		t.Errorf("%+v", rs)
	}
	if rs, _ := m.installer(Options{DryRun: true}).Refresh(); Failed(rs) {
		t.Errorf("dry run: %+v", rs)
	}
}

func TestList(t *testing.T) {
	m := newMachine(t)
	m.bins["codex"] = true
	m.install(Options{NoSkills: true}, "cursor")
	st, err := m.installer(Options{}).List()
	if err != nil || len(st) != len(registry()) {
		t.Fatal(st, err)
	}
	byID := map[string]Status{}
	for _, s := range st {
		byID[s.ID] = s
	}
	if !byID["cursor"].Installed || !byID["cursor"].Detected || !byID["codex"].Detected || byID["codex"].Installed || byID["claude"].Detected {
		t.Errorf("%+v", byID)
	}
}

func TestSelect(t *testing.T) {
	m := newMachine(t)
	if got, _ := Select(m.env(), nil); len(got) != 0 {
		t.Errorf("nothing is installed: %v", got)
	}
	m.bins["claude"] = true
	got, _ := Select(m.env(), []string{"auto"})
	if len(got) != 1 || got[0].ID != "claude" {
		t.Errorf("auto = %v", got)
	}
	if got, _ := Select(m.env(), []string{"all"}); len(got) != len(registry()) {
		t.Errorf("all = %d", len(got))
	}
	if got, err := Select(m.env(), []string{"cursor", " codex "}); err != nil || len(got) != 2 {
		t.Errorf("named = %v %v", got, err)
	}
	if _, err := Select(m.env(), []string{"nope"}); err == nil || !strings.Contains(err.Error(), "known:") {
		t.Errorf("unknown: %v", err)
	}
	if ids := IDs(); len(ids) != len(registry()) || ids[0] > ids[1] {
		t.Errorf("ids = %v", ids)
	}
}

func TestDetect(t *testing.T) {
	bins := map[string]string{"claude": "claude", "codex": "codex", "antigravity": "agy", "cursor": "cursor", "vscode": "code", "windsurf": "windsurf", "opencode": "opencode"}
	dirs := map[string][]string{
		"codex": {".codex"}, "antigravity": {".gemini", "antigravity"}, "cursor": {".cursor"},
		"windsurf": {".codeium", "windsurf"}, "opencode": {".config", "opencode"},
	}
	for _, a := range registry() {
		m := newMachine(t)
		if a.detect(m.env()) {
			t.Errorf("%s detected on an empty machine", a.ID)
		}
		if bin, ok := bins[a.ID]; ok {
			m.bins[bin] = true
			if !a.detect(m.env()) {
				t.Errorf("%s not detected from %s", a.ID, bin)
			}
		}
		m = newMachine(t)
		if d, ok := dirs[a.ID]; ok {
			m.write(m.path(append(d, "x")...), "")
			if !a.detect(m.env()) {
				t.Errorf("%s not detected from %v", a.ID, d)
			}
		}
	}
	m := newMachine(t)
	m.write(filepath.Join(m.cfg, "Code", "User", "settings.json"), "{}")
	if a := m.agents("vscode")[0]; !a.detect(m.env()) {
		t.Error("vscode not detected from its user folder")
	}
}

func TestOpencodeFile(t *testing.T) {
	m := newMachine(t)
	dir := m.path(".config", "opencode")
	if got := opencodeFile(m.env()); got != filepath.Join(dir, "opencode.json") {
		t.Errorf("neither: %s", got)
	}
	m.write(filepath.Join(dir, "opencode.jsonc"), "{}")
	if got := opencodeFile(m.env()); got != filepath.Join(dir, "opencode.jsonc") {
		t.Errorf("jsonc only: %s", got)
	}
	m.write(filepath.Join(dir, "opencode.json"), "{}")
	if got := opencodeFile(m.env()); got != filepath.Join(dir, "opencode.json") {
		t.Errorf("both: %s", got)
	}
}

func TestDefaultEnv(t *testing.T) {
	e, err := DefaultEnv()
	if err != nil || e.Home == "" || e.Config == "" || e.LookPath == nil || e.Run == nil {
		t.Fatalf("%+v %v", e, err)
	}
	if runtime.GOOS != "linux" {
		return
	}
	t.Setenv("HOME", "")
	if _, err := DefaultEnv(); err == nil {
		t.Error("no home must fail")
	}
	t.Setenv("HOME", t.TempDir())
	t.Setenv("XDG_CONFIG_HOME", "relative")
	if _, err := DefaultEnv(); err == nil {
		t.Error("a relative XDG_CONFIG_HOME must fail")
	}
}

func TestRun(t *testing.T) {
	if err := run(os.Args[0], "-test.run=^$"); err != nil {
		t.Errorf("a command that works: %v", err)
	}
	if err := run(os.Args[0], "-test.nosuchflag"); err == nil || !strings.Contains(err.Error(), "nosuchflag") {
		t.Errorf("a failing command should report its last line: %v", err)
	}
}
