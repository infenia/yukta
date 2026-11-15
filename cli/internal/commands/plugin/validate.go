// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package plugin

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"

	"com.infenia.yukta/go-cli/internal/pluginspec"
	"github.com/spf13/cobra"
)

// ValidateCmd creates the "plugin validate" subcommand: the conformance check of a community
// plugin. It needs no server.
func ValidateCmd() *cobra.Command {
	var config, payload string
	cmd := &cobra.Command{
		Use:   "validate <dir>",
		Short: "Check a community plugin directory",
		Long: "Check a community plugin: its yukta-plugin.json against the manifest schema, its directory\n" +
			"digest (to pin it with TYPE@sha256:<digest> in yukta.plugins.enabled), and one run of its\n" +
			"command with a sample message, as the server would run it. No server is needed.",
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return validate(cmd, args[0], config, payload)
		},
	}
	cmd.Flags().StringVar(&config, "config", "{}", "node configuration for the sample run (JSON object)")
	cmd.Flags().StringVar(&payload, "payload", "{}", "payload of the sample message (JSON)")
	return cmd
}

func validate(cmd *cobra.Command, dir, config, payload string) error {
	data, err := os.ReadFile(filepath.Join(dir, pluginspec.Manifest)) // #nosec G304 -- the user names the directory
	if err != nil {
		return err
	}
	manifest, err := pluginspec.CheckManifest(data)
	if err != nil {
		return err
	}
	digest, err := pluginspec.Digest(dir)
	if err != nil {
		return err
	}
	var cfg map[string]any
	if err := json.Unmarshal([]byte(config), &cfg); err != nil {
		return fmt.Errorf("--config: %w", err)
	}
	var body any
	if err := json.Unmarshal([]byte(payload), &body); err != nil {
		return fmt.Errorf("--payload: %w", err)
	}
	out := cmd.OutOrStdout()
	_, _ = fmt.Fprintf(out, "manifest  ok (%s, %s)\n", manifest["type"], manifest["category"])
	if run, _ := manifest["run"].(map[string]any); run["mcp"] != nil {
		// ponytail: an MCP tool is not run here; its pin covers the tool's schema, which the server
		// reads at startup. Add an MCP client here if authors need the check without a server.
		_, _ = fmt.Fprintln(out, "enable    the server logs the digest to pin at startup (it covers the tool's input schema)")
		return nil
	}
	_, _ = fmt.Fprintf(out, "enable    %s@%s\n", manifest["type"], digest)
	res, err := pluginspec.Run(dir, manifest, cfg, body)
	if res.Stderr != "" {
		_, _ = fmt.Fprintf(out, "stderr    %s", res.Stderr)
	}
	if err != nil {
		return err
	}
	_, _ = fmt.Fprintf(out, "run       ok, %d output(s)\n", len(res.Outputs))
	return nil
}
