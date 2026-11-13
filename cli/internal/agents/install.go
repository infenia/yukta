// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package agents

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

// Installer applies Options to agents on one machine.
type Installer struct {
	Env  Env
	Opts Options
	Out  io.Writer // progress lines of a dry run
}

// Result is the outcome for one agent.
type Result struct {
	ID     string `json:"id"`
	Name   string `json:"name"`
	Status string `json:"status"` // configured, dry-run, removed, skipped or failed
	Detail string `json:"detail"`
}

// Failed reports whether any agent failed.
func Failed(rs []Result) bool {
	for _, r := range rs {
		if r.Status == "failed" {
			return true
		}
	}
	return false
}

func (in *Installer) manifestPath() string {
	return filepath.Join(in.Env.Config, "yukta", "agents.json")
}

func (in *Installer) load() (map[string]Change, error) {
	m := map[string]Change{}
	raw, err := os.ReadFile(in.manifestPath())
	if errors.Is(err, fs.ErrNotExist) {
		return m, nil
	}
	if err != nil {
		return nil, err
	}
	if err := json.Unmarshal(raw, &m); err != nil {
		return nil, fmt.Errorf("%s is damaged (%w); delete it and run install again", in.manifestPath(), err)
	}
	return m, nil
}

func (in *Installer) save(m map[string]Change) error {
	data, _ := json.MarshalIndent(m, "", "  ") // plain structs cannot fail
	return writeAtomic(in.manifestPath(), append(data, '\n'), false)
}

// exec runs an agent's CLI; a dry run only says so, and never shows a key.
func (in *Installer) exec(args ...string) error {
	if in.Opts.DryRun {
		shown := make([]string, len(args))
		for i, a := range args {
			shown[i] = a
			if strings.HasPrefix(a, "Authorization: ") {
				shown[i] = "Authorization: <hidden>"
			}
		}
		_, err := fmt.Fprintf(in.Out, "  would run: %s\n", strings.Join(shown, " "))
		return err
	}
	return in.Env.Run(args[0], args[1:]...)
}

// Install configures each agent and records what it did.
func (in *Installer) Install(agents []Agent) ([]Result, error) {
	m, err := in.load()
	if err != nil {
		return nil, err
	}
	var rs []Result
	for _, a := range agents {
		ch, err := in.installOne(a)
		if err != nil {
			rs = append(rs, Result{a.ID, a.Name, "failed", err.Error()})
			continue
		}
		status := "configured"
		if in.Opts.DryRun {
			status = "dry-run"
		}
		m[a.ID] = ch
		rs = append(rs, Result{a.ID, a.Name, status, ch.Note})
	}
	if in.Opts.DryRun {
		return rs, nil
	}
	return rs, in.save(m)
}

func (in *Installer) installOne(a Agent) (Change, error) {
	var ch Change
	var err error
	if a.mcp != nil && !in.Opts.NoMCP {
		if ch, err = a.mcp(in); err != nil {
			return Change{}, err
		}
	}
	notes := []string{ch.Note}
	if !in.Opts.NoSkills && !ch.Plugin {
		for _, dir := range a.skills(in.Env) {
			if err := in.copySkills(dir); err != nil {
				return Change{}, err
			}
			ch.Skills = append(ch.Skills, dir)
			notes = append(notes, "skills in "+dir)
		}
	}
	ch.Note = strings.Join(trim(notes), "; ")
	return ch, nil
}

func trim(in []string) []string {
	out := in[:0]
	for _, s := range in {
		if s != "" {
			out = append(out, s)
		}
	}
	return out
}

func (in *Installer) copySkills(dir string) error {
	if in.Opts.DryRun {
		_, err := fmt.Fprintf(in.Out, "  would copy skills %s into %s\n", strings.Join(skillNames(), ", "), dir)
		return err
	}
	return copySkills(dir)
}

func (in *Installer) writeJSONEntry(path string, spec jsonSpec) (Change, error) {
	h, err := in.Opts.headers(spec.envRef)
	if err != nil {
		return Change{}, err
	}
	ch := Change{File: path, Format: "json", Root: spec.root}
	changed, err := setJSON(path, spec.root, serverName, spec.entry(in.Opts.URL, h), !in.Opts.DryRun)
	switch {
	case errors.Is(err, ErrNotJSON):
		return Change{}, fmt.Errorf("%w\n  not edited; add this yourself under %q:\n%s", err, spec.root, in.snippet(spec))
	case err != nil:
		return Change{}, err
	case changed:
		ch.Note = "MCP server in " + path
	default:
		ch.Note = "MCP server already in " + path
	}
	return ch, nil
}

// snippet is the entry to paste by hand, with the key hidden.
func (in *Installer) snippet(spec jsonSpec) string {
	var h map[string]any
	if in.Opts.Token != "" || in.Opts.TokenEnv != "" {
		h = map[string]any{"Authorization": "Bearer <your key>"}
	}
	var b strings.Builder
	enc := json.NewEncoder(&b)
	enc.SetEscapeHTML(false) // keep <your key> readable
	enc.SetIndent("", "  ")
	_ = enc.Encode(map[string]any{serverName: spec.entry(in.Opts.URL, h)}) // plain maps cannot fail
	return b.String()
}

func opencodeFile(e Env) string {
	dir := filepath.Join(e.Home, ".config", "opencode")
	if _, err := os.Stat(filepath.Join(dir, "opencode.json")); err != nil {
		if _, err := os.Stat(filepath.Join(dir, "opencode.jsonc")); err == nil {
			return filepath.Join(dir, "opencode.jsonc")
		}
	}
	return filepath.Join(dir, "opencode.json")
}

// claude prefers the Yukta plugin (MCP, skills and the live view); a plugin cannot carry a custom URL
// or key, so those and a failed plugin install fall back to `claude mcp add` plus the skills.
func (in *Installer) claude() (Change, error) {
	o := in.Opts
	if o.URL == DefaultMCPURL && o.Token == "" && o.TokenEnv == "" {
		_ = in.exec("claude", "plugin", "marketplace", "add", "infenia/yukta") // fails when already added
		if err := in.exec("claude", "plugin", "install", "yukta@yukta"); err == nil {
			return Change{Plugin: true, Note: "plugin yukta@yukta",
				Undo: [][]string{{"claude", "plugin", "uninstall", "yukta@yukta"}}}, nil
		}
	}
	_ = in.exec("claude", "mcp", "remove", "--scope", "user", serverName) // so a rerun replaces it
	args := []string{"claude", "mcp", "add", "--scope", "user", "--transport", "http", serverName, o.URL}
	if h, _ := o.headers("${%s}"); h != nil {
		args = append(args, "--header", "Authorization: "+h["Authorization"].(string))
	}
	if err := in.exec(args...); err != nil {
		return Change{}, err
	}
	return Change{Note: "MCP server via claude mcp add",
		Undo: [][]string{{"claude", "mcp", "remove", "--scope", "user", serverName}}}, nil
}

// codex uses `codex mcp add` when it can express the key, else writes its config.toml.
func (in *Installer) codex() (Change, error) {
	o := in.Opts
	if o.Token == "" || o.TokenEnv != "" {
		args := []string{"codex", "mcp", "add", serverName, "--url", o.URL}
		if o.TokenEnv != "" {
			args = append(args, "--bearer-token-env-var", o.TokenEnv)
		}
		if err := in.exec(args...); err == nil {
			return Change{Note: "MCP server via codex mcp add", Undo: [][]string{{"codex", "mcp", "remove", serverName}}}, nil
		}
	}
	path := filepath.Join(in.Env.Home, ".codex", "config.toml")
	if _, err := setTOML(path, serverName, tomlBlock(serverName, o.URL, o.Token, o.TokenEnv), !o.DryRun); err != nil {
		return Change{}, err
	}
	return Change{File: path, Format: "toml", Note: "MCP server in " + path}, nil
}

// Uninstall removes what Install recorded for the agents.
func (in *Installer) Uninstall(agents []Agent) ([]Result, error) {
	m, err := in.load()
	if err != nil {
		return nil, err
	}
	var rs []Result
	for _, a := range agents {
		ch, ok := m[a.ID]
		if !ok {
			rs = append(rs, Result{a.ID, a.Name, "skipped", "not installed by Yukta"})
			continue
		}
		if err := in.undo(ch); err != nil {
			rs = append(rs, Result{a.ID, a.Name, "failed", err.Error()})
			continue
		}
		delete(m, a.ID)
		rs = append(rs, Result{a.ID, a.Name, "removed", ""})
	}
	return rs, in.save(m)
}

func (in *Installer) undo(ch Change) error {
	for _, cmd := range ch.Undo {
		if err := in.Env.Run(cmd[0], cmd[1:]...); err != nil {
			return err
		}
	}
	switch ch.Format {
	case "json":
		if err := removeJSON(ch.File, ch.Root, serverName); err != nil {
			return err
		}
	case "toml":
		if err := removeTOML(ch.File, serverName); err != nil {
			return err
		}
	}
	var err error
	for _, dir := range ch.Skills {
		err = errors.Join(err, removeSkills(dir))
	}
	return err
}

// Installed lists the agents the manifest holds.
func (in *Installer) Installed() ([]Agent, error) {
	m, err := in.load()
	if err != nil {
		return nil, err
	}
	var out []Agent
	for _, a := range registry() {
		if _, ok := m[a.ID]; ok {
			out = append(out, a)
		}
	}
	return out, nil
}

// Refresh copies the current skills again for every installed agent (after an upgrade).
func (in *Installer) Refresh() ([]Result, error) {
	m, err := in.load()
	if err != nil {
		return nil, err
	}
	ids := make([]string, 0, len(m))
	for id := range m {
		ids = append(ids, id)
	}
	sort.Strings(ids)
	var rs []Result
	for _, id := range ids {
		var err error
		for _, dir := range m[id].Skills {
			if err = in.copySkills(dir); err != nil {
				break
			}
		}
		if err != nil {
			rs = append(rs, Result{id, id, "failed", err.Error()})
			continue
		}
		rs = append(rs, Result{id, id, "configured", "skills refreshed"})
	}
	return rs, nil
}

// Status is one row of `agents list`.
type Status struct {
	ID        string `json:"id"`
	Name      string `json:"name"`
	Detected  bool   `json:"detected"`
	Installed bool   `json:"installed"`
}

// List says which agents are on this machine and which Yukta already configured.
func (in *Installer) List() ([]Status, error) {
	m, err := in.load()
	if err != nil {
		return nil, err
	}
	var out []Status
	for _, a := range registry() {
		_, ok := m[a.ID]
		out = append(out, Status{a.ID, a.Name, a.detect(in.Env), ok})
	}
	return out, nil
}
