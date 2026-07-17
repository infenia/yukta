// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package plugin

import (
	"strings"
	"testing"

	"com.infenia.yukta/go-cli/internal/client"
)

func TestPluginCmd_createsCommand(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := PluginCmd(c)

	if cmd.Use != "plugin" {
		t.Errorf("expected Use 'plugin', got %q", cmd.Use)
	}
}

func TestPluginCmd_registersSubcommands(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := PluginCmd(c)

	subcommands := cmd.Commands()
	if len(subcommands) != 2 {
		t.Errorf("expected 2 subcommands, got %d", len(subcommands))
	}

	subcommandUses := make(map[string]bool)
	for _, sub := range subcommands {
		subcommandUses[sub.Use] = true
	}

	// Check for list and get (get has args appended, so it's "get <type>")
	hasListCmd := subcommandUses["list"]
	if !hasListCmd {
		t.Errorf("expected 'list' subcommand not found")
	}

	// GetCmd has Use="get <type>" due to Args specification
	hasGetCmd := false
	for use := range subcommandUses {
		if strings.HasPrefix(use, "get") {
			hasGetCmd = true
			break
		}
	}
	if !hasGetCmd {
		t.Errorf("expected 'get' subcommand not found, got: %v", subcommandUses)
	}
}
