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

package app

import (
	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands"
	"com.infenia.yukta/go-cli/internal/commands/session"
)

// Run initializes and executes the Yukta CLI application.
// It creates the HTTP client, wires up all commands, and executes them.
// This function is testable and returns an error instead of calling os.Exit().
func Run() error {
	// Create a new HTTP client with empty string (uses default URL from environment or hardcoded default)
	c := client.NewClient("")

	// Create the root command
	rootCmd := commands.RootCmd(c)

	// Add the session command group with all its subcommands
	rootCmd.AddCommand(session.SessionCmd(c))

	// Execute the command and return any error
	return rootCmd.Execute()
}
