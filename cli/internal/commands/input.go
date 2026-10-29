// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package commands

import (
	"fmt"
	"os"
	"strings"
)

// ReadJSONArg reads a JSON argument given as a file path or as inline JSON. An existing file is
// read; text that starts with { or [ is inline JSON; anything else is a mistyped path and says so,
// instead of surfacing later as "invalid JSON".
func ReadJSONArg(input string) ([]byte, error) {
	if info, err := os.Stat(input); err == nil {
		if info.IsDir() {
			return nil, fmt.Errorf("%s is a directory, not a JSON file", input)
		}
		return os.ReadFile(input) // #nosec G304 -- the user names this file on purpose
	}
	if t := strings.TrimSpace(input); strings.HasPrefix(t, "{") || strings.HasPrefix(t, "[") {
		return []byte(input), nil
	}
	return nil, fmt.Errorf("%q is neither an existing file nor inline JSON (file not found?)", input)
}
