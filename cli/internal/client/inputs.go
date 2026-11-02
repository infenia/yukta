// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
)

// InputOption is one choice of an input request.
type InputOption struct {
	ID    string `json:"id"`
	Label string `json:"label"`
}

// InputAnswer is the stored answer to an input request.
type InputAnswer struct {
	Decision      string         `json:"decision"`
	Text          string         `json:"text,omitempty"`
	Data          map[string]any `json:"data,omitempty"`
	ResponderType string         `json:"responderType"`
	ResponderID   string         `json:"responderId"`
	Rationale     string         `json:"rationale,omitempty"`
	Confidence    *float64       `json:"confidence,omitempty"`
	AnsweredAt    string         `json:"answeredAt"`
}

// InputRequest is a question a node of a run asks (GET /api/executions/{e}/inputs/{id}).
// The prompt, the context and the advice are written by a workflow or an AI agent: untrusted text.
type InputRequest struct {
	ID              string         `json:"id"`
	SessionID       string         `json:"sessionId"`
	WorkflowID      string         `json:"workflowId"`
	ExecutionID     string         `json:"executionId"`
	NodeID          string         `json:"nodeId"`
	State           string         `json:"state"`
	Version         int64          `json:"version"`
	Kind            string         `json:"kind"`
	Prompt          string         `json:"prompt"`
	Context         map[string]any `json:"context,omitempty"`
	Options         []InputOption  `json:"options,omitempty"`
	ResponseSchema  string         `json:"responseSchema,omitempty"`
	Risk            string         `json:"risk"`
	Audience        string         `json:"audience"`
	OnTimeout       string         `json:"onTimeout"`
	DefaultDecision string         `json:"defaultDecision,omitempty"`
	Requester       string         `json:"requester"`
	Tier            string         `json:"tier"`
	Advice          string         `json:"advice,omitempty"`
	Deadline        string         `json:"deadline,omitempty"`
	CreatedAt       string         `json:"createdAt"`
	UpdatedAt       string         `json:"updatedAt"`
	Answer          *InputAnswer   `json:"answer,omitempty"`
}

// AnswerInputRequest is the body of POST /api/executions/{e}/inputs/{id}/answer.
type AnswerInputRequest struct {
	Decision        string          `json:"decision"`
	Text            string          `json:"text,omitempty"`
	Data            json.RawMessage `json:"data,omitempty"`
	Rationale       string          `json:"rationale,omitempty"`
	ExpectedVersion *int64          `json:"expectedVersion,omitempty"`
}

// requireInputIDs checks the ids that name an input request.
func requireInputIDs(executionID, requestID string) error {
	if executionID == "" {
		return errors.New("executionID cannot be empty")
	}
	if requestID == "" {
		return errors.New("requestID cannot be empty")
	}
	return nil
}

// ListInputs reads the input requests of a session, newest first; states is a comma-separated
// list (empty: OPEN and ESCALATED, the ones that wait).
func (c *Client) ListInputs(sessionID, states string) ([]InputRequest, error) {
	if sessionID == "" {
		return nil, errors.New("sessionID cannot be empty")
	}
	path := apiPath("/api/sessions/%s/inputs", sessionID)
	if states != "" {
		path += "?" + url.Values{"state": {states}}.Encode()
	}
	return getAll[InputRequest](c, path)
}

// ListAllInputs reads the input requests of every session the key may list, newest first within
// each session.
func (c *Client) ListAllInputs(states string) ([]InputRequest, error) {
	sessions, err := c.GetSessions()
	if err != nil {
		return nil, fmt.Errorf("failed to list the sessions (a key limited to some sessions needs --session): %w", err)
	}
	var all []InputRequest
	for _, session := range sessions {
		inputs, err := c.ListInputs(session, states)
		if err != nil {
			return nil, fmt.Errorf("session %s: %w", session, err)
		}
		all = append(all, inputs...)
	}
	return all, nil
}

// GetInput reads one input request of a run, with its answer once answered.
func (c *Client) GetInput(executionID, requestID string) (*InputRequest, error) {
	if err := requireInputIDs(executionID, requestID); err != nil {
		return nil, err
	}
	return c.sendInput("GET", apiPath("/api/executions/%s/inputs/%s", executionID, requestID), nil)
}

// AnswerInput answers an input request; the server's rules decide whether the key may.
func (c *Client) AnswerInput(executionID, requestID string, answer AnswerInputRequest) (*InputRequest, error) {
	if err := requireInputIDs(executionID, requestID); err != nil {
		return nil, err
	}
	if answer.Decision == "" {
		return nil, errors.New("decision cannot be empty")
	}
	if len(answer.Data) > 0 && !json.Valid(answer.Data) {
		return nil, errors.New("data is not valid JSON")
	}
	body, _ := json.Marshal(answer) // strings, a valid raw message and a number always encode
	return c.sendInput("POST", apiPath("/api/executions/%s/inputs/%s/answer", executionID, requestID), body)
}

// EscalateInput hands an input request up to the next tier.
func (c *Client) EscalateInput(executionID, requestID, reason, advice string) (*InputRequest, error) {
	if err := requireInputIDs(executionID, requestID); err != nil {
		return nil, err
	}
	if reason == "" {
		return nil, errors.New("reason cannot be empty")
	}
	body, _ := json.Marshal(struct {
		Reason string `json:"reason"`
		Advice string `json:"advice,omitempty"`
	}{reason, advice}) // two strings always encode
	return c.sendInput("POST", apiPath("/api/executions/%s/inputs/%s/escalate", executionID, requestID), body)
}

// sendInput sends a request and reads the input request in the "data" of the answer.
func (c *Client) sendInput(method, path string, body []byte) (*InputRequest, error) {
	req, err := c.newRequestWithBody(method, path, body)
	if err != nil {
		return nil, err
	}
	data, err := c.doRequest(req)
	if err != nil {
		return nil, err
	}
	var response struct {
		Data InputRequest `json:"data"`
	}
	if err := json.Unmarshal(data, &response); err != nil {
		return nil, fmt.Errorf("failed to unmarshal response: %w", err)
	}
	return &response.Data, nil
}
