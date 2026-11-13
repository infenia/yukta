// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package agents

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"reflect"
	"strconv"
	"strings"
)

const backupSuffix = ".yukta-bak"

// ErrNotJSON means a config file is not plain JSON (it has comments, say). It is never rewritten.
var ErrNotJSON = errors.New("not plain JSON")

// writeAtomic replaces path with data through a temp file in the same folder, so a crash never leaves a
// half-written config. With backup, the first original is kept once as path+".yukta-bak". A new file is
// private (0600): it may hold a key; an existing file keeps its mode.
func writeAtomic(path string, data []byte, backup bool) error {
	mode := os.FileMode(0o600)
	if info, err := os.Stat(path); err == nil {
		mode = info.Mode().Perm()
		if backup {
			if err := keepOriginal(path, mode); err != nil {
				return err
			}
		}
	}
	dir := filepath.Dir(path)
	err := os.MkdirAll(dir, 0o750)
	var tmp *os.File
	if err == nil {
		tmp, err = os.CreateTemp(dir, ".yukta-*")
	}
	if err != nil {
		return err
	}
	defer func() { _ = os.Remove(tmp.Name()) }() // gone after a successful rename
	_, err = tmp.Write(data)
	if cerr := tmp.Close(); err == nil {
		err = cerr
	}
	if err == nil {
		err = os.Chmod(tmp.Name(), mode)
	}
	if err == nil {
		err = os.Rename(tmp.Name(), path)
	}
	return err
}

// keepOriginal copies path to path+".yukta-bak" unless that copy exists already.
func keepOriginal(path string, mode os.FileMode) error {
	if _, err := os.Stat(path + backupSuffix); err == nil {
		return nil
	}
	old, err := os.ReadFile(path) // #nosec G304 -- the agent's own config file
	if err == nil {
		err = writeAtomic(path+backupSuffix, old, false)
	}
	if err == nil {
		err = os.Chmod(path+backupSuffix, mode)
	}
	return err
}

// readJSON reads a JSON object; a missing file is an empty one.
func readJSON(path string) (map[string]any, bool, error) {
	raw, err := os.ReadFile(path) // #nosec G304 -- the agent's own config file
	if errors.Is(err, fs.ErrNotExist) {
		return map[string]any{}, false, nil
	}
	if err != nil {
		return nil, false, err
	}
	if len(bytes.TrimSpace(raw)) == 0 {
		return map[string]any{}, true, nil
	}
	doc := map[string]any{}
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.UseNumber() // keep 1.0 as 1.0, not 1
	if err := dec.Decode(&doc); err != nil {
		return nil, true, fmt.Errorf("%s: %w: %v", path, ErrNotJSON, err)
	}
	return doc, true, nil
}

// writeJSON writes doc indented. ponytail: Go sorts object keys, so a hand-ordered file comes back
// alphabetical (the .yukta-bak keeps the original); an order-preserving editor if anyone minds.
func writeJSON(path string, doc map[string]any) error {
	data, _ := json.MarshalIndent(doc, "", "  ") // maps of strings, bools and json.Number cannot fail
	return writeAtomic(path, append(data, '\n'), true)
}

// servers returns doc[root] as an object, creating it when create is set.
func servers(doc map[string]any, root string, create bool) (map[string]any, error) {
	switch v := doc[root].(type) {
	case nil:
		if !create {
			return nil, nil
		}
		fresh := map[string]any{}
		doc[root] = fresh
		return fresh, nil
	case map[string]any:
		return v, nil
	default:
		return nil, fmt.Errorf("%q is not an object", root)
	}
}

// setJSON puts entry at doc[root][name]; it reports whether the file changed.
func setJSON(path, root, name string, entry map[string]any, write bool) (bool, error) {
	doc, _, err := readJSON(path)
	if err != nil {
		return false, err
	}
	s, err := servers(doc, root, true)
	if err != nil {
		return false, fmt.Errorf("%s: %w", path, err)
	}
	// Compare through JSON so numbers and nested types match what a re-read file holds.
	if same(s[name], entry) {
		return false, nil
	}
	s[name] = entry
	if !write {
		return true, nil
	}
	return true, writeJSON(path, doc)
}

func same(a, b any) bool {
	if a == nil {
		return false
	}
	ja, _ := json.Marshal(a)
	jb, _ := json.Marshal(b)
	var x, y any
	_ = json.Unmarshal(ja, &x)
	_ = json.Unmarshal(jb, &y)
	return reflect.DeepEqual(x, y)
}

// removeJSON deletes doc[root][name]; a missing file or key is fine.
func removeJSON(path, root, name string) error {
	doc, exists, err := readJSON(path)
	if err != nil || !exists {
		return err
	}
	s, err := servers(doc, root, false)
	if err != nil {
		return fmt.Errorf("%s: %w", path, err)
	}
	if _, ok := s[name]; !ok {
		return nil
	}
	delete(s, name)
	return writeJSON(path, doc)
}

// tomlBlock is the Codex [mcp_servers.<name>] table. Strings go through strconv.Quote, which is a valid
// TOML basic string for the printable ASCII of URLs and tokens.
func tomlBlock(name, url, bearer, envVar string) string {
	var b strings.Builder
	fmt.Fprintf(&b, "[mcp_servers.%s]\nurl = %s\n", name, strconv.Quote(url))
	switch {
	case envVar != "":
		fmt.Fprintf(&b, "bearer_token_env_var = %s\n", strconv.Quote(envVar))
	case bearer != "":
		fmt.Fprintf(&b, "http_headers = { Authorization = %s }\n", strconv.Quote("Bearer "+bearer))
	}
	return b.String()
}

// dropTOMLTable removes the [header] table (its lines up to the next table) from text.
func dropTOMLTable(text, header string) (string, bool) {
	lines := strings.SplitAfter(text, "\n")
	var out []string
	skipping, found := false, false
	for _, l := range lines {
		t := strings.TrimSpace(l)
		if strings.HasPrefix(t, "[") {
			skipping = t == header
			found = found || skipping
		}
		if !skipping {
			out = append(out, l)
		}
	}
	return strings.Join(out, ""), found
}

// setTOML writes the table, replacing an earlier one of ours.
func setTOML(path, name, block string, write bool) (bool, error) {
	raw, err := os.ReadFile(path) // #nosec G304 -- the agent's own config file
	if err != nil && !errors.Is(err, fs.ErrNotExist) {
		return false, err
	}
	text, _ := dropTOMLTable(string(raw), "[mcp_servers."+name+"]")
	text = strings.TrimRight(text, "\n")
	if text != "" {
		text += "\n\n"
	}
	text += block
	if text == string(raw) {
		return false, nil
	}
	if !write {
		return true, nil
	}
	return true, writeAtomic(path, []byte(text), true)
}

// removeTOML drops our table; a missing file or table is fine.
func removeTOML(path, name string) error {
	raw, err := os.ReadFile(path) // #nosec G304 -- the agent's own config file
	if errors.Is(err, fs.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	text, found := dropTOMLTable(string(raw), "[mcp_servers."+name+"]")
	if !found {
		return nil
	}
	return writeAtomic(path, []byte(strings.TrimRight(text, "\n")+"\n"), true)
}
