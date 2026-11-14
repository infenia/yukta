// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package pluginspec

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"sort"
)

// Digest is the value an operator pins a plugin directory to (TYPE@sha256:<digest> in
// yukta.plugins.enabled), as the server computes it (PluginDigest): SHA-256 over one line
// "<relative path>\x00<hex SHA-256 of the file>\n" per regular file, sorted by path with /
// separators. A symbolic link anywhere in the directory is an error.
func Digest(dir string) (string, error) {
	var files []string
	err := filepath.WalkDir(dir, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if d.Type()&fs.ModeSymlink != 0 {
			return fmt.Errorf("plugin directory %s holds a symbolic link: %s", dir, path)
		}
		if d.Type().IsRegular() {
			rel, _ := filepath.Rel(dir, path)
			files = append(files, filepath.ToSlash(rel))
		}
		return nil
	})
	if err != nil {
		return "", err
	}
	sort.Strings(files)
	all := sha256.New()
	for _, name := range files {
		data, err := os.ReadFile(filepath.Join(dir, filepath.FromSlash(name))) // #nosec G304 -- a file of the plugin directory
		if err != nil {
			return "", err
		}
		sum := sha256.Sum256(data)
		_, _ = fmt.Fprintf(all, "%s\x00%s\n", name, hex.EncodeToString(sum[:]))
	}
	return "sha256:" + hex.EncodeToString(all.Sum(nil)), nil
}
