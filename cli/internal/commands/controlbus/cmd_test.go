// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package controlbus

import (
	"strings"
	"testing"

	"com.infenia.yukta/go-cli/internal/client"
)

func TestControlBusCmd_createsCommand(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := ControlBusCmd(c)

	if cmd.Use != "controlbus" {
		t.Errorf("expected Use 'controlbus', got %q", cmd.Use)
	}
}

func TestControlBusCmd_registersSubcommands(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := ControlBusCmd(c)

	subcommands := cmd.Commands()
	if len(subcommands) != 4 {
		t.Errorf("expected 4 subcommands, got %d", len(subcommands))
	}

	// Check for subcommands by prefix (Use field includes args like "nodes <workflow-id>")
	expectedSubcommands := []string{"nodes", "all-nodes", "heartbeat", "command"}
	found := make(map[string]bool)

	for _, sub := range subcommands {
		for _, expected := range expectedSubcommands {
			if strings.HasPrefix(sub.Use, expected) {
				found[expected] = true
			}
		}
	}

	for _, expected := range expectedSubcommands {
		if !found[expected] {
			t.Errorf("expected subcommand %q not found", expected)
		}
	}
}
