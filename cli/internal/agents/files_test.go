// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package agents

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestWriteAtomic(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "sub", "f.json")
	if err := writeAtomic(p, []byte("a"), true); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(p + backupSuffix); err == nil {
		t.Error("a new file has nothing to back up")
	}
	if err := writeAtomic(p, []byte("b"), true); err != nil {
		t.Fatal(err)
	}
	if err := writeAtomic(p, []byte("c"), true); err != nil {
		t.Fatal(err)
	}
	if got, _ := os.ReadFile(p + backupSuffix); string(got) != "a" {
		t.Errorf("backup = %q, want the first original", got)
	}
	if entries, _ := os.ReadDir(filepath.Dir(p)); len(entries) != 2 {
		t.Errorf("temp files left behind: %v", entries)
	}
	// The parent is a file.
	if err := writeAtomic(filepath.Join(p, "x"), []byte("x"), true); err == nil {
		t.Error("parent is a file")
	}
	// The target is a folder: with a backup the read fails, without one the rename does.
	d := filepath.Join(dir, "d")
	if err := os.Mkdir(d, 0o750); err != nil {
		t.Fatal(err)
	}
	if err := writeAtomic(d, []byte("x"), true); err == nil {
		t.Error("backup of a folder")
	}
	if err := writeAtomic(d, []byte("x"), false); err == nil {
		t.Error("rename over a folder")
	}
}

func TestReadJSON(t *testing.T) {
	dir := t.TempDir()
	if doc, exists, err := readJSON(filepath.Join(dir, "none.json")); err != nil || exists || len(doc) != 0 {
		t.Errorf("missing: %v %v %v", doc, exists, err)
	}
	if _, _, err := readJSON(dir); err == nil {
		t.Error("a folder")
	}
	p := filepath.Join(dir, "a.json")
	if err := os.WriteFile(p, []byte("  \n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if doc, exists, err := readJSON(p); err != nil || !exists || len(doc) != 0 {
		t.Errorf("blank: %v %v %v", doc, exists, err)
	}
	if err := os.WriteFile(p, []byte("{/*c*/}"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, _, err := readJSON(p); !errors.Is(err, ErrNotJSON) {
		t.Errorf("comments: %v", err)
	}
}

func TestSetAndRemoveJSON(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "m.json")
	entry := map[string]any{"url": "u"}
	if changed, err := setJSON(p, "s", "yukta", entry, false); err != nil || !changed {
		t.Fatalf("preview: %v %v", changed, err)
	}
	if _, err := os.Stat(p); err == nil {
		t.Error("a preview wrote the file")
	}
	if changed, err := setJSON(p, "s", "yukta", entry, true); err != nil || !changed {
		t.Fatalf("write: %v %v", changed, err)
	}
	if changed, err := setJSON(p, "s", "yukta", entry, true); err != nil || changed {
		t.Fatalf("same again: %v %v", changed, err)
	}
	if changed, _ := setJSON(p, "s", "yukta", map[string]any{"url": "v"}, true); !changed {
		t.Error("a different entry must be written")
	}
	if _, err := setJSON(dir, "s", "yukta", entry, true); err == nil {
		t.Error("a folder")
	}
	if err := removeJSON(filepath.Join(dir, "none.json"), "s", "yukta"); err != nil {
		t.Error("missing file is fine:", err)
	}
	if err := removeJSON(p, "other", "yukta"); err != nil {
		t.Error("missing root is fine:", err)
	}
	if err := removeJSON(p, "s", "absent"); err != nil {
		t.Error("missing key is fine:", err)
	}
	if err := removeJSON(p, "s", "yukta"); err != nil {
		t.Fatal(err)
	}
	if strings.Contains(readFile(t, p), "yukta") {
		t.Error("entry still there")
	}
	if err := os.WriteFile(p, []byte(`{"s":1}`), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := removeJSON(p, "s", "yukta"); err == nil {
		t.Error("root that is not an object")
	}
	if err := removeJSON(dir, "s", "yukta"); err == nil {
		t.Error("a folder")
	}
}

func readFile(t *testing.T, p string) string {
	t.Helper()
	b, err := os.ReadFile(p)
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}

func TestSame(t *testing.T) {
	if same(nil, map[string]any{}) {
		t.Error("nothing is not the same")
	}
	if !same(map[string]any{"a": []any{"x"}}, map[string]any{"a": []string{"x"}}) {
		t.Error("equal after a JSON round trip")
	}
	if same(map[string]any{"a": 1}, map[string]any{"a": 2}) {
		t.Error("different")
	}
}

func TestTOML(t *testing.T) {
	if got := tomlBlock("y", "http://u", "", ""); got != "[mcp_servers.y]\nurl = \"http://u\"\n" {
		t.Errorf("plain: %q", got)
	}
	if got := tomlBlock("y", "http://u", "k", ""); !strings.Contains(got, `http_headers = { Authorization = "Bearer k" }`) {
		t.Errorf("key: %q", got)
	}
	if got := tomlBlock("y", "http://u", "k", "V"); !strings.Contains(got, `bearer_token_env_var = "V"`) || strings.Contains(got, "Bearer") {
		t.Errorf("env: %q", got)
	}
	text := "a = 1\n[mcp_servers.y]\nurl = \"x\"\n\n[other]\nb = 2\n"
	got, found := dropTOMLTable(text, "[mcp_servers.y]")
	if !found || got != "a = 1\n[other]\nb = 2\n" {
		t.Errorf("drop: %q %v", got, found)
	}
	if _, found := dropTOMLTable(text, "[nope]"); found {
		t.Error("found a table that is not there")
	}

	dir := t.TempDir()
	p := filepath.Join(dir, "c.toml")
	block := tomlBlock("y", "http://u", "", "")
	if changed, err := setTOML(p, "y", block, false); err != nil || !changed {
		t.Fatalf("preview: %v %v", changed, err)
	}
	if changed, err := setTOML(p, "y", block, true); err != nil || !changed {
		t.Fatalf("write: %v %v", changed, err)
	}
	if changed, err := setTOML(p, "y", block, true); err != nil || changed {
		t.Fatalf("same again: %v %v", changed, err)
	}
	if _, err := setTOML(dir, "y", block, true); err == nil {
		t.Error("a folder")
	}
	if err := removeTOML(filepath.Join(dir, "none.toml"), "y"); err != nil {
		t.Error("missing file is fine:", err)
	}
	if err := removeTOML(p, "absent"); err != nil {
		t.Error("missing table is fine:", err)
	}
	if err := removeTOML(p, "y"); err != nil || strings.Contains(readFile(t, p), "mcp_servers") {
		t.Errorf("remove: %v %q", err, readFile(t, p))
	}
	if err := removeTOML(dir, "y"); err == nil {
		t.Error("a folder")
	}
}
