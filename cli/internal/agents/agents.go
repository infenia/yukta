// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

// Package agents connects Yukta to the AI agents installed on a machine: it registers the Yukta MCP
// server in each agent's own configuration and copies the Yukta skills where the agent reads them.
// Every change is recorded in a manifest, so uninstall removes exactly what install added.
package agents

import (
	"errors"
	"fmt"
	"path/filepath"
	"sort"
	"strings"
)

// DefaultMCPURL is the MCP endpoint of a local server.
const DefaultMCPURL = "http://localhost:9858/mcp"

const serverName = "yukta"

// Options say what to install and how the agents reach the server.
type Options struct {
	URL      string // the MCP endpoint, <server>/mcp
	Token    string // API key, empty for a server without keys
	TokenEnv string // name of an environment variable the agent reads the key from, instead of a written key
	NoMCP    bool   // skills only
	NoSkills bool   // MCP only
	DryRun   bool   // say what would change, change nothing
}

// headers is the Authorization header for the client's entry. envRef is how that client spells a
// reference to an environment variable ("${env:%s}"); "" means it has none.
func (o Options) headers(envRef string) (map[string]any, error) {
	switch {
	case o.TokenEnv != "" && envRef != "":
		return map[string]any{"Authorization": "Bearer " + fmt.Sprintf(envRef, o.TokenEnv)}, nil
	case o.Token != "":
		return map[string]any{"Authorization": "Bearer " + o.Token}, nil
	case o.TokenEnv != "":
		return nil, errors.New("this agent cannot read a key from an environment variable: pass --token or --token-file")
	}
	return nil, nil
}

// jsonSpec describes an agent whose MCP servers live in a JSON file.
type jsonSpec struct {
	root   string                                                  // the key holding the servers
	envRef string                                                  // see Options.headers
	entry  func(url string, headers map[string]any) map[string]any // the server's entry
}

func entryWith(base map[string]any, headers map[string]any) map[string]any {
	if headers != nil {
		base["headers"] = headers
	}
	return base
}

var (
	cursorSpec = jsonSpec{"mcpServers", "${env:%s}", func(u string, h map[string]any) map[string]any {
		return entryWith(map[string]any{"url": u}, h)
	}}
	windsurfSpec = jsonSpec{"mcpServers", "${env:%s}", func(u string, h map[string]any) map[string]any {
		return entryWith(map[string]any{"serverUrl": u}, h)
	}}
	// Antigravity documents no environment references, so a key is written to the (private) file.
	antigravitySpec = jsonSpec{"mcpServers", "", func(u string, h map[string]any) map[string]any {
		return entryWith(map[string]any{"serverUrl": u}, h)
	}}
	vscodeSpec = jsonSpec{"servers", "${env:%s}", func(u string, h map[string]any) map[string]any {
		return entryWith(map[string]any{"type": "http", "url": u}, h)
	}}
	opencodeSpec = jsonSpec{"mcp", "{env:%s}", func(u string, h map[string]any) map[string]any {
		return entryWith(map[string]any{"type": "remote", "url": u, "enabled": true}, h)
	}}
)

// Change is what install did for one agent, kept in the manifest for uninstall.
type Change struct {
	File   string     `json:"file,omitempty"`   // config file holding our entry
	Format string     `json:"format,omitempty"` // "json" or "toml"
	Root   string     `json:"root,omitempty"`   // JSON key holding the servers
	Undo   [][]string `json:"undo,omitempty"`   // commands that remove what an agent CLI added
	Plugin bool       `json:"plugin,omitempty"` // Claude Code got the plugin, which brings the skills
	Skills []string   `json:"skills,omitempty"` // folders the skills were copied into
	Note   string     `json:"-"`
}

// Agent is one supported AI agent.
type Agent struct {
	ID, Name string
	detect   func(Env) bool
	skills   func(Env) []string
	mcp      func(*Installer) (Change, error) // nil: the agent has no MCP step
}

func jsonAgent(id, name string, detect func(Env) bool, skills func(Env) []string, file func(Env) string, spec jsonSpec) Agent {
	return Agent{ID: id, Name: name, detect: detect, skills: skills, mcp: func(in *Installer) (Change, error) {
		return in.writeJSONEntry(file(in.Env), spec)
	}}
}

func under(parts ...string) func(Env) string {
	return func(e Env) string { return filepath.Join(append([]string{e.Home}, parts...)...) }
}

func skillsIn(parts ...string) func(Env) []string {
	return func(e Env) []string { return []string{filepath.Join(append([]string{e.Home}, parts...)...)} }
}

// registry is the one place that knows where each agent keeps things. Add an agent here.
func registry() []Agent {
	return []Agent{
		{ID: "claude", Name: "Claude Code", detect: func(e Env) bool { return e.has("claude") },
			skills: skillsIn(".claude", "skills"), mcp: (*Installer).claude},
		{ID: "codex", Name: "Codex", detect: func(e Env) bool { return e.has("codex") || e.isDir(e.Home, ".codex") },
			skills: skillsIn(".codex", "skills"), mcp: (*Installer).codex},
		jsonAgent("antigravity", "Antigravity",
			func(e Env) bool { return e.has("agy", "antigravity") || e.isDir(e.Home, ".gemini", "antigravity") },
			func(e Env) []string {
				return []string{filepath.Join(e.Home, ".gemini", "antigravity", "skills"), filepath.Join(e.Home, ".gemini", "skills")}
			},
			under(".gemini", "config", "mcp_config.json"), antigravitySpec),
		jsonAgent("cursor", "Cursor",
			func(e Env) bool { return e.has("cursor") || e.isDir(e.Home, ".cursor") },
			skillsIn(".cursor", "skills"), under(".cursor", "mcp.json"), cursorSpec),
		jsonAgent("vscode", "VS Code Copilot",
			func(e Env) bool { return e.has("code") || e.isDir(e.Config, "Code", "User") },
			skillsIn(".copilot", "skills"),
			func(e Env) string { return filepath.Join(e.Config, "Code", "User", "mcp.json") }, vscodeSpec),
		jsonAgent("windsurf", "Windsurf",
			func(e Env) bool { return e.has("windsurf") || e.isDir(e.Home, ".codeium", "windsurf") },
			skillsIn(".codeium", "windsurf", "skills"), under(".codeium", "windsurf", "mcp_config.json"), windsurfSpec),
		jsonAgent("opencode", "OpenCode",
			func(e Env) bool { return e.has("opencode") || e.isDir(e.Home, ".config", "opencode") },
			skillsIn(".config", "opencode", "skills"), opencodeFile, opencodeSpec),
		// Any agent that follows the open ~/.agents/skills convention. Skills only; never auto-selected.
		{ID: "generic", Name: "Other agents (~/.agents/skills)", detect: func(Env) bool { return false },
			skills: skillsIn(".agents", "skills")},
	}
}

// Select resolves --agent values: none or "auto" is every detected agent, "all" every agent.
func Select(e Env, ids []string) ([]Agent, error) {
	all := registry()
	if len(ids) == 0 || (len(ids) == 1 && ids[0] == "auto") {
		var out []Agent
		for _, a := range all {
			if a.detect(e) {
				out = append(out, a)
			}
		}
		return out, nil
	}
	if len(ids) == 1 && ids[0] == "all" {
		return all, nil
	}
	var out []Agent
	for _, id := range ids {
		found := false
		for _, a := range all {
			if a.ID == strings.TrimSpace(id) {
				out, found = append(out, a), true
			}
		}
		if !found {
			return nil, fmt.Errorf("unknown agent %q (known: %s)", id, strings.Join(IDs(), ", "))
		}
	}
	return out, nil
}

// IDs lists the agent ids.
func IDs() []string {
	var ids []string
	for _, a := range registry() {
		ids = append(ids, a.ID)
	}
	sort.Strings(ids)
	return ids
}
