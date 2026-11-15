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

// Result is what one run of a plugin produced.
type Result struct {
	// Outputs are the checked output objects, in order; empty for a terminal.
	Outputs []map[string]any
	// Stderr is what the plugin logged.
	Stderr string
}

// containerEnv are the variables a container CLI reads to reach its daemon, passed to the CLI
// only, as the server does; run passes none into the container.
var containerEnv = []string{"HOME", "DOCKER_HOST", "DOCKER_CONFIG", "DOCKER_CONTEXT", "CONTAINER_HOST", "XDG_RUNTIME_DIR"}

// Run runs a command or image plugin once, as the server would: the message envelope v1 on stdin,
// in the plugin's directory, with PATH only from the environment (an image: plus the container
// CLI's own variables), within the manifest's timeout. It fails on a non-zero exit and, for a
// processor, on any stdout line the server would refuse. An image runs with the CLI named by
// YUKTA_PLUGINS_CONTAINER_RUNTIME (default docker) and is removed when the timeout stops it.
func Run(dir string, manifest, config map[string]any, payload any) (Result, error) {
	run, _ := manifest["run"].(map[string]any)
	args, env, container, err := command(dir, run)
	if err != nil {
		return Result{}, err
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
	cmd.Env = env
	cmd.Stdin = bytes.NewReader(stdin)
	var stdout, stderr bytes.Buffer
	cmd.Stdout, cmd.Stderr = &stdout, &stderr
	err = cmd.Run()
	res := Result{Stderr: stderr.String()}
	if err != nil {
		if container != "" && ctx.Err() != nil {
			_ = exec.Command(args[0], "rm", "-f", container).Run() // #nosec G204 -- the user's CLI
		}
		return res, fmt.Errorf("the plugin failed: %w", err)
	}
	if manifest["category"] == "terminal" {
		return res, nil
	}
	return outputs(res, stdout.String(), ports(manifest))
}

// command is the argv and environment that run the plugin, and the container's name for an image.
func command(dir string, run map[string]any) ([]string, []string, string, error) {
	env := []string{"PATH=" + os.Getenv("PATH")}
	if image, ok := run["image"].(string); ok {
		for _, k := range containerEnv {
			if v, set := os.LookupEnv(k); set {
				env = append(env, k+"="+v)
			}
		}
		cli := os.Getenv("YUKTA_PLUGINS_CONTAINER_RUNTIME")
		if cli == "" {
			cli = "docker"
		}
		name := fmt.Sprintf("yukta-validate-%d", os.Getpid())
		return []string{cli, "run", "--rm", "-i", "--name", name, image}, env, name, nil
	}
	argv, ok := run["command"].([]any)
	if !ok {
		return nil, nil, "", fmt.Errorf("only run.command and run.image can be tried; this plugin uses run.mcp")
	}
	args := make([]string, len(argv))
	for i, a := range argv {
		args[i] = a.(string)
	}
	if strings.Contains(args[0], "/") {
		args[0] = filepath.Join(dir, args[0])
	}
	return args, env, "", nil
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
