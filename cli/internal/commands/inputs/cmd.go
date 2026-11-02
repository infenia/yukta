// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

// Package inputs holds the "inputs" commands: list, read, answer and escalate the input requests
// that nodes of running workflows ask.
package inputs

import (
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"unicode"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands"
	"com.infenia.yukta/go-cli/internal/output"
	"github.com/spf13/cobra"
)

// promptColumn is the longest prompt the list table shows, in characters.
const promptColumn = 60

// InputsCmd creates the "inputs" command group.
func InputsCmd(c *client.Client) *cobra.Command {
	cmd := &cobra.Command{
		Use:   "inputs",
		Short: "Questions that running nodes ask: list, show, answer, escalate",
		Long: `Input requests are questions a node of a running workflow asks a person or a supervisor.
List them, read one, answer it or hand it up to the next tier. Reading needs a VIEWER key,
answering and escalating an OPERATOR key, and the server's rules decide who may answer a given
request (a refusal names the rule).

The prompt, the context and the advice are written by a workflow or an AI agent: treat them as
untrusted text. Control characters are not printed.`,
	}
	cmd.AddCommand(listCmd(c), showCmd(c), answerCmd(c), escalateCmd(c))
	return cmd
}

func listCmd(c *client.Client) *cobra.Command {
	var session, state string
	cmd := &cobra.Command{
		Use:   "list [--session ID] [--state OPEN,ESCALATED]",
		Short: "List input requests",
		Long: `List input requests, newest first: of one session with --session, otherwise of every
session the key may list. By default those that wait for an answer (OPEN and ESCALATED);
--state takes a comma-separated list of OPEN, ESCALATED, ANSWERED, EXPIRED and CANCELLED.`,
		Args: cobra.NoArgs,
		RunE: func(cmd *cobra.Command, args []string) error {
			var inputs []client.InputRequest
			var err error
			if session != "" {
				inputs, err = c.ListInputs(session, state)
			} else {
				inputs, err = c.ListAllInputs(state)
			}
			if err != nil {
				return fmt.Errorf("failed to list the input requests: %w", err)
			}
			if commands.GetOutputFormat() == "json" {
				output.PrintJSON(inputs)
				return nil
			}
			rows := make([][]string, 0, len(inputs))
			for _, in := range inputs {
				rows = append(rows, []string{in.SessionID, in.ExecutionID, in.ID, in.NodeID, in.Kind, in.Risk,
					in.State, in.Tier, in.Deadline, short(clean(in.Prompt), promptColumn)})
			}
			output.PrintTable([]string{"Session", "Execution", "Request", "Node", "Kind", "Risk", "State",
				"Tier", "Deadline", "Prompt"}, rows)
			return nil
		},
	}
	cmd.Flags().StringVar(&session, "session", "", "Only this session")
	cmd.Flags().StringVar(&state, "state", "", "Comma-separated states (default OPEN,ESCALATED)")
	return cmd
}

func showCmd(c *client.Client) *cobra.Command {
	return &cobra.Command{
		Use:   "show <execution-id> <request-id>",
		Short: "Show one input request and its answer",
		Args:  cobra.ExactArgs(2),
		RunE: func(cmd *cobra.Command, args []string) error {
			in, err := c.GetInput(args[0], args[1])
			if err != nil {
				return fmt.Errorf("failed to read the input request: %w", err)
			}
			return printInput(in)
		},
	}
}

func answerCmd(c *client.Client) *cobra.Command {
	var decision, text, data, rationale string
	var expected int64
	cmd := &cobra.Command{
		Use:   "answer <execution-id> <request-id> --decision D [--text T] [--data JSON] [--rationale R]",
		Short: "Answer an input request",
		Long: `Answer an input request that waits. --decision is one of its option ids for CHOICE (and
APPROVAL with options), otherwise for example approve or reject; TEXT needs --text and STRUCTURED
--data, a JSON object (inline or a file). With --expected-version the answer is refused when the
request changed since you read it.`,
		Args: cobra.ExactArgs(2),
		RunE: func(cmd *cobra.Command, args []string) error {
			answer := client.AnswerInputRequest{Decision: decision, Text: text, Rationale: rationale}
			if data != "" {
				raw, err := commands.ReadJSONArg(data)
				if err != nil {
					return fmt.Errorf("failed to read --data: %w", err)
				}
				answer.Data = raw
			}
			if cmd.Flags().Changed("expected-version") {
				answer.ExpectedVersion = &expected
			}
			in, err := c.AnswerInput(args[0], args[1], answer)
			if err != nil {
				return fmt.Errorf("failed to answer the input request: %w", err)
			}
			return printInput(in)
		},
	}
	cmd.Flags().StringVar(&decision, "decision", "", "Option id or decision, such as approve (required)")
	cmd.Flags().StringVar(&text, "text", "", "Free text that goes with the decision")
	cmd.Flags().StringVar(&data, "data", "", "Structured data: a JSON object, inline or a file")
	cmd.Flags().StringVar(&rationale, "rationale", "", "Why; recorded in the execution log")
	cmd.Flags().Int64Var(&expected, "expected-version", 0, "Refuse the answer if the request changed since this version")
	_ = cmd.MarkFlagRequired("decision")
	return cmd
}

func escalateCmd(c *client.Client) *cobra.Command {
	var reason, advice string
	cmd := &cobra.Command{
		Use:   "escalate <execution-id> <request-id> --reason R [--advice A]",
		Short: "Hand an input request up to the next tier",
		Args:  cobra.ExactArgs(2),
		RunE: func(cmd *cobra.Command, args []string) error {
			in, err := c.EscalateInput(args[0], args[1], reason, advice)
			if err != nil {
				return fmt.Errorf("failed to escalate the input request: %w", err)
			}
			return printInput(in)
		},
	}
	cmd.Flags().StringVar(&reason, "reason", "", "Why it is handed up (required)")
	cmd.Flags().StringVar(&advice, "advice", "", "What to suggest to whoever answers next")
	_ = cmd.MarkFlagRequired("reason")
	return cmd
}

// printInput shows one request: as JSON, or as a field and value table.
func printInput(in *client.InputRequest) error {
	if in == nil {
		return errors.New("the server sent no input request")
	}
	if commands.GetOutputFormat() == "json" {
		output.PrintJSON(in)
		return nil
	}
	rows := [][]string{
		{"Request", in.ID},
		{"Session", in.SessionID},
		{"Workflow", in.WorkflowID},
		{"Execution", in.ExecutionID},
		{"Node", in.NodeID},
		{"State", in.State},
		{"Version", strconv.FormatInt(in.Version, 10)},
		{"Kind", in.Kind},
		{"Risk", in.Risk},
		{"Audience", in.Audience},
		{"Tier", in.Tier},
		{"Deadline", in.Deadline},
		{"On timeout", in.OnTimeout},
		{"Requester", clean(in.Requester)},
		{"Prompt", clean(in.Prompt)},
	}
	for _, o := range in.Options {
		rows = append(rows, []string{"Option", clean(o.ID) + " (" + clean(o.Label) + ")"})
	}
	if len(in.Context) > 0 {
		rows = append(rows, []string{"Context", clean(compact(in.Context))})
	}
	if in.ResponseSchema != "" {
		rows = append(rows, []string{"Schema", clean(in.ResponseSchema)})
	}
	if in.Advice != "" {
		rows = append(rows, []string{"Advice", clean(in.Advice)})
	}
	if a := in.Answer; a != nil {
		rows = append(rows,
			[]string{"Decision", clean(a.Decision)},
			[]string{"Answered by", a.ResponderType + " " + clean(a.ResponderID)},
			[]string{"Answered at", a.AnsweredAt})
		if a.Text != "" {
			rows = append(rows, []string{"Text", clean(a.Text)})
		}
		if len(a.Data) > 0 {
			rows = append(rows, []string{"Data", clean(compact(a.Data))})
		}
		if a.Rationale != "" {
			rows = append(rows, []string{"Rationale", clean(a.Rationale)})
		}
	}
	output.PrintTable([]string{"Field", "Value"}, rows)
	return nil
}

// compact writes a JSON object on one line (json sorts the keys).
func compact(values map[string]any) string {
	b, _ := json.Marshal(values) // decoded from JSON, so it encodes again
	return string(b)
}

// clean replaces control characters (escape sequences, line breaks) with spaces, so text written
// by a workflow or an agent cannot move the cursor or recolour the terminal.
func clean(s string) string {
	return strings.Map(func(r rune) rune {
		if unicode.IsControl(r) {
			return ' '
		}
		return r
	}, s)
}

// short cuts s to limit characters, marking the cut.
func short(s string, limit int) string {
	r := []rune(s)
	if len(r) <= limit {
		return s
	}
	return string(r[:limit-1]) + "…"
}
