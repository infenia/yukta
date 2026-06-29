// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package session

import (
	"testing"

	"com.infenia.yukta/go-cli/internal/client"
)

// TestSessionCmd_createsCommand_withCorrectUse tests that SessionCmd creates command with correct Use.
func TestSessionCmd_createsCommand_withCorrectUse(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := SessionCmd(c)

	if cmd.Use != "session" {
		t.Errorf("expected Use 'session', got %q", cmd.Use)
	}
}

// TestSessionCmd_hasShortDescription tests that command has short description.
func TestSessionCmd_hasShortDescription(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := SessionCmd(c)

	if cmd.Short == "" {
		t.Error("expected non-empty Short description")
	}

	if cmd.Short != "Manage sessions" {
		t.Errorf("expected Short 'Manage sessions', got %q", cmd.Short)
	}
}

// TestSessionCmd_hasLongDescription tests that command has long description.
func TestSessionCmd_hasLongDescription(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := SessionCmd(c)

	if cmd.Long == "" {
		t.Error("expected non-empty Long description")
	}

	if len(cmd.Long) < 20 {
		t.Errorf("expected meaningful Long description, got %q", cmd.Long)
	}
}

// TestSessionCmd_addsListSubcommand tests that list subcommand is registered.
func TestSessionCmd_addsListSubcommand(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := SessionCmd(c)

	found := false
	for _, subcmd := range cmd.Commands() {
		if subcmd.Use == "list" {
			found = true
			break
		}
	}
	if !found {
		t.Error("expected 'list' subcommand to be registered")
	}
}

// TestSessionCmd_addsGetSubcommand tests that get subcommand is registered.
func TestSessionCmd_addsGetSubcommand(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := SessionCmd(c)

	found := false
	for _, subcmd := range cmd.Commands() {
		if subcmd.Use == "get <session-id>" {
			found = true
			break
		}
	}
	if !found {
		t.Error("expected 'get' subcommand to be registered")
	}
}

// TestSessionCmd_addsWorkflowSubcommand tests that workflow subcommand is registered.
func TestSessionCmd_addsWorkflowSubcommand(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := SessionCmd(c)

	found := false
	for _, subcmd := range cmd.Commands() {
		if subcmd.Use == "workflow" {
			found = true
			break
		}
	}
	if !found {
		t.Error("expected 'workflow' subcommand to be registered")
	}
}

// TestSessionCmd_subcommandCount tests that correct number of subcommands are registered.
func TestSessionCmd_subcommandCount(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := SessionCmd(c)

	count := len(cmd.Commands())
	if count < 3 {
		t.Errorf("expected at least 3 subcommands, got %d", count)
	}
}

// TestSessionCmd_noRunFunction tests that session command has no direct Run action.
func TestSessionCmd_noRunFunction(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := SessionCmd(c)

	// Session command is a group, so it shouldn't have a Run function
	if cmd.Run != nil {
		t.Error("expected session command to have no Run function (it's a command group)")
	}
}

// TestSessionCmd_clientNotNil tests that client is used to create subcommands.
func TestSessionCmd_clientNotNil(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := SessionCmd(c)

	// If client was nil, this would panic during command creation
	if cmd == nil {
		t.Error("expected command to be created successfully")
	}
}

// TestSessionCmd_listHasCorrectUse tests that list subcommand has correct Use.
func TestSessionCmd_listHasCorrectUse(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := SessionCmd(c)

	found := false
	for _, subcmd := range cmd.Commands() {
		if subcmd.Use == "list" {
			found = true
			break
		}
	}
	if !found {
		t.Error("expected 'list' subcommand with correct Use")
	}
}

// TestSessionCmd_getHasCorrectUse tests that get subcommand has correct Use.
func TestSessionCmd_getHasCorrectUse(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := SessionCmd(c)

	found := false
	for _, subcmd := range cmd.Commands() {
		if subcmd.Use == "get <session-id>" {
			found = true
			break
		}
	}
	if !found {
		t.Error("expected 'get' subcommand with session-id argument")
	}
}

// TestSessionCmd_workflowHasCorrectUse tests that workflow subcommand has correct Use.
func TestSessionCmd_workflowHasCorrectUse(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := SessionCmd(c)

	found := false
	for _, subcmd := range cmd.Commands() {
		if subcmd.Use == "workflow" {
			found = true
			break
		}
	}
	if !found {
		t.Error("expected 'workflow' subcommand with correct Use")
	}
}
