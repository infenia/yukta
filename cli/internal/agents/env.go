// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package agents

import (
	"bytes"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

// Env is everything the installer reads from the machine, so tests can swap it.
type Env struct {
	Home     string // the user's home folder
	Config   string // the user's config folder (XDG_CONFIG_HOME, %APPDATA%, Library/Application Support)
	LookPath func(string) (string, error)
	Run      func(name string, args ...string) error
}

// DefaultEnv is the real machine.
func DefaultEnv() (Env, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return Env{}, err
	}
	cfg, err := os.UserConfigDir()
	if err != nil {
		return Env{}, err
	}
	return Env{Home: home, Config: cfg, LookPath: exec.LookPath, Run: run}, nil
}

// run executes an agent's own CLI; its output is kept out of ours except for the last line of a failure.
func run(name string, args ...string) error {
	out, err := exec.Command(name, args...).CombinedOutput() // #nosec G204 -- fixed agent commands
	if err != nil {
		lines := strings.Split(strings.TrimSpace(string(bytes.TrimSpace(out))), "\n")
		return fmt.Errorf("%s %s: %w: %s", name, args[0], err, lines[len(lines)-1])
	}
	return nil
}

func (e Env) has(bins ...string) bool {
	for _, b := range bins {
		if _, err := e.LookPath(b); err == nil {
			return true
		}
	}
	return false
}

func (e Env) isDir(parts ...string) bool {
	info, err := os.Stat(filepath.Join(parts...))
	return err == nil && info.IsDir()
}
