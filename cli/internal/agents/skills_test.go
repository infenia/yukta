// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package agents

import (
	"io/fs"
	"os"
	"path/filepath"
	"testing"
)

// treeOf maps every file under root (slash paths) to its content, skipping what the copies leave out.
func treeOf(t *testing.T, root string, names []string) map[string]string {
	t.Helper()
	out := map[string]string{}
	for _, name := range names {
		err := filepath.WalkDir(filepath.Join(root, name), func(p string, d fs.DirEntry, err error) error {
			if err != nil || d.IsDir() || filepath.Ext(p) == ".py" {
				return err
			}
			rel, _ := filepath.Rel(root, p)
			b, err := os.ReadFile(p)
			out[filepath.ToSlash(rel)] = string(b)
			return err
		})
		if err != nil {
			t.Fatal(err)
		}
	}
	return out
}

// The embedded copy and the plugin's copy must equal ../skills: run `make sync-skills` in cli/ to fix.
func TestSkillCopiesMatchSource(t *testing.T) {
	names := skillNames()
	if len(names) != 2 {
		t.Fatalf("skills = %v", names)
	}
	source := treeOf(t, filepath.Join("..", "..", "..", "skills"), names)
	for label, root := range map[string]string{
		"cli/internal/agents/skills": "skills",
		"claude-mods/yukta/skills":   filepath.Join("..", "..", "..", "claude-mods", "yukta", "skills"),
	} {
		got := treeOf(t, root, names)
		if len(got) != len(source) {
			t.Errorf("%s has %d files, skills/ has %d: run `make sync-skills` in cli/", label, len(got), len(source))
		}
		for p, want := range source {
			if got[p] != want {
				t.Errorf("%s/%s differs from skills/%s: run `make sync-skills` in cli/", label, p, p)
			}
		}
	}
}

func TestCopyAndRemoveSkills(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "skills")
	stale := filepath.Join(dir, "yukta-workflows", "stale.md")
	other := filepath.Join(dir, "mine", "SKILL.md")
	for _, p := range []string{stale, other} {
		if err := os.MkdirAll(filepath.Dir(p), 0o750); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(p, []byte("x"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	if err := copySkills(dir); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(stale); err == nil {
		t.Error("a copy must replace the folder, not merge into it")
	}
	if _, err := os.Stat(filepath.Join(dir, "yukta-workflows", "references", "cli.md")); err != nil {
		t.Error("references missing:", err)
	}
	if err := removeSkills(dir); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(dir, "yukta-workflows")); err == nil {
		t.Error("skill left behind")
	}
	if _, err := os.Stat(other); err != nil {
		t.Error("another skill must never be touched")
	}
	file := filepath.Join(t.TempDir(), "f")
	if err := os.WriteFile(file, []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := copySkills(file); err == nil {
		t.Error("a file where the folder should be")
	}
}
