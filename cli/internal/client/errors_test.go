// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
)

// calls invokes every endpoint method once with valid arguments.
func calls() map[string]func(c *Client) error {
	ctx := context.Background()
	noop := func(string) error { return nil }
	w := func(_ WorkflowStartResponse, err error) error { return err }
	return map[string]func(c *Client) error{
		"GetSessions":         func(c *Client) error { _, err := c.GetSessions(); return err },
		"GetSessionDetails":   func(c *Client) error { _, err := c.GetSessionDetails("s"); return err },
		"GetWorkflow":         func(c *Client) error { _, err := c.GetWorkflow("s", "w"); return err },
		"ApplyConfig":         func(c *Client) error { return c.ApplyConfig([]byte(`{}`)) },
		"ListPlugins":         func(c *Client) error { _, err := c.ListPlugins(); return err },
		"GetPluginDetails":    func(c *Client) error { _, err := c.GetPluginDetails("p"); return err },
		"GetActiveNodes":      func(c *Client) error { _, err := c.GetActiveNodesInWorkflow("w"); return err },
		"GetAllActiveNodes":   func(c *Client) error { _, err := c.GetAllActiveNodes(); return err },
		"GetLastHeartbeat":    func(c *Client) error { _, err := c.GetLastHeartbeat("w", "n"); return err },
		"SendCommand":         func(c *Client) error { _, err := c.SendCommand("w", "n", []byte(`{}`)); return err },
		"GetSessionSummaries": func(c *Client) error { _, err := c.GetSessionSummaries(); return err },
		"GetWorkflowGraph":    func(c *Client) error { _, err := c.GetWorkflowGraph("s", "w"); return err },
		"GetWorkflowExecs":    func(c *Client) error { _, err := c.GetWorkflowExecutions("s", "w"); return err },
		"StartWorkflow":       func(c *Client) error { return w(c.StartWorkflow("s", "w", nil)) },
		"StopWorkflow":        func(c *Client) error { _, err := c.StopWorkflow("s", "w", ""); return err },
		"StopExecution":       func(c *Client) error { return w(c.StopExecution("e")) },
		"RestartExecution":    func(c *Client) error { return w(c.RestartExecution("e")) },
		"RestartFromNode":     func(c *Client) error { return w(c.RestartFromNode("e", "n")) },
		"PauseWorkflow":       func(c *Client) error { return w(c.PauseWorkflow("s", "e")) },
		"ResumeWorkflow":      func(c *Client) error { return w(c.ResumeWorkflow("s", "e")) },
		"GetWorkflowStatus":   func(c *Client) error { _, err := c.GetWorkflowStatus("s", "e"); return err },
		"GetWorkflowHistory":  func(c *Client) error { _, err := c.GetWorkflowHistory("s"); return err },
		"PauseNode":           func(c *Client) error { return w(c.PauseNode("s", "e", "n")) },
		"ResumeNode":          func(c *Client) error { return w(c.ResumeNode("s", "e", "n")) },
		"EnableStepMode":      func(c *Client) error { return w(c.EnableStepMode("s", "e", "n")) },
		"DisableStepMode":     func(c *Client) error { return w(c.DisableStepMode("s", "e", "n")) },
		"StepNode":            func(c *Client) error { return w(c.StepNode("s", "e", "n")) },
		"StopNode":            func(c *Client) error { return w(c.StopNode("s", "e", "n", true, "r")) },
		"SkipNode":            func(c *Client) error { return w(c.SkipNode("s", "e", "n", true)) },
		"StreamLogs":          func(c *Client) error { return c.StreamExecutionLogs(ctx, "s", "e", noop) },
		"StreamLogEntries":    func(c *Client) error { return c.StreamExecutionLogEntries(ctx, "s", "e", noop) },
		"StreamStatus": func(c *Client) error {
			return c.StreamWorkflowStatus(ctx, "s", "e", false, func(WorkflowProgress) error { return nil })
		},
	}
}

// Every method must report, not swallow, a bad base URL, an unreachable server, an API error
// and (for JSON endpoints) an undecodable body.
func TestEveryEndpointReportsFailures(t *testing.T) {
	down := httptest.NewServer(http.NotFoundHandler())
	downURL := down.URL
	down.Close()

	garbage := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte("not json"))
	}))
	defer garbage.Close()

	failing := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		http.Error(w, "boom", http.StatusInternalServerError)
	}))
	defer failing.Close()

	for name, call := range calls() {
		for scenario, baseURL := range map[string]string{
			"bad URL":     "http://bad\x7fhost",
			"unreachable": downURL,
			"API error":   failing.URL,
		} {
			if err := call(NewClient(baseURL)); err == nil {
				t.Errorf("%s/%s: expected error", name, scenario)
			}
		}
		// Endpoints that decode a body must fail on garbage; the rest may accept it.
		_ = call(NewClient(garbage.URL))
	}
}

func TestDecodeFailures(t *testing.T) {
	garbage := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte("not json"))
	}))
	defer garbage.Close()

	c := NewClient(garbage.URL)
	for _, name := range []string{
		"GetSessions", "GetSessionDetails", "GetWorkflow", "ListPlugins", "GetPluginDetails",
		"GetActiveNodes", "GetAllActiveNodes", "GetLastHeartbeat", "SendCommand",
		"StartWorkflow", "StopWorkflow", "StopExecution", "RestartExecution", "RestartFromNode",
		"PauseWorkflow", "ResumeWorkflow", "GetWorkflowStatus", "GetWorkflowHistory",
		"PauseNode", "ResumeNode", "EnableStepMode", "DisableStepMode", "StepNode", "StopNode", "SkipNode",
	} {
		if err := calls()[name](c); err == nil {
			t.Errorf("%s: expected decode error", name)
		}
	}
}

func TestNewRequestWithBody_invalidMethod(t *testing.T) {
	if _, err := NewClient("http://localhost").newRequestWithBody("BAD METHOD", "/x", nil); err == nil {
		t.Error("expected error for invalid method")
	}
}
