// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package server

import (
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands"
	"com.infenia.yukta/go-cli/internal/output"
	"github.com/spf13/cobra"
)

// tailLines is how many lines "logs" shows first; followPoll is how often -f looks for more.
const tailLines = 100

var followPoll = 500 * time.Millisecond

// ServerCmd creates the "server" group: the state of the daemon started with "yukta start".
// It reads the same files as the server's own status command: yukta.pid and yukta.log in
// $YUKTA_HOME, else $XDG_STATE_HOME/yukta, else ~/.local/state/yukta.
func ServerCmd(c *client.Client) *cobra.Command {
	cmd := &cobra.Command{Use: "server", Short: "Inspect the Yukta daemon (state and logs)"}
	cmd.AddCommand(statusCmd(c), logsCmd())
	return cmd
}

func home() string {
	if h := os.Getenv("YUKTA_HOME"); h != "" {
		return h
	}
	base := os.Getenv("XDG_STATE_HOME")
	if base == "" {
		u, _ := os.UserHomeDir()
		base = filepath.Join(u, ".local", "state")
	}
	return filepath.Join(base, "yukta")
}

// readPid parses "<pid> [<start millis>]".
func readPid(path string) (pid int, started time.Time, err error) {
	data, err := os.ReadFile(path) // #nosec G304 -- the daemon's own state file
	if err != nil {
		return 0, time.Time{}, err
	}
	parts := strings.Fields(string(data))
	if len(parts) == 0 {
		return 0, time.Time{}, errors.New("empty pid file")
	}
	if pid, err = strconv.Atoi(parts[0]); err != nil {
		return 0, time.Time{}, err
	}
	if len(parts) > 1 {
		if ms, perr := strconv.ParseInt(parts[1], 10, 64); perr == nil {
			started = time.UnixMilli(ms)
		}
	}
	return pid, started, nil
}

// alive tells whether pid is the daemon: a live process that started when the pid file says (the
// start time guards against a recycled pid, like the Java side). Without a recorded start time, or
// where ps is missing, the process is taken as is.
func alive(pid int, started time.Time) bool {
	p, err := os.FindProcess(pid)
	if err != nil || p.Signal(syscall.Signal(0)) != nil {
		return false
	}
	if started.IsZero() {
		return true
	}
	// #nosec G204 -- pid is an int parsed from our own state file
	out, _ := exec.Command("ps", "-o", "etime=", "-p", strconv.Itoa(pid)).Output() // empty on failure
	elapsed, ok := parseEtime(strings.TrimSpace(string(out)))
	if !ok {
		return true
	}
	diff := time.Since(started) - elapsed
	return diff > -2*time.Second && diff < 2*time.Second
}

// parseEtime reads ps's elapsed time: [[dd-]hh:]mm:ss.
func parseEtime(s string) (time.Duration, bool) {
	var days int64
	if d, rest, found := strings.Cut(s, "-"); found {
		n, err := strconv.ParseInt(d, 10, 64)
		if err != nil {
			return 0, false
		}
		days, s = n, rest
	}
	parts := strings.Split(s, ":")
	if len(parts) > 3 {
		return 0, false
	}
	var secs int64
	for _, p := range parts { // hh:mm:ss or mm:ss, folded base 60
		n, err := strconv.ParseInt(p, 10, 64)
		if err != nil {
			return 0, false
		}
		secs = secs*60 + n
	}
	total := days*86400 + secs
	return time.Duration(total) * time.Second, true
}

func health(base string) string {
	hc := http.Client{Timeout: 2 * time.Second}
	resp, err := hc.Get(strings.TrimRight(base, "/") + "/actuator/health") // #nosec G107 -- the user's --url
	if err != nil {
		return "not answering"
	}
	_ = resp.Body.Close()
	if resp.StatusCode == http.StatusOK {
		return "UP"
	}
	return fmt.Sprintf("HTTP %d", resp.StatusCode)
}

func statusCmd(c *client.Client) *cobra.Command {
	return &cobra.Command{
		Use:   "status",
		Short: "Show the daemon's state, pid, health and uptime",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			pid, started, err := readPid(filepath.Join(home(), "yukta.pid"))
			if err != nil || !alive(pid, started) {
				return errors.New("yukta is not running")
			}
			uptime := "-"
			if !started.IsZero() {
				uptime = time.Since(started).Round(time.Second).String()
			}
			h := health(c.BaseURL)
			if commands.GetOutputFormat() == "json" {
				output.PrintJSON(map[string]any{"running": true, "pid": pid, "health": h, "uptime": uptime, "url": c.BaseURL})
				return nil
			}
			output.PrintTable([]string{"Field", "Value"}, [][]string{
				{"state", "running"}, {"pid", strconv.Itoa(pid)}, {"health", h}, {"uptime", uptime}, {"url", c.BaseURL},
			})
			return nil
		},
	}
}

func logsCmd() *cobra.Command {
	var follow bool
	cmd := &cobra.Command{
		Use:   "logs",
		Short: "Show the end of the daemon log (-f to follow)",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			path := filepath.Join(home(), "yukta.log")
			f, err := os.Open(path) // #nosec G304 -- the daemon's own log file
			if err != nil {
				return fmt.Errorf("no log yet at %s", path)
			}
			defer func() { _ = f.Close() }()
			data, _ := io.ReadAll(f)
			lines := strings.Split(strings.TrimRight(string(data), "\n"), "\n")
			if len(lines) > tailLines {
				lines = lines[len(lines)-tailLines:]
			}
			out := cmd.OutOrStdout()
			_, _ = fmt.Fprintln(out, strings.Join(lines, "\n"))
			if !follow {
				return nil
			}
			// cobra's Execute sets a context, so it is never nil here.
			ctx, stop := signal.NotifyContext(cmd.Context(), os.Interrupt, syscall.SIGTERM)
			defer stop()
			for {
				// f is at EOF, so each copy yields only what was appended since.
				_, _ = io.Copy(out, f)
				select {
				case <-ctx.Done():
					return nil
				case <-time.After(followPoll):
				}
			}
		},
	}
	cmd.Flags().BoolVarP(&follow, "follow", "f", false, "Keep printing new lines until Ctrl-C")
	return cmd
}
