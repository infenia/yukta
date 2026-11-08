// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package supervisors

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/url"
	"os"
	"os/exec"
	"os/signal"
	"strconv"
	"strings"
	"sync"
	"time"

	"com.infenia.yukta/go-cli/internal/client"
	"github.com/spf13/cobra"
)

// agentCmd bridges the agent API to a local program: every signal is written to the program's
// standard input as one JSON line, and every JSON line it writes is sent as a directive. A script
// in any language can be a supervisor this way, with no HTTP client of its own.
func agentCmd(c *client.Client) *cobra.Command {
	var credentialFile, program string
	cmd := &cobra.Command{
		Use:   "agent --credential-file <file> --exec <program> [-- args...]",
		Short: "Run a program as the decision maker of an http supervisor",
		Long: "Connects to /api/agent/v1 with the credential in the file and streams the signals of the supervisor.\n" +
			"Each signal is written to the program's standard input as a JSON line {\"kind\", \"cursor\", \"signal\"};\n" +
			"each line it writes to standard output is a directive: {\"type\":\"command\", \"key\", \"cause\", \"reason\", \"ifStatus\",\n" +
			"\"command\":{...}}, {\"type\":\"verdict\", \"answers\", \"verdict\", \"reason\"}, {\"type\":\"noOpinion\", \"answers\"} or\n" +
			"{\"type\":\"delegationAnswer\", \"answers\", \"answer\":{...}}. It reconnects after the last signal it saw.",
		RunE: func(cmd *cobra.Command, args []string) error {
			token, err := os.ReadFile(credentialFile) // #nosec G304 -- the user names the file
			if err != nil {
				return fmt.Errorf("cannot read the credential file: %w", err)
			}
			c.Token = strings.TrimSpace(string(token))
			ctx, stop := signal.NotifyContext(cmd.Context(), os.Interrupt)
			defer stop()
			return runAgent(ctx, c, program, args, cmd.ErrOrStderr())
		},
	}
	cmd.Flags().StringVar(&credentialFile, "credential-file", "", "A file that holds the credential of the supervisor")
	cmd.Flags().StringVar(&program, "exec", "", "The program that decides")
	_ = cmd.MarkFlagRequired("credential-file")
	_ = cmd.MarkFlagRequired("exec")
	return cmd
}

// runAgent starts the program and keeps the stream going until the context ends or the program does.
func runAgent(ctx context.Context, c *client.Client, program string, args []string, log io.Writer) error {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	proc := exec.CommandContext(ctx, program, args...) // #nosec G204 -- the user names the program
	proc.Stderr = log
	stdin, err := proc.StdinPipe()
	if err != nil {
		return err
	}
	stdout, err := proc.StdoutPipe()
	if err != nil {
		return err
	}
	if err := proc.Start(); err != nil {
		return fmt.Errorf("cannot start %s: %w", program, err)
	}
	exited := make(chan error, 1)
	go func() {
		scanner := bufio.NewScanner(stdout)
		scanner.Buffer(make([]byte, 0, 64*1024), 1024*1024)
		for scanner.Scan() {
			if err := dispatch(scanner.Text(), c.AgentSend); err != nil {
				_, _ = fmt.Fprintln(log, "directive not sent:", err)
			}
		}
		exited <- proc.Wait()
	}()

	var mu sync.Mutex
	after := int64(0)
	backoff := time.Second
	for ctx.Err() == nil {
		err := c.AgentStream(ctx, after, func(data string, cursor string) error {
			mu.Lock()
			defer mu.Unlock()
			if n, perr := strconv.ParseInt(cursor, 10, 64); perr == nil {
				after = n
			}
			_, werr := io.WriteString(stdin, data+"\n")
			return werr
		})
		select {
		case perr := <-exited:
			return fmt.Errorf("the program ended: %v", perr)
		default:
		}
		if ctx.Err() != nil {
			break
		}
		_, _ = fmt.Fprintln(log, "stream ended, reconnecting:", err)
		select {
		case <-time.After(backoff):
		case <-ctx.Done():
		}
		if backoff < 30*time.Second {
			backoff *= 2
		}
	}
	_ = stdin.Close()
	return nil
}

// dispatch sends one line a program wrote to the right place of the agent API.
func dispatch(line string, send func(path string, body []byte) error) error {
	line = strings.TrimSpace(line)
	if line == "" {
		return nil
	}
	var directive map[string]any
	if err := json.Unmarshal([]byte(line), &directive); err != nil {
		return fmt.Errorf("not JSON: %w", err)
	}
	kind, _ := directive["type"].(string)
	answers, _ := directive["answers"].(string)
	switch kind {
	case "command":
		delete(directive, "type")
		body, _ := json.Marshal(directive)
		return send("/api/agent/v1/directives", body)
	case "verdict":
		body, _ := json.Marshal(map[string]any{"verdict": directive["verdict"], "reason": directive["reason"]})
		return send("/api/agent/v1/reviews/"+url.PathEscape(requireID(answers)), body)
	case "noOpinion":
		body, _ := json.Marshal(map[string]any{"verdict": "NO_OPINION"})
		return send("/api/agent/v1/reviews/"+url.PathEscape(requireID(answers)), body)
	case "delegationAnswer":
		body, _ := json.Marshal(map[string]any{"answer": directive["answer"]})
		return send("/api/agent/v1/delegations/"+url.PathEscape(requireID(answers)), body)
	default:
		return fmt.Errorf("unknown directive type %q", kind)
	}
}

func requireID(id string) string {
	if id == "" {
		return "missing"
	}
	return id
}
