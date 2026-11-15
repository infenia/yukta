// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package plugin

import (
	"bytes"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

const echoManifest = `{"apiVersion":"yukta/plugin/v1","type":"ECHO","category":"processor",` +
	`"description":"d","run":{"command":["sh","run.sh"]}}`

func runValidate(t *testing.T, args ...string) (string, error) {
	t.Helper()
	cmd := ValidateCmd()
	var out bytes.Buffer
	cmd.SetOut(&out)
	cmd.SetErr(&out)
	cmd.SetArgs(args)
	err := cmd.Execute()
	return out.String(), err
}

func pluginDir(t *testing.T, manifest, script string) string {
	t.Helper()
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "yukta-plugin.json"), []byte(manifest), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "run.sh"), []byte(script), 0o600); err != nil {
		t.Fatal(err)
	}
	return dir
}

func TestValidate_aGoodPlugin(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("plugins run with sh")
	}
	dir := pluginDir(t, echoManifest, `echo working >&2; echo '{"payload":1}'`)

	out, err := runValidate(t, dir, "--payload", `{"text":"hi"}`)
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"manifest  ok (ECHO, processor)", "enable    ECHO@sha256:", "stderr    working", "run       ok, 1 output(s)"} {
		if !strings.Contains(out, want) {
			t.Errorf("missing %q in:\n%s", want, out)
		}
	}
}

func TestValidate_mcpPluginIsCheckedButNotRun(t *testing.T) {
	dir := pluginDir(t, `{"apiVersion":"yukta/plugin/v1","type":"TOOL","category":"processor","description":"d",`+
		`"run":{"mcp":{"url":"http://localhost:1/mcp","tool":"t"}}}`, "")

	out, err := runValidate(t, dir)
	if err != nil || !strings.Contains(out, "the server logs the digest to pin") {
		t.Errorf("got %v:\n%s", err, out)
	}
}

func TestValidate_failures(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("plugins run with sh")
	}
	good := pluginDir(t, echoManifest, "exit 3")
	linked := pluginDir(t, echoManifest, "")
	if err := os.Symlink("/etc", filepath.Join(linked, "link")); err != nil {
		t.Fatal(err)
	}
	cases := []struct {
		args []string
		want string
	}{
		{[]string{t.TempDir()}, "no such file"},
		{[]string{pluginDir(t, "{}", "")}, "is invalid"},
		{[]string{linked}, "symbolic link"},
		{[]string{good, "--config", "["}, "--config"},
		{[]string{good, "--payload", "{"}, "--payload"},
		{[]string{good}, "the plugin failed"},
	}
	for _, c := range cases {
		if _, err := runValidate(t, c.args...); err == nil || !strings.Contains(err.Error(), c.want) {
			t.Errorf("%v: got %v, want %q", c.args, err, c.want)
		}
	}
}
