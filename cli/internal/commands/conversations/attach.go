// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package conversations

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"strconv"
	"strings"
	"sync"

	"com.infenia.yukta/go-cli/internal/client"
	"github.com/spf13/cobra"
)

// agentView is what attach knows of one conversation.
type agentView struct {
	conversation client.Conversation
	render       *renderer
	// openRequest is the input request of the agent that waits for an answer, if any.
	openRequest string
}

// attacher follows the conversations of a run and prints them in one place.
type attacher struct {
	c           *client.Client
	out         io.Writer
	jsonLines   bool
	interactive bool
	filter      string

	// term shows the raw terminal of an agent until the person leaves it; nil without a terminal
	term func(client.Conversation) error

	mu       sync.Mutex
	hold     bool
	held     []string
	agents   []*agentView
	byID     map[string]*agentView
	selected string
}

// AttachCmd creates the "attach" command.
func AttachCmd(c *client.Client) *cobra.Command {
	var agent string
	var jsonLines bool
	cmd := &cobra.Command{
		Use:   "attach <execution-id> [--agent <id|node-id>] [--json]",
		Short: "Follow and talk to all the agents of a run in one place",
		Long: `Follow the conversations of a run, and of the runs it starts, as one timeline: one line per
item, prefixed [node harness]. The command ends when the run does, and picks up again after a
dropped connection without losing or repeating a line.

With a terminal on standard input and output it also reads commands and messages:

  text           send the text to the selected agent
  /agents        list the agents, with a number each
  /agent <n|node> select an agent by number or node id
  /approve [request] [rationale]   approve the request the agent waits on
  /reject  [request] [rationale]   reject it
  /interrupt     stop the selected agent's turn
  /end           end the selected agent's conversation
  /quit          leave (the run goes on)

With --json, or when the input or output is not a terminal, it only prints (one JSON object per
line with --json) and reads nothing.

What an agent writes is untrusted text; control characters are not printed.`,
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			a := &attacher{
				c:           c,
				out:         cmd.OutOrStdout(),
				jsonLines:   jsonLines,
				interactive: !jsonLines && isTerminal(cmd.InOrStdin()) && isTerminal(cmd.OutOrStdout()),
				filter:      agent,
				byID:        map[string]*agentView{},
			}
			lines, restore := a.lineSource(cmd.Context(), cmd.InOrStdin(), cmd.OutOrStdout())
			defer restore()
			return a.run(cmd.Context(), args[0], lines)
		},
	}
	cmd.Flags().StringVar(&agent, "agent", "", "Start with this agent selected (conversation id or node id)")
	cmd.Flags().BoolVar(&jsonLines, "json", false, "Print one JSON object per line and read nothing")
	return cmd
}

func isTerminal(v any) bool {
	f, ok := v.(*os.File)
	if !ok {
		return false
	}
	info, err := f.Stat()
	return err == nil && info.Mode()&os.ModeCharDevice != 0
}

func (a *attacher) run(ctx context.Context, executionID string, lines lineSource) error {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	if a.interactive {
		go a.readInput(cancel, lines)
	}
	follow := &client.ConversationFollow{ExecutionID: executionID}
	err := a.c.StreamConversations(ctx, follow, a.onConversation, a.onEvent)
	if ctx.Err() != nil && err == ctx.Err() {
		return nil // the person left
	}
	return err
}

func (a *attacher) onConversation(conversation client.Conversation) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	agent := a.byID[conversation.ID]
	previous := ""
	if agent == nil {
		agent = &agentView{render: newRenderer()}
		a.byID[conversation.ID] = agent
		a.agents = append(a.agents, agent)
		if a.selected == "" && a.filter != "" && (a.filter == conversation.ID || a.filter == conversation.NodeID) {
			a.selected = conversation.ID
		}
	} else {
		previous = agent.conversation.State
	}
	agent.conversation = conversation
	if a.jsonLines {
		return a.printJSON("conversation", conversation)
	}
	if conversation.State != previous {
		a.print(agent, "state "+conversation.State+suffix(conversation.Outcome)+reasonOf(map[string]any{"reason": conversation.Reason}))
	}
	return nil
}

func (a *attacher) onEvent(event client.ConversationEvent) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	agent := a.byID[event.ConversationID]
	if a.jsonLines {
		return a.printJSON("event", event)
	}
	if agent == nil {
		agent = &agentView{render: newRenderer()}
		agent.conversation = client.Conversation{ID: event.ConversationID}
		a.byID[event.ConversationID] = agent
		a.agents = append(a.agents, agent)
	}
	if event.Kind == "REQUEST" {
		id := str(event.Data["inputRequestId"])
		if str(event.Data["state"]) == "open" {
			agent.openRequest = id
		} else if agent.openRequest == id {
			agent.openRequest = ""
		}
	}
	for _, line := range agent.render.lines(event) {
		a.print(agent, line)
	}
	return nil
}

func (a *attacher) print(agent *agentView, line string) {
	c := agent.conversation
	text := fmt.Sprintf("[%s %s] %s\n", clean(c.NodeID), clean(c.Harness), line)
	if a.hold {
		// the raw terminal owns the screen: what comes meanwhile is shown after it
		a.held = append(a.held, text)
		return
	}
	_, _ = io.WriteString(a.out, text)
}

func (a *attacher) printJSON(kind string, v any) error {
	body, err := json.Marshal(v)
	if err != nil {
		return err
	}
	_, err = fmt.Fprintf(a.out, "{\"type\":%q,\"%s\":%s}\n", kind, kind, body)
	return err
}

// ---- what the person types

// lineSource gives the lines the person types, one at a time: a terminal prompt or a plain reader.
type lineSource interface {
	ReadLine() (string, error)
}

type scannerLines struct{ scanner *bufio.Scanner }

func newLines(in io.Reader) lineSource {
	return &scannerLines{scanner: bufio.NewScanner(in)}
}

func (l *scannerLines) ReadLine() (string, error) {
	if l.scanner.Scan() {
		return l.scanner.Text(), nil
	}
	if err := l.scanner.Err(); err != nil {
		return "", err
	}
	return "", io.EOF
}

func (a *attacher) readInput(quit context.CancelFunc, lines lineSource) {
	for {
		text, err := lines.ReadLine()
		if err != nil {
			return
		}
		line := strings.TrimSpace(text)
		if line == "" {
			continue
		}
		if line == "/quit" {
			quit()
			return
		}
		a.tell(a.handle(line))
	}
}

func (a *attacher) tell(message string) {
	if message == "" {
		return
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	_, _ = fmt.Fprintln(a.out, "* "+message)
}

// handle runs a line the person typed and returns what to tell them.
func (a *attacher) handle(line string) string {
	if !strings.HasPrefix(line, "/") {
		return a.withSelected(func(c client.Conversation, _ *agentView) string {
			queued, err := a.c.SendConversationMessage(c.ID, line, false)
			switch {
			case err != nil:
				return "not sent: " + clean(err.Error())
			case queued:
				return "queued: it waits for the end of the turn"
			default:
				return ""
			}
		})
	}
	fields := strings.Fields(line)
	switch fields[0] {
	case "/agents":
		return a.listAgents()
	case "/agent":
		if len(fields) != 2 {
			return "usage: /agent <number|node>"
		}
		return a.selectAgent(fields[1])
	case "/approve":
		return a.answer(fields[1:], "approve")
	case "/reject":
		return a.answer(fields[1:], "reject")
	case "/interrupt":
		return a.withSelected(func(c client.Conversation, _ *agentView) string {
			if err := a.c.InterruptConversation(c.ID); err != nil {
				return "not interrupted: " + clean(err.Error())
			}
			return "interrupt sent"
		})
	case "/end":
		return a.withSelected(func(c client.Conversation, _ *agentView) string {
			if err := a.c.EndConversation(c.ID, "", ""); err != nil {
				return "not ended: " + clean(err.Error())
			}
			return "end sent"
		})
	case "/term":
		return a.rawTerminal()
	default:
		return "unknown command " + clean(fields[0]) + "; try /agents, /agent, /approve, /reject, /interrupt, /end, /quit"
	}
}

func (a *attacher) listAgents() string {
	a.mu.Lock()
	defer a.mu.Unlock()
	var b strings.Builder
	for i, agent := range a.agents {
		c := agent.conversation
		mark := " "
		if c.ID == a.selected {
			mark = "*"
		}
		_, _ = fmt.Fprintf(&b, "\n%s %d  %s  %s %s  %s", mark, i+1, c.ID, clean(c.NodeID), clean(c.Harness), c.State)
		if agent.openRequest != "" {
			b.WriteString("  waits on request " + clean(agent.openRequest))
		}
	}
	if b.Len() == 0 {
		return "no agents yet"
	}
	return strings.TrimPrefix(b.String(), "\n")
}

func (a *attacher) selectAgent(which string) string {
	a.mu.Lock()
	defer a.mu.Unlock()
	if n, err := strconv.Atoi(which); err == nil {
		if n < 1 || n > len(a.agents) {
			return "no agent number " + which
		}
		a.selected = a.agents[n-1].conversation.ID
		return "selected " + clean(a.agents[n-1].conversation.NodeID)
	}
	for _, agent := range a.agents {
		if agent.conversation.NodeID == which || agent.conversation.ID == which {
			a.selected = agent.conversation.ID
			return "selected " + clean(agent.conversation.NodeID)
		}
	}
	return "no agent " + clean(which)
}

// withSelected runs fn for the selected agent, or for the only one that is not over.
func (a *attacher) withSelected(fn func(client.Conversation, *agentView) string) string {
	a.mu.Lock()
	agent, err := a.current()
	var conversation client.Conversation
	if err == nil {
		conversation = agent.conversation
	}
	a.mu.Unlock()
	if err != nil {
		return err.Error()
	}
	return fn(conversation, agent)
}

func (a *attacher) current() (*agentView, error) {
	if agent := a.byID[a.selected]; agent != nil {
		return agent, nil
	}
	var live []*agentView
	for _, agent := range a.agents {
		if agent.conversation.State != "ENDED" {
			live = append(live, agent)
		}
	}
	if len(live) == 1 {
		a.selected = live[0].conversation.ID
		return live[0], nil
	}
	return nil, errNoAgent
}

// answer approves or rejects the request an agent waits on: the one named, else the open one.
func (a *attacher) answer(args []string, decision string) string {
	return a.withSelected(func(c client.Conversation, agent *agentView) string {
		a.mu.Lock()
		request := agent.openRequest
		a.mu.Unlock()
		if len(args) > 0 {
			request = args[0]
			args = args[1:]
		}
		if request == "" {
			return "the agent waits on no request"
		}
		_, err := a.c.AnswerInput(c.ExecutionID, request, client.AnswerInputRequest{
			Decision: decision, Rationale: strings.Join(args, " ")})
		if err != nil {
			return "not answered: " + clean(err.Error())
		}
		return strings.TrimSuffix(decision, "e") + "ed request " + clean(request)
	})
}

// rawTerminal hands the screen to the terminal of the selected agent until the person presses Ctrl-].
func (a *attacher) rawTerminal() string {
	return a.withSelected(func(c client.Conversation, _ *agentView) string {
		switch {
		case a.term == nil:
			return "/term needs a terminal on standard input and output"
		case !c.Can("terminal"):
			return "this agent has no terminal"
		}
		a.mu.Lock()
		a.hold = true
		a.mu.Unlock()
		err := a.term(c)
		a.mu.Lock()
		a.hold = false
		held := a.held
		a.held = nil
		for _, text := range held {
			_, _ = io.WriteString(a.out, text)
		}
		a.mu.Unlock()
		if err != nil {
			return "left the terminal: " + clean(err.Error())
		}
		return "back from the terminal"
	})
}
