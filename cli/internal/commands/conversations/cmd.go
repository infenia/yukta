// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

// Package conversations holds the commands for the conversations with AI agents: the
// "conversations" group (list, show, send, interrupt, end) and "attach", the one window for all the
// agents of a run.
package conversations

import (
	"errors"
	"fmt"
	"regexp"
	"strconv"
	"strings"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/commands"
	"com.infenia.yukta/go-cli/internal/output"
	"github.com/spf13/cobra"
)

// control matches what an agent could write to take over a terminal: escape sequences (CSI, OSC, character sets and
// the two-character ones) and the other control characters; a line break and a tab stay.
var control = regexp.MustCompile(`\x1b\[[0-9;?]*[ -/]*[@-~]|\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)|\x1b[()*+][0-9A-Za-z]|\x1b[@-Z\\-_]|[\x00-\x08\x0b-\x1f\x7f]`)

// clean removes the control sequences from text an agent wrote.
func clean(text string) string {
	return control.ReplaceAllString(text, "")
}

// ConversationsCmd creates the "conversations" command group.
func ConversationsCmd(c *client.Client) *cobra.Command {
	cmd := &cobra.Command{
		Use:   "conversations",
		Short: "Conversations with AI agents: list, show, send, interrupt, end",
		Long: `Conversations are what the AGENT_SESSION nodes of your workflows hold with AI agents.
Reading needs a VIEWER key. Sending a message, interrupting and ending need an OPERATOR key that
the node's steerAudience names (the default is role:OPERATOR).

What an agent writes is untrusted text. Control characters are not printed. To follow all the
agents of a run in one place, use "attach".`,
	}
	cmd.AddCommand(listCmd(c), showCmd(c), sendCmd(c), interruptCmd(c), endCmd(c))
	return cmd
}

func listCmd(c *client.Client) *cobra.Command {
	var execution, state, session string
	var limit int
	cmd := &cobra.Command{
		Use:   "list [--execution E] [--state RUNNING,WAITING,IDLE]",
		Short: "List conversations",
		Long: `List the conversations, newest first: all the key reaches, or those of a run (and of the
runs below it) with --execution. --state takes a comma-separated list of STARTING, RUNNING,
WAITING, IDLE and ENDED.`,
		Args: cobra.NoArgs,
		RunE: func(cmd *cobra.Command, args []string) error {
			found, err := c.ListConversations(client.ConversationListOptions{
				SessionID: session, ExecutionID: execution, State: state, Limit: limit})
			if err != nil {
				return fmt.Errorf("failed to list the conversations: %w", err)
			}
			if commands.GetOutputFormat() == "json" {
				output.PrintJSON(found)
				return nil
			}
			rows := make([][]string, 0, len(found))
			for _, f := range found {
				rows = append(rows, []string{f.ID, f.ExecutionID, f.NodeID, f.Harness, f.Protocol,
					f.State, f.Outcome, strconv.Itoa(f.Turn), strconv.Itoa(f.Queued)})
			}
			output.PrintTable([]string{"Conversation", "Execution", "Node", "Harness", "Protocol", "State",
				"Outcome", "Turns", "Queued"}, rows)
			return nil
		},
	}
	cmd.Flags().StringVar(&execution, "execution", "", "Only this run and the runs below it")
	cmd.Flags().StringVar(&session, "session", "", "Only this session")
	cmd.Flags().StringVar(&state, "state", "", "Comma-separated states")
	cmd.Flags().IntVar(&limit, "limit", 0, "Most conversations (default 100, at most 500)")
	return cmd
}

func showCmd(c *client.Client) *cobra.Command {
	var after int64
	var limit int
	cmd := &cobra.Command{
		Use:   "show <id> [--after SEQ]",
		Short: "Show a conversation and what happened in it",
		Long: `Show a conversation and its events after the sequence number --after (default: from the
start), one page of at most --limit (default 100). The last line says where the next page starts.`,
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			conversation, err := c.GetConversation(args[0])
			if err != nil {
				return fmt.Errorf("failed to read the conversation: %w", err)
			}
			page, err := c.ConversationEvents(args[0], after, limit)
			if err != nil {
				return fmt.Errorf("failed to read the events: %w", err)
			}
			if commands.GetOutputFormat() == "json" {
				output.PrintJSON(struct {
					Conversation *client.Conversation       `json:"conversation"`
					Events       []client.ConversationEvent `json:"events"`
					Next         int64                      `json:"next,omitempty"`
				}{conversation, page.Events, page.Next})
				return nil
			}
			out := cmd.OutOrStdout()
			_, _ = fmt.Fprintf(out, "%s  %s %s (%s)  node %s  %s%s\n", conversation.ID, conversation.Harness,
				conversation.Protocol, conversation.Model, conversation.NodeID, conversation.State,
				suffix(conversation.Outcome))
			render := newRenderer()
			for _, event := range page.Events {
				for _, line := range render.lines(event) {
					_, _ = fmt.Fprintln(out, line)
				}
			}
			if page.Next > 0 {
				_, _ = fmt.Fprintf(out, "more: --after %d\n", page.Next)
			}
			return nil
		},
	}
	cmd.Flags().Int64Var(&after, "after", 0, "Only events after this sequence number")
	cmd.Flags().IntVar(&limit, "limit", 0, "Most events (default 100, at most 500)")
	return cmd
}

func sendCmd(c *client.Client) *cobra.Command {
	var interrupt bool
	cmd := &cobra.Command{
		Use:   "send <id> <text> [--interrupt]",
		Short: "Send a message to an agent",
		Long: `Send a message. It waits for the end of the agent's turn unless the agent takes it during
the turn or --interrupt stops the turn first.`,
		Args: cobra.ExactArgs(2),
		RunE: func(cmd *cobra.Command, args []string) error {
			queued, err := c.SendConversationMessage(args[0], args[1], interrupt)
			if err != nil {
				return fmt.Errorf("failed to send the message: %w", err)
			}
			if queued {
				_, _ = fmt.Fprintln(cmd.OutOrStdout(), "message queued: it waits for the end of the turn")
				return nil
			}
			_, _ = fmt.Fprintln(cmd.OutOrStdout(), "message sent")
			return nil
		},
	}
	cmd.Flags().BoolVar(&interrupt, "interrupt", false, "Stop the agent's turn first")
	return cmd
}

func interruptCmd(c *client.Client) *cobra.Command {
	return &cobra.Command{
		Use:   "interrupt <id>",
		Short: "Stop the agent's current turn",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if err := c.InterruptConversation(args[0]); err != nil {
				return fmt.Errorf("failed to interrupt the agent: %w", err)
			}
			_, _ = fmt.Fprintln(cmd.OutOrStdout(), "interrupt sent")
			return nil
		},
	}
}

func endCmd(c *client.Client) *cobra.Command {
	var outcome, reason string
	cmd := &cobra.Command{
		Use:   "end <id> [--outcome success|failure|incomplete] [--reason R]",
		Short: "End a conversation",
		Long: `End the conversation: the agent is closed and its node goes on with --outcome, else the
status of the last turn.`,
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			switch outcome {
			case "", "success", "failure", "incomplete":
			default:
				return fmt.Errorf("invalid --outcome %q: use success, failure or incomplete", outcome)
			}
			if err := c.EndConversation(args[0], outcome, reason); err != nil {
				return fmt.Errorf("failed to end the conversation: %w", err)
			}
			_, _ = fmt.Fprintln(cmd.OutOrStdout(), "end sent")
			return nil
		},
	}
	cmd.Flags().StringVar(&outcome, "outcome", "", "success, failure or incomplete (default: the last turn's)")
	cmd.Flags().StringVar(&reason, "reason", "", "Why")
	return cmd
}

func suffix(outcome string) string {
	if outcome == "" {
		return ""
	}
	return " (" + outcome + ")"
}

// renderer turns events into lines of text, one item at a time: the pieces of a message are
// gathered until the last one arrives.
type renderer struct {
	pieces map[string]*strings.Builder
}

func newRenderer() *renderer {
	return &renderer{pieces: map[string]*strings.Builder{}}
}

// lines is what to print for an event; none for the pieces of a message that is not complete and
// for terminal output.
func (r *renderer) lines(e client.ConversationEvent) []string {
	text := ""
	switch e.Kind {
	case "MESSAGE":
		piece := str(e.Data["text"])
		if e.ItemID != "" {
			b := r.pieces[e.ItemID]
			if b == nil {
				b = &strings.Builder{}
				r.pieces[e.ItemID] = b
			}
			b.WriteString(piece)
			if more, _ := e.Data["more"].(bool); more {
				return nil
			}
			piece = b.String()
			delete(r.pieces, e.ItemID)
		}
		text = role(e.Data) + piece
	case "TOOL":
		text = "tool " + str(e.Data["title"]) + " [" + str(e.Data["status"]) + "]"
	case "REQUEST":
		text = "request " + str(e.Data["inputRequestId"]) + " (" + str(e.Data["state"]) + "): " + str(e.Data["prompt"])
	case "RESULT":
		text = "result " + str(e.Data["status"]) + reasonOf(e.Data)
	case "NOTICE":
		text = "notice " + str(e.Data["level"]) + ": " + str(e.Data["text"])
	case "STATE":
		text = "state " + str(e.Data["state"]) + reasonOf(e.Data)
	case "PLAN":
		text = "plan updated"
	default:
		return nil
	}
	return strings.Split(strings.TrimRight(clean(text), "\n"), "\n")
}

func role(data map[string]any) string {
	switch str(data["role"]) {
	case "user":
		if author := str(data["author"]); author != "" && author != "prompt" {
			return "you (" + author + "): "
		}
		return "you: "
	case "thought":
		return "(thinking) "
	default:
		return ""
	}
}

func reasonOf(data map[string]any) string {
	if reason := str(data["reason"]); reason != "" {
		return ": " + reason
	}
	return ""
}

func str(v any) string {
	if s, ok := v.(string); ok {
		return s
	}
	return ""
}

var errNoAgent = errors.New("no agent is selected: use /agents and /agent <number|node>")
