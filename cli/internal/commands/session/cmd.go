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
	"com.infenia.yukta/go-cli/internal/client"
	"github.com/spf13/cobra"
)

// SessionCmd creates and returns the "session" command group.
// This is a parent command that organizes session-related operations.
// Subcommands (list, get, apply, workflow) will be added by later tasks.
func SessionCmd(c client.ClientInterface) *cobra.Command {
	sessionCmd := &cobra.Command{
		Use:   "session",
		Short: "Manage sessions",
		Long: `The session command group provides operations for managing Yukta sessions.

Subcommands allow you to:
  - List all available sessions
  - Get details about a specific session
  - Apply session configurations
  - Manage workflows within sessions`,
		// This is a command group; no Run action needed. Subcommands are added separately.
	}

	// Subcommands are registered as they are implemented in later tasks:
	// - sessionCmd.AddCommand(ListCmd(c))     // Task 6
	// - sessionCmd.AddCommand(GetCmd(c))      // Task 7
	// - sessionCmd.AddCommand(ApplyCmd(c))    // Task 8
	sessionCmd.AddCommand(ListCmd(c))     // Task 6
	sessionCmd.AddCommand(GetCmd(c))      // Task 7
	sessionCmd.AddCommand(WorkflowCmd(c)) // Task 9

	return sessionCmd
}
