// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"context"
	"encoding/json"
	"fmt"
	"net/url"
	"strconv"
	"time"
)

// WorkflowStartRequest is the request body for starting a workflow execution.
type WorkflowStartRequest struct {
	SessionID  string `json:"sessionId"`
	WorkflowID string `json:"workflowId"`
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
	StartTime time.Time              `json:"startTime"`
	EndTime   time.Time              `json:"endTime"`
	Metadata  map[string]interface{} `json:"metadata"`
}

// WorkflowProgress represents the current progress of a workflow execution.
type WorkflowProgress struct {
	ExecutionID string         `json:"executionId"`
	SessionID   string         `json:"sessionId"`
	WorkflowID  string         `json:"workflowId"`
	Status      string         `json:"status"`
	Tasks       []TaskProgress `json:"tasks"`
	StartTime   time.Time      `json:"startTime"`
	EndTime     time.Time      `json:"endTime"`
}

// WorkflowExecutionSummary is a brief summary of a workflow execution.
type WorkflowExecutionSummary struct {
	ExecutionID string    `json:"executionId"`
	WorkflowID  string    `json:"workflowId"`
	Status      string    `json:"status"`
	StartTime   time.Time `json:"startTime"`
	EndTime     time.Time `json:"endTime"`
}

// StartWorkflow initiates execution of a workflow in a session.
func (c *Client) StartWorkflow(sessionID, workflowID string) (WorkflowStartResponse, error) {
	if sessionID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("sessionID cannot be empty")
	}
	if workflowID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("workflowID cannot be empty")
	}

	req := WorkflowStartRequest{SessionID: sessionID, WorkflowID: workflowID}
	reqBody, _ := json.Marshal(req)

	httpReq, err := c.newRequestWithBody("POST", "/api/workflow/start", reqBody)
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

// StopWorkflow stops all active executions of a workflow for a given session.
func (c *Client) StopWorkflow(sessionID, workflowID string) (WorkflowStopResponse, error) {
	if sessionID == "" {
		return WorkflowStopResponse{}, fmt.Errorf("sessionID cannot be empty")
	}
	if workflowID == "" {
		return WorkflowStopResponse{}, fmt.Errorf("workflowID cannot be empty")
	}

	path := fmt.Sprintf("/api/workflow/%s/%s/stop", sessionID, workflowID)
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
	if executionID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("executionID cannot be empty")
	}

	path := fmt.Sprintf("/api/workflow/executions/%s/stop", executionID)
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

	path := fmt.Sprintf("/api/workflow/executions/%s/restart", executionID)
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

	path := fmt.Sprintf("/api/workflow/executions/%s/restart/%s", executionID, fromNodeID)
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
	if sessionID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("sessionID cannot be empty")
	}
	if executionID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("executionID cannot be empty")
	}

	path := fmt.Sprintf("/api/workflow/%s/%s/pause", sessionID, executionID)
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

// ResumeWorkflow resumes a paused workflow execution.
func (c *Client) ResumeWorkflow(sessionID, executionID string) (WorkflowStartResponse, error) {
	if sessionID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("sessionID cannot be empty")
	}
	if executionID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("executionID cannot be empty")
	}

	path := fmt.Sprintf("/api/workflow/%s/%s/resume", sessionID, executionID)
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

// GetWorkflowStatus retrieves the current status and progress of a workflow execution.
func (c *Client) GetWorkflowStatus(sessionID, executionID string) (WorkflowProgress, error) {
	if sessionID == "" {
		return WorkflowProgress{}, fmt.Errorf("sessionID cannot be empty")
	}
	if executionID == "" {
		return WorkflowProgress{}, fmt.Errorf("executionID cannot be empty")
	}

	path := fmt.Sprintf("/api/workflow/%s/status/%s", sessionID, executionID)
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

	return response.Data, nil
}

// GetWorkflowHistory retrieves the execution history of all workflows for a session.
func (c *Client) GetWorkflowHistory(sessionID string) ([]WorkflowExecutionSummary, error) {
	if sessionID == "" {
		return nil, fmt.Errorf("sessionID cannot be empty")
	}

	path := fmt.Sprintf("/api/workflow/%s/history", sessionID)
	httpReq, err := c.newRequest("GET", path)
	if err != nil {
		return nil, err
	}

	body, err := c.doRequest(httpReq)
	if err != nil {
		return nil, err
	}

	var response struct {
		Data []WorkflowExecutionSummary `json:"data"`
	}

	if err := json.Unmarshal(body, &response); err != nil {
		return nil, fmt.Errorf("failed to unmarshal response: %w", err)
	}

	return response.Data, nil
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

	path := fmt.Sprintf("/api/workflow/%s/%s/node/%s/pause", sessionID, executionID, nodeID)
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

	path := fmt.Sprintf("/api/workflow/%s/%s/node/%s/resume", sessionID, executionID, nodeID)
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

	path := fmt.Sprintf("/api/workflow/%s/%s/node/%s/step/enable", sessionID, executionID, nodeID)
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

	path := fmt.Sprintf("/api/workflow/%s/%s/node/%s/step/disable", sessionID, executionID, nodeID)
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

	path := fmt.Sprintf("/api/workflow/%s/%s/node/%s/step", sessionID, executionID, nodeID)
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
	params.Set("reason", reason)

	path := fmt.Sprintf("/api/workflow/%s/%s/node/%s/stop?%s", sessionID, executionID, nodeID, params.Encode())
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

	path := fmt.Sprintf("/api/workflow/%s/%s/node/%s/skip?%s", sessionID, executionID, nodeID, params.Encode())
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

	params := url.Values{}
	params.Set("includeHistory", strconv.FormatBool(includeHistory))

	path := fmt.Sprintf("/api/workflow/%s/%s/status/stream?%s", sessionID, executionID, params.Encode())

	resp, err := c.streamRequest(ctx, path)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	return ScanSSE(ctx, resp.Body, func(event SSEEvent) error {
		var progress WorkflowProgress
		if err := json.Unmarshal([]byte(event.Data), &progress); err != nil {
			return fmt.Errorf("failed to unmarshal progress: %w", err)
		}
		return onProgress(progress)
	})
}
