// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/url"
	"strconv"
	"strings"
	"time"
)

// timestampLayout is the zone-less layout older servers emit for java.time.LocalDateTime values.
const timestampLayout = "2006-01-02T15:04:05.999999999"

// Timestamp is a time.Time that also accepts the zone-less timestamps older servers return.
//
// Current servers send an RFC 3339 timestamp with their UTC offset, which is an exact instant. An
// older server sends the clock reading of its own time zone with no offset, so the zone is unknown:
// the CLI does not guess one (its own zone is no better a guess). Such a value keeps its clock
// reading, is shown without a zone and is written back out unchanged, and the difference between
// two of them is still right.
type Timestamp struct {
	time.Time
	zoneless bool
}

// UnmarshalJSON parses RFC 3339 timestamps, or zone-less ones.
func (t *Timestamp) UnmarshalJSON(data []byte) error {
	var raw *string
	if err := json.Unmarshal(data, &raw); err != nil {
		return err
	}
	if raw == nil || *raw == "" {
		*t = Timestamp{}
		return nil
	}
	if parsed, err := time.Parse(time.RFC3339Nano, *raw); err == nil {
		*t = Timestamp{Time: parsed}
		return nil
	}
	parsed, err := time.ParseInLocation(timestampLayout, *raw, time.UTC)
	if err != nil {
		return err
	}
	*t = Timestamp{Time: parsed, zoneless: true}
	return nil
}

// MarshalJSON writes the timestamp as it was read: RFC 3339, or the zone-less text of an older
// server.
func (t Timestamp) MarshalJSON() ([]byte, error) {
	if t.IsZero() { // not set: null, not a real-looking year 1
		return []byte("null"), nil
	}
	if t.zoneless {
		return json.Marshal(t.Format(timestampLayout))
	}
	return json.Marshal(t.Time)
}

// String shows a time for people: the clock reading with the server's offset (or none, if the
// server sent none), and "-" for a time that is not set.
func (t Timestamp) String() string {
	switch {
	case t.IsZero():
		return "-"
	case t.zoneless:
		return t.Format("2006-01-02 15:04:05")
	default:
		return t.Format("2006-01-02 15:04:05 -07:00")
	}
}

// WorkflowStartRequest is the request body for starting a workflow execution.
type WorkflowStartRequest struct {
	SessionID  string         `json:"sessionId"`
	WorkflowID string         `json:"workflowId"`
	Payload    map[string]any `json:"payload,omitempty"`
}

// WorkflowStartResponse contains the execution ID of a started workflow.
type WorkflowStartResponse struct {
	ExecutionID string `json:"executionId"`
}

// WorkflowStopResponse contains the list of execution IDs that were stopped.
type WorkflowStopResponse struct {
	ExecutionIDs []string `json:"executionIds"`
}

// TaskProgress represents the progress of a single task/node in a workflow execution.
type TaskProgress struct {
	NodeID    string                 `json:"nodeId"`
	Module    string                 `json:"module"`
	Status    string                 `json:"status"`
	StartTime Timestamp              `json:"startTime"`
	EndTime   Timestamp              `json:"endTime"`
	Metadata  map[string]interface{} `json:"metadata"`
	// Breakpoint is the active breakpoint on the node, nil when there is none.
	Breakpoint *Breakpoint `json:"breakpoint,omitempty"`
}

// Breakpoint pauses a node when the value at Field equals Equals.
type Breakpoint struct {
	Field  string `json:"field"`
	Equals string `json:"equals"`
}

// WorkflowProgress represents the current progress of a workflow execution.
type WorkflowProgress struct {
	ExecutionID string         `json:"executionId"`
	SessionID   string         `json:"sessionId"`
	WorkflowID  string         `json:"workflowId"`
	Status      string         `json:"status"`
	Tasks       []TaskProgress `json:"tasks"`
	StartTime   Timestamp      `json:"startTime"`
	EndTime     Timestamp      `json:"endTime"`
	// ParentExecutionID is the run that started this one as a sub-workflow, if any.
	ParentExecutionID string `json:"parentExecutionId,omitempty"`
	// RootExecutionID is the top of this run's tree of runs (itself when it has no parent).
	RootExecutionID string `json:"rootExecutionId,omitempty"`
	// RestartOf is the run this one restarts, if any.
	RestartOf string `json:"restartOf,omitempty"`
}

// WorkflowExecutionSummary is a brief summary of a workflow execution.
type WorkflowExecutionSummary struct {
	ExecutionID       string    `json:"executionId"`
	WorkflowID        string    `json:"workflowId"`
	Status            string    `json:"status"`
	StartTime         Timestamp `json:"startTime"`
	EndTime           Timestamp `json:"endTime"`
	ParentExecutionID string    `json:"parentExecutionId,omitempty"`
	RootExecutionID   string    `json:"rootExecutionId,omitempty"`
	RestartOf         string    `json:"restartOf,omitempty"`
}

// StartWorkflow initiates execution of a workflow in a session.
// payload is optional and handed to the workflow's trigger node.
func (c *Client) StartWorkflow(sessionID, workflowID string, payload map[string]any) (WorkflowStartResponse, error) {
	return c.StartWorkflowKeyed(sessionID, workflowID, payload, "")
}

// StartWorkflowKeyed is StartWorkflow with an Idempotency-Key: the server returns the first run's
// execution id for a repeat of the same key, so a start that timed out can be sent again safely.
// An empty key sends none.
func (c *Client) StartWorkflowKeyed(sessionID, workflowID string, payload map[string]any, idempotencyKey string) (WorkflowStartResponse, error) {
	if sessionID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("sessionID cannot be empty")
	}
	if workflowID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("workflowID cannot be empty")
	}

	req := WorkflowStartRequest{SessionID: sessionID, WorkflowID: workflowID, Payload: payload}
	reqBody, _ := json.Marshal(req)

	httpReq, err := c.newRequestWithBody("POST", "/api/workflow/start", reqBody)
	if err != nil {
		return WorkflowStartResponse{}, err
	}

	if idempotencyKey == "" && c.Retries > 0 {
		// a retried start must not start twice
		idempotencyKey = newKey()
	}
	if idempotencyKey != "" {
		httpReq.Header.Set("Idempotency-Key", idempotencyKey)
	}

	body, err := c.doRequest(httpReq)
	if err != nil {
		return WorkflowStartResponse{}, err
	}

	var response struct {
		Data WorkflowStartResponse `json:"data"`
	}

	if err := json.Unmarshal(body, &response); err != nil {
		return WorkflowStartResponse{}, fmt.Errorf("failed to unmarshal response: %w", err)
	}

	return response.Data, nil
}

// StopWorkflow stops all active executions of a workflow for a given session. mode is DRAIN, CANCEL
// or KILL (case-insensitive); empty leaves the choice to the server (DRAIN).
func (c *Client) StopWorkflow(sessionID, workflowID, mode string) (WorkflowStopResponse, error) {
	if sessionID == "" {
		return WorkflowStopResponse{}, fmt.Errorf("sessionID cannot be empty")
	}
	if workflowID == "" {
		return WorkflowStopResponse{}, fmt.Errorf("workflowID cannot be empty")
	}

	path := apiPath("/api/workflow/%s/%s/stop", sessionID, workflowID)
	if mode != "" {
		path += "?mode=" + url.QueryEscape(strings.ToUpper(mode))
	}
	httpReq, err := c.newRequest("POST", path)
	if err != nil {
		return WorkflowStopResponse{}, err
	}

	body, err := c.doRequest(httpReq)
	if err != nil {
		return WorkflowStopResponse{}, err
	}

	var response struct {
		Data WorkflowStopResponse `json:"data"`
	}

	if err := json.Unmarshal(body, &response); err != nil {
		return WorkflowStopResponse{}, fmt.Errorf("failed to unmarshal response: %w", err)
	}

	return response.Data, nil
}

// StopExecution stops a specific workflow execution.
func (c *Client) StopExecution(executionID string) (WorkflowStartResponse, error) {
	return c.StopExecutionTree(executionID, false)
}

// StopExecutionTree stops a workflow execution and, with tree, every unfinished run it started as a
// sub-workflow, at any depth.
func (c *Client) StopExecutionTree(executionID string, tree bool) (WorkflowStartResponse, error) {
	return c.StopExecutionMode(executionID, tree, "")
}

// StopExecutionMode stops a workflow execution the given way: DRAIN lets work in flight finish,
// CANCEL cancels it, KILL ends the run at once. An empty mode leaves the choice to the server (DRAIN).
func (c *Client) StopExecutionMode(executionID string, tree bool, mode string) (WorkflowStartResponse, error) {
	if executionID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("executionID cannot be empty")
	}

	path := apiPath("/api/executions/%s/stop", executionID)
	params := url.Values{}
	if tree {
		params.Set("tree", "true")
	}
	if mode != "" {
		params.Set("mode", mode)
	}
	if len(params) > 0 {
		path += "?" + params.Encode()
	}
	httpReq, err := c.newRequest("POST", path)
	if err != nil {
		return WorkflowStartResponse{}, err
	}

	body, err := c.doRequest(httpReq)
	if err != nil {
		return WorkflowStartResponse{}, err
	}

	var response struct {
		Data WorkflowStartResponse `json:"data"`
	}

	if err := json.Unmarshal(body, &response); err != nil {
		return WorkflowStartResponse{}, fmt.Errorf("failed to unmarshal response: %w", err)
	}

	return response.Data, nil
}

// RestartExecution restarts a workflow execution from the beginning.
func (c *Client) RestartExecution(executionID string) (WorkflowStartResponse, error) {
	if executionID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("executionID cannot be empty")
	}

	path := apiPath("/api/executions/%s/restart", executionID)
	httpReq, err := c.newRequest("POST", path)
	if err != nil {
		return WorkflowStartResponse{}, err
	}

	body, err := c.doRequest(httpReq)
	if err != nil {
		return WorkflowStartResponse{}, err
	}

	var response struct {
		Data WorkflowStartResponse `json:"data"`
	}

	if err := json.Unmarshal(body, &response); err != nil {
		return WorkflowStartResponse{}, fmt.Errorf("failed to unmarshal response: %w", err)
	}

	return response.Data, nil
}

// RestartFromNode restarts a workflow execution from a specific node.
func (c *Client) RestartFromNode(executionID, fromNodeID string) (WorkflowStartResponse, error) {
	if executionID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("executionID cannot be empty")
	}
	if fromNodeID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("fromNodeID cannot be empty")
	}

	path := apiPath("/api/executions/%s/restart/%s", executionID, fromNodeID)
	httpReq, err := c.newRequest("POST", path)
	if err != nil {
		return WorkflowStartResponse{}, err
	}

	body, err := c.doRequest(httpReq)
	if err != nil {
		return WorkflowStartResponse{}, err
	}

	var response struct {
		Data WorkflowStartResponse `json:"data"`
	}

	if err := json.Unmarshal(body, &response); err != nil {
		return WorkflowStartResponse{}, fmt.Errorf("failed to unmarshal response: %w", err)
	}

	return response.Data, nil
}

// PauseWorkflow pauses a workflow execution globally.
func (c *Client) PauseWorkflow(sessionID, executionID string) (WorkflowStartResponse, error) {
	return c.PauseWorkflowTree(sessionID, executionID, false)
}

// PauseWorkflowTree pauses a workflow execution and, with tree, every unfinished run it started as
// a sub-workflow, at any depth.
func (c *Client) PauseWorkflowTree(sessionID, executionID string, tree bool) (WorkflowStartResponse, error) {
	return c.executionSignal("pause", sessionID, executionID, tree)
}

// ResumeWorkflow resumes a paused workflow execution.
func (c *Client) ResumeWorkflow(sessionID, executionID string) (WorkflowStartResponse, error) {
	return c.ResumeWorkflowTree(sessionID, executionID, false)
}

// ResumeWorkflowTree resumes a paused workflow execution and, with tree, every unfinished run it
// started as a sub-workflow.
func (c *Client) ResumeWorkflowTree(sessionID, executionID string, tree bool) (WorkflowStartResponse, error) {
	return c.executionSignal("resume", sessionID, executionID, tree)
}

// executionSignal sends POST /api/executions/{id}/{verb}, on the whole tree with tree.
func (c *Client) executionSignal(verb, sessionID, executionID string, tree bool) (WorkflowStartResponse, error) {
	if sessionID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("sessionID cannot be empty")
	}
	if executionID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("executionID cannot be empty")
	}

	path := apiPath("/api/executions/%s/"+verb, executionID)
	if tree {
		path += "?tree=true"
	}
	httpReq, err := c.newRequest("POST", path)
	if err != nil {
		return WorkflowStartResponse{}, err
	}

	if err := c.checkExecutionSession(sessionID, executionID); err != nil {
		return WorkflowStartResponse{}, err
	}

	body, err := c.doRequest(httpReq)
	if err != nil {
		return WorkflowStartResponse{}, err
	}

	var response struct {
		Data WorkflowStartResponse `json:"data"`
	}

	if err := json.Unmarshal(body, &response); err != nil {
		return WorkflowStartResponse{}, fmt.Errorf("failed to unmarshal response: %w", err)
	}

	return response.Data, nil
}

// GetWorkflowStatus retrieves the current status and progress of a workflow execution.
func (c *Client) GetWorkflowStatus(sessionID, executionID string) (WorkflowProgress, error) {
	if sessionID == "" {
		return WorkflowProgress{}, fmt.Errorf("sessionID cannot be empty")
	}
	if executionID == "" {
		return WorkflowProgress{}, fmt.Errorf("executionID cannot be empty")
	}

	path := apiPath("/api/executions/%s", executionID)
	httpReq, err := c.newRequest("GET", path)
	if err != nil {
		return WorkflowProgress{}, err
	}

	body, err := c.doRequest(httpReq)
	if err != nil {
		return WorkflowProgress{}, err
	}

	var response struct {
		Data WorkflowProgress `json:"data"`
	}

	if err := json.Unmarshal(body, &response); err != nil {
		return WorkflowProgress{}, fmt.Errorf("failed to unmarshal response: %w", err)
	}

	// /api/executions/{id} does not look at a session, so the check the old path made on the
	// server is made here: a run of another session is reported as not found.
	if response.Data.SessionID != "" && response.Data.SessionID != sessionID {
		return WorkflowProgress{}, fmt.Errorf("execution %q not found in session %q", executionID, sessionID)
	}

	return response.Data, nil
}

// GetExecutionTree lists an execution and every run it started as a sub-workflow, at any depth,
// oldest first.
func (c *Client) GetExecutionTree(executionID string) ([]WorkflowExecutionSummary, error) {
	if executionID == "" {
		return nil, fmt.Errorf("executionID cannot be empty")
	}

	return getAll[WorkflowExecutionSummary](c, apiPath("/api/executions/%s/tree", executionID))
}

// GetWorkflowHistory retrieves the execution history of all workflows for a session.
func (c *Client) GetWorkflowHistory(sessionID string) ([]WorkflowExecutionSummary, error) {
	if sessionID == "" {
		return nil, fmt.Errorf("sessionID cannot be empty")
	}

	return getAll[WorkflowExecutionSummary](c, apiPath("/api/workflow/%s/history", sessionID))
}

// PauseNode pauses a specific node in a workflow execution.
func (c *Client) PauseNode(sessionID, executionID, nodeID string) (WorkflowStartResponse, error) {
	if sessionID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("sessionID cannot be empty")
	}
	if executionID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("executionID cannot be empty")
	}
	if nodeID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("nodeID cannot be empty")
	}

	path := apiPath("/api/executions/%s/nodes/%s/pause", executionID, nodeID)
	httpReq, err := c.newRequest("POST", path)
	if err != nil {
		return WorkflowStartResponse{}, err
	}

	if err := c.checkExecutionSession(sessionID, executionID); err != nil {
		return WorkflowStartResponse{}, err
	}

	body, err := c.doRequest(httpReq)
	if err != nil {
		return WorkflowStartResponse{}, err
	}

	var response struct {
		Data WorkflowStartResponse `json:"data"`
	}

	if err := json.Unmarshal(body, &response); err != nil {
		return WorkflowStartResponse{}, fmt.Errorf("failed to unmarshal response: %w", err)
	}

	return response.Data, nil
}

// ResumeNode resumes a paused node in a workflow execution.
func (c *Client) ResumeNode(sessionID, executionID, nodeID string) (WorkflowStartResponse, error) {
	if sessionID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("sessionID cannot be empty")
	}
	if executionID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("executionID cannot be empty")
	}
	if nodeID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("nodeID cannot be empty")
	}

	path := apiPath("/api/executions/%s/nodes/%s/resume", executionID, nodeID)
	httpReq, err := c.newRequest("POST", path)
	if err != nil {
		return WorkflowStartResponse{}, err
	}

	if err := c.checkExecutionSession(sessionID, executionID); err != nil {
		return WorkflowStartResponse{}, err
	}

	body, err := c.doRequest(httpReq)
	if err != nil {
		return WorkflowStartResponse{}, err
	}

	var response struct {
		Data WorkflowStartResponse `json:"data"`
	}

	if err := json.Unmarshal(body, &response); err != nil {
		return WorkflowStartResponse{}, fmt.Errorf("failed to unmarshal response: %w", err)
	}

	return response.Data, nil
}

// EnableStepMode enables step-through debug mode on a node.
func (c *Client) EnableStepMode(sessionID, executionID, nodeID string) (WorkflowStartResponse, error) {
	if sessionID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("sessionID cannot be empty")
	}
	if executionID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("executionID cannot be empty")
	}
	if nodeID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("nodeID cannot be empty")
	}

	path := apiPath("/api/executions/%s/nodes/%s/step/enable", executionID, nodeID)
	httpReq, err := c.newRequest("POST", path)
	if err != nil {
		return WorkflowStartResponse{}, err
	}

	if err := c.checkExecutionSession(sessionID, executionID); err != nil {
		return WorkflowStartResponse{}, err
	}

	body, err := c.doRequest(httpReq)
	if err != nil {
		return WorkflowStartResponse{}, err
	}

	var response struct {
		Data WorkflowStartResponse `json:"data"`
	}

	if err := json.Unmarshal(body, &response); err != nil {
		return WorkflowStartResponse{}, fmt.Errorf("failed to unmarshal response: %w", err)
	}

	return response.Data, nil
}

// DisableStepMode disables step-through debug mode on a node.
func (c *Client) DisableStepMode(sessionID, executionID, nodeID string) (WorkflowStartResponse, error) {
	if sessionID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("sessionID cannot be empty")
	}
	if executionID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("executionID cannot be empty")
	}
	if nodeID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("nodeID cannot be empty")
	}

	path := apiPath("/api/executions/%s/nodes/%s/step/disable", executionID, nodeID)
	httpReq, err := c.newRequest("POST", path)
	if err != nil {
		return WorkflowStartResponse{}, err
	}

	if err := c.checkExecutionSession(sessionID, executionID); err != nil {
		return WorkflowStartResponse{}, err
	}

	body, err := c.doRequest(httpReq)
	if err != nil {
		return WorkflowStartResponse{}, err
	}

	var response struct {
		Data WorkflowStartResponse `json:"data"`
	}

	if err := json.Unmarshal(body, &response); err != nil {
		return WorkflowStartResponse{}, fmt.Errorf("failed to unmarshal response: %w", err)
	}

	return response.Data, nil
}

// StepNode allows one element to pass through a node in step-through mode.
func (c *Client) StepNode(sessionID, executionID, nodeID string) (WorkflowStartResponse, error) {
	if sessionID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("sessionID cannot be empty")
	}
	if executionID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("executionID cannot be empty")
	}
	if nodeID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("nodeID cannot be empty")
	}

	path := apiPath("/api/executions/%s/nodes/%s/step", executionID, nodeID)
	httpReq, err := c.newRequest("POST", path)
	if err != nil {
		return WorkflowStartResponse{}, err
	}

	if err := c.checkExecutionSession(sessionID, executionID); err != nil {
		return WorkflowStartResponse{}, err
	}

	body, err := c.doRequest(httpReq)
	if err != nil {
		return WorkflowStartResponse{}, err
	}

	var response struct {
		Data WorkflowStartResponse `json:"data"`
	}

	if err := json.Unmarshal(body, &response); err != nil {
		return WorkflowStartResponse{}, fmt.Errorf("failed to unmarshal response: %w", err)
	}

	return response.Data, nil
}

// StopNode stops a specific node with optional immediate/reason parameters.
func (c *Client) StopNode(sessionID, executionID, nodeID string, immediate bool, reason string) (WorkflowStartResponse, error) {
	if sessionID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("sessionID cannot be empty")
	}
	if executionID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("executionID cannot be empty")
	}
	if nodeID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("nodeID cannot be empty")
	}

	params := url.Values{}
	params.Set("immediate", strconv.FormatBool(immediate))
	if reason != "" { // absent, the server records its own default reason
		params.Set("reason", reason)
	}

	path := apiPath("/api/executions/%s/nodes/%s/stop", executionID, nodeID) + "?" + params.Encode()
	httpReq, err := c.newRequest("POST", path)
	if err != nil {
		return WorkflowStartResponse{}, err
	}

	if err := c.checkExecutionSession(sessionID, executionID); err != nil {
		return WorkflowStartResponse{}, err
	}

	body, err := c.doRequest(httpReq)
	if err != nil {
		return WorkflowStartResponse{}, err
	}

	var response struct {
		Data WorkflowStartResponse `json:"data"`
	}

	if err := json.Unmarshal(body, &response); err != nil {
		return WorkflowStartResponse{}, fmt.Errorf("failed to unmarshal response: %w", err)
	}

	return response.Data, nil
}

// SkipNode marks a node as skipped or unskips it based on the skip parameter.
func (c *Client) SkipNode(sessionID, executionID, nodeID string, skip bool) (WorkflowStartResponse, error) {
	if sessionID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("sessionID cannot be empty")
	}
	if executionID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("executionID cannot be empty")
	}
	if nodeID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("nodeID cannot be empty")
	}

	params := url.Values{}
	params.Set("skip", strconv.FormatBool(skip))

	path := apiPath("/api/executions/%s/nodes/%s/skip", executionID, nodeID) + "?" + params.Encode()
	httpReq, err := c.newRequest("POST", path)
	if err != nil {
		return WorkflowStartResponse{}, err
	}

	if err := c.checkExecutionSession(sessionID, executionID); err != nil {
		return WorkflowStartResponse{}, err
	}

	body, err := c.doRequest(httpReq)
	if err != nil {
		return WorkflowStartResponse{}, err
	}

	var response struct {
		Data WorkflowStartResponse `json:"data"`
	}

	if err := json.Unmarshal(body, &response); err != nil {
		return WorkflowStartResponse{}, fmt.Errorf("failed to unmarshal response: %w", err)
	}

	return response.Data, nil
}

// StreamWorkflowStatus streams the progress of a workflow execution via SSE.
func (c *Client) StreamWorkflowStatus(
	ctx context.Context,
	sessionID, executionID string,
	includeHistory bool,
	onProgress func(WorkflowProgress) error,
) error {
	if sessionID == "" {
		return fmt.Errorf("sessionID cannot be empty")
	}
	if executionID == "" {
		return fmt.Errorf("executionID cannot be empty")
	}
	if onProgress == nil {
		return fmt.Errorf("onProgress callback cannot be nil")
	}

	if err := c.checkExecutionSession(sessionID, executionID); err != nil {
		return err
	}

	params := url.Values{}
	params.Set("includeHistory", strconv.FormatBool(includeHistory))

	path := apiPath("/api/executions/%s/status/stream", executionID) + "?" + params.Encode()

	return c.followSSE(ctx, path, c.executionDone(executionID), func(event SSEEvent) error {
		var progress WorkflowProgress
		if err := json.Unmarshal([]byte(event.Data), &progress); err != nil {
			return fmt.Errorf("failed to unmarshal progress: %w", err)
		}
		return onProgress(progress)
	})
}

// checkExecutionSession refuses to act on an execution that belongs to another session: the
// /api/executions routes take no session, so the check is made here (as GetWorkflowStatus does).
func (c *Client) checkExecutionSession(sessionID, executionID string) error {
	_, err := c.GetWorkflowStatus(sessionID, executionID)
	return err
}

// newKey returns a random Idempotency-Key.
func newKey() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b) // crypto/rand.Read never fails on supported platforms
	return hex.EncodeToString(b)
}
