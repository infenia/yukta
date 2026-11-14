// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package pluginspec

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"
)

const manifestFmt = `{"apiVersion":"yukta/plugin/v1","type":"ECHO","category":"%s","description":"d",` +
	`"ports":["default","other"],"timeout":"%s","run":{"command":["sh","run.sh"]}}`

// plugin writes a plugin directory with a run.sh script and returns its dir and manifest.
func plugin(t *testing.T, category, timeout, script string) (string, map[string]any) {
	t.Helper()
	dir := t.TempDir()
	data := []byte(strings.Replace(strings.Replace(manifestFmt, "%s", category, 1), "%s", timeout, 1))
	if category == "terminal" {
		data = bytes.Replace(data, []byte(`"ports":["default","other"],`), nil, 1)
	}
	must(t, os.WriteFile(filepath.Join(dir, Manifest), data, 0o600))
	must(t, os.WriteFile(filepath.Join(dir, "run.sh"), []byte(script), 0o600))
	m, err := CheckManifest(data)
	must(t, err)
	return dir, m
}

func must(t *testing.T, err error) {
	t.Helper()
	if err != nil {
		t.Fatal(err)
	}
}

func skipOnWindows(t *testing.T) {
	t.Helper()
	if runtime.GOOS == "windows" {
		t.Skip("plugins run with sh")
	}
}

func TestSchemas_matchPluginApi(t *testing.T) {
	for _, name := range []string{"manifest", "message-out"} {
		embedded, err := schemas.ReadFile("schema/" + name + ".schema.json")
		must(t, err)
		source, err := os.ReadFile("../../../plugin-api/src/main/resources/schema/plugin/v1/" + name + ".schema.json")
		must(t, err)
		if !bytes.Equal(embedded, source) {
			t.Errorf("%s.schema.json differs from plugin-api's: run make sync-schemas", name)
		}
	}
}

func TestCompile_unknownSchemaFails(t *testing.T) {
	if _, err := compile("nope"); err == nil {
		t.Fatal("expected an error")
	}
	defer func() {
		if recover() == nil {
			t.Fatal("expected a panic")
		}
	}()
	mustCompile("nope")
}

func TestCompileJSON_errors(t *testing.T) {
	cases := map[string][]string{
		"not JSON":       {"x.json", "{"},
		"meta-schema id": {"https://json-schema.org/draft/2020-12/schema", "{}"},
		"bad schema":     {"x.json", `{"type":5}`},
	}
	for name, c := range cases {
		if _, err := compileJSON(c[0], []byte(c[1])); err == nil {
			t.Errorf("%s: expected an error", name)
		}
	}
}

func TestCheckManifest(t *testing.T) {
	if _, err := CheckManifest([]byte("{")); err == nil || !strings.Contains(err.Error(), "not JSON") {
		t.Errorf("got %v", err)
	}
	if _, err := CheckManifest([]byte(`{"apiVersion":"yukta/plugin/v2"}`)); err == nil ||
		!strings.Contains(err.Error(), "is invalid") {
		t.Errorf("got %v", err)
	}
}

func TestCheckOutput(t *testing.T) {
	if _, err := CheckOutput([]byte("x")); err == nil || !strings.Contains(err.Error(), "not JSON") {
		t.Errorf("got %v", err)
	}
	if _, err := CheckOutput([]byte(`{"payload":1,"traceId":"x"}`)); err == nil ||
		!strings.Contains(err.Error(), "invalid output") {
		t.Errorf("got %v", err)
	}
}

func TestDigest_isTheHashOfTheSortedFileLines(t *testing.T) {
	dir := t.TempDir()
	must(t, os.WriteFile(filepath.Join(dir, "b.txt"), []byte("two"), 0o600))
	must(t, os.MkdirAll(filepath.Join(dir, "a"), 0o700))
	must(t, os.WriteFile(filepath.Join(dir, "a", "x.txt"), []byte("one"), 0o600))

	got, err := Digest(dir)
	must(t, err)
	if want := "sha256:" + sha("a/x.txt\x00"+sha("one")+"\nb.txt\x00"+sha("two")+"\n"); got != want {
		t.Errorf("got %s, want %s", got, want)
	}
}

func TestDigest_errors(t *testing.T) {
	if _, err := Digest(filepath.Join(t.TempDir(), "missing")); err == nil {
		t.Error("expected an error for a missing directory")
	}
	dir := t.TempDir()
	must(t, os.Symlink("/etc", filepath.Join(dir, "link")))
	if _, err := Digest(dir); err == nil || !strings.Contains(err.Error(), "symbolic link") {
		t.Errorf("got %v", err)
	}
	if runtime.GOOS == "windows" || os.Geteuid() == 0 {
		t.Skip("file permissions do not stop this user")
	}
	dir = t.TempDir()
	must(t, os.WriteFile(filepath.Join(dir, "secret"), []byte("x"), 0o000))
	if _, err := Digest(dir); err == nil {
		t.Error("expected an error for an unreadable file")
	}
}

func sha(text string) string {
	sum := sha256.Sum256([]byte(text))
	return hex.EncodeToString(sum[:])
}

func TestRun_processorOutputs(t *testing.T) {
	skipOnWindows(t)
	dir, m := plugin(t, "processor", "5s",
		`cat > /dev/null; echo hi >&2; echo '{"payload":1,"port":"other"}'; echo; echo '{"payload":2}'`)

	res, err := Run(dir, m, map[string]any{}, nil)
	must(t, err)
	if len(res.Outputs) != 2 || res.Stderr != "hi\n" {
		t.Errorf("got %+v", res)
	}
}

func TestRun_seesTheEnvelopeAndPathOnly(t *testing.T) {
	skipOnWindows(t)
	t.Setenv("YUKTA_SECRET_X", "leak")
	dir, m := plugin(t, "processor", "5s",
		`env | grep -c YUKTA_SECRET >&2; grep -q yukta/message/v1 && echo '{"payload":1}'`)

	res, err := Run(dir, m, map[string]any{}, nil)
	must(t, err)
	if res.Stderr != "0\n" || len(res.Outputs) != 1 {
		t.Errorf("got %+v", res)
	}
}

func TestRun_failures(t *testing.T) {
	skipOnWindows(t)
	cases := map[string]string{
		"exit 3":                                "the plugin failed",
		"echo nope":                             "stdout line 1: not JSON",
		`echo '{"payload":1,"port":"nowhere"}'`: `port "nowhere"`,
	}
	for script, want := range cases {
		dir, m := plugin(t, "processor", "5s", script)
		if _, err := Run(dir, m, map[string]any{}, nil); err == nil || !strings.Contains(err.Error(), want) {
			t.Errorf("%s: got %v, want %q", script, err, want)
		}
	}
}

func TestRun_terminalIgnoresStdout(t *testing.T) {
	skipOnWindows(t)
	dir, m := plugin(t, "terminal", "500ms", "echo anything")

	res, err := Run(dir, m, map[string]any{}, nil)
	must(t, err)
	if len(res.Outputs) != 0 {
		t.Errorf("got %+v", res)
	}
}

func TestRun_localExecutableAndOtherForms(t *testing.T) {
	skipOnWindows(t)
	dir, m := plugin(t, "processor", "5s", "")
	m["run"] = map[string]any{"command": []any{"./missing"}}
	if _, err := Run(dir, m, nil, nil); err == nil || !strings.Contains(err.Error(), filepath.Join(dir, "missing")) {
		t.Errorf("got %v", err)
	}
	m["run"] = map[string]any{"mcp": map[string]any{"url": "http://x", "tool": "t"}}
	if _, err := Run(dir, m, nil, nil); err == nil || !strings.Contains(err.Error(), "uses run.mcp") {
		t.Errorf("got %v", err)
	}
}

// fakeRuntime is a container CLI that logs its arguments to calls; run echoes stdin as the payload,
// or sleeps for the image sleepy.
func fakeRuntime(t *testing.T) (calls string) {
	t.Helper()
	bin := t.TempDir()
	calls = filepath.Join(bin, "calls")
	cli := filepath.Join(bin, "fake-docker")
	must(t, os.WriteFile(cli, []byte("#!/bin/sh\necho \"$*\" >> '"+calls+"'\n"+
		"[ \"$1\" = rm ] && exit 0\n"+
		"for a; do img=$a; done\n"+
		"[ \"$img\" = sleepy ] && exec sleep 5\n"+
		"echo \"home=$HOME\" >&2; printf '{\"payload\":'; cat; printf '}\\n'\n"), 0o700))
	t.Setenv("YUKTA_PLUGINS_CONTAINER_RUNTIME", cli)
	t.Setenv("HOME", "/home/test")
	return calls
}

func TestRun_image(t *testing.T) {
	skipOnWindows(t)
	calls := fakeRuntime(t)
	dir, m := plugin(t, "processor", "5s", "")
	m["run"] = map[string]any{"image": "ghcr.io/acme/x:1"}

	res, err := Run(dir, m, map[string]any{}, nil)
	must(t, err)
	got, _ := os.ReadFile(calls)
	if len(res.Outputs) != 1 || res.Stderr != "home=/home/test\n" ||
		!strings.HasPrefix(string(got), "run --rm -i --name yukta-validate-") ||
		!strings.HasSuffix(string(got), " ghcr.io/acme/x:1\n") {
		t.Errorf("got %+v, calls %q", res, got)
	}
}

func TestRun_imageTimeoutRemovesTheContainer(t *testing.T) {
	skipOnWindows(t)
	calls := fakeRuntime(t)
	dir, m := plugin(t, "processor", "300ms", "")
	m["run"] = map[string]any{"image": "sleepy"}

	if _, err := Run(dir, m, map[string]any{}, nil); err == nil {
		t.Fatal("expected a timeout")
	}
	got, _ := os.ReadFile(calls)
	if !strings.Contains(string(got), "rm -f yukta-validate-") {
		t.Errorf("calls %q", got)
	}
}

func TestRun_imageDefaultsToDocker(t *testing.T) {
	t.Setenv("YUKTA_PLUGINS_CONTAINER_RUNTIME", "")
	args, _, _, err := command("", map[string]any{"image": "x"})
	if err != nil || args[0] != "docker" {
		t.Errorf("got %v %v", args, err)
	}
}

func TestPortsAndTimeoutDefaults(t *testing.T) {
	if p := ports(map[string]any{}); len(p) != 1 || p[0] != "default" {
		t.Errorf("got %v", p)
	}
	if d := timeout(map[string]any{}); d != 30*time.Second {
		t.Errorf("got %v", d)
	}
	if d := timeout(map[string]any{"timeout": "2m"}); d != 2*time.Minute {
		t.Errorf("got %v", d)
	}
}
