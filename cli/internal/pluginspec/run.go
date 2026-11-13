// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package pluginspec

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"time"
)

// Result is what one run of a command plugin produced.
type Result struct {
	// Outputs are the checked output objects, in order; empty for a terminal.
	Outputs []map[string]any
	// Stderr is what the plugin logged.
	Stderr string
}

// Run runs a command plugin once, as the server would: the message envelope v1 on stdin, in the
// plugin's directory, with PATH only from the environment, within the manifest's timeout. It
// fails on a non-zero exit and, for a processor, on any stdout line the server would refuse.
func Run(dir string, manifest, config map[string]any, payload any) (Result, error) {
	run, _ := manifest["run"].(map[string]any)
	argv, ok := run["command"].([]any)
	if !ok {
		return Result{}, fmt.Errorf("only run.command can be tried; this plugin uses another run form")
	}
	args := make([]string, len(argv))
	for i, a := range argv {
		args[i] = a.(string)
	}
	if strings.Contains(args[0], "/") {
		args[0] = filepath.Join(dir, args[0])
	}
	stdin, _ := json.Marshal(map[string]any{
		"apiVersion": "yukta/message/v1",
		"config":     config,
		"message": map[string]any{
			"id": "validate", "traceId": "validate", "metadata": map[string]any{}, "payload": payload,
		},
	})
	ctx, cancel := context.WithTimeout(context.Background(), timeout(manifest))
	defer cancel()
	cmd := exec.CommandContext(ctx, args[0], args[1:]...) // #nosec G204 -- the plugin the user checks
	cmd.Dir = dir
	cmd.Env = []string{"PATH=" + os.Getenv("PATH")}
	cmd.Stdin = bytes.NewReader(stdin)
	var stdout, stderr bytes.Buffer
	cmd.Stdout, cmd.Stderr = &stdout, &stderr
	err := cmd.Run()
	res := Result{Stderr: stderr.String()}
	if err != nil {
		return res, fmt.Errorf("the plugin failed: %w", err)
	}
	if manifest["category"] == "terminal" {
		return res, nil
	}
	return outputs(res, stdout.String(), ports(manifest))
}

func outputs(res Result, stdout string, ports []string) (Result, error) {
	for i, line := range strings.Split(stdout, "\n") {
		if strings.TrimSpace(line) == "" {
			continue
		}
		out, err := CheckOutput([]byte(line))
		if err != nil {
			return res, fmt.Errorf("stdout line %d: %w", i+1, err)
		}
		port, ok := out["port"].(string)
		if ok && !slices.Contains(ports, port) {
			return res, fmt.Errorf("stdout line %d: port %q is not one of %v", i+1, port, ports)
		}
		res.Outputs = append(res.Outputs, out)
	}
	return res, nil
}

func ports(manifest map[string]any) []string {
	list, ok := manifest["ports"].([]any)
	if !ok {
		return []string{"default"}
	}
	out := make([]string, len(list))
	for i, p := range list {
		out[i] = p.(string)
	}
	return out
}

// timeout is the manifest's timeout (500ms, 30s, 5m, 1h), or 30s.
func timeout(manifest map[string]any) time.Duration {
	text, ok := manifest["timeout"].(string)
	if !ok {
		return 30 * time.Second
	}
	if n, found := strings.CutSuffix(text, "ms"); found {
		ms, _ := strconv.Atoi(n)
		return time.Duration(ms) * time.Millisecond
	}
	d, _ := time.ParseDuration(text)
	return d
}
