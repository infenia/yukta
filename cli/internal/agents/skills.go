// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package agents

import (
	"embed"
	"errors"
	"io/fs"
	"os"
	"path"
	"path/filepath"
	"strings"
)

// skillsFS holds the skills of ../../../skills. `make sync-skills` refreshes it and skills_test.go fails
// when it drifts.
//
//go:embed skills
var skillsFS embed.FS

// skillNames are the skill folders Yukta owns; nothing else in an agent's skills folder is touched.
func skillNames() []string {
	entries, _ := fs.ReadDir(skillsFS, "skills") // the embedded root always exists
	names := make([]string, 0, len(entries))
	for _, e := range entries {
		names = append(names, e.Name())
	}
	return names
}

// copySkills (re)writes every Yukta skill under dir.
func copySkills(dir string) error {
	for _, name := range skillNames() {
		dest := filepath.Join(dir, name)
		err := os.RemoveAll(dest)
		if err == nil {
			// The embedded tree is always readable, so the walk's own error and ReadFile cannot fail.
			err = fs.WalkDir(skillsFS, path.Join("skills", name), func(p string, d fs.DirEntry, _ error) error {
				if d.IsDir() {
					return nil
				}
				data, _ := skillsFS.ReadFile(p)
				return writeFileAll(filepath.Join(dir, filepath.FromSlash(strings.TrimPrefix(p, "skills/"))), data)
			})
		}
		if err != nil {
			return err
		}
	}
	return nil
}

func writeFileAll(p string, data []byte) error {
	err := os.MkdirAll(filepath.Dir(p), 0o750)
	if err == nil {
		err = os.WriteFile(p, data, 0o600)
	}
	return err
}

// removeSkills deletes the Yukta skills under dir.
func removeSkills(dir string) error {
	var err error
	for _, name := range skillNames() {
		err = errors.Join(err, os.RemoveAll(filepath.Join(dir, name)))
	}
	return err
}
