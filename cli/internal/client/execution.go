// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"encoding/json"
	"fmt"
	"net/url"
)

// Calls on /api/executions/{id}/… : these routes need no session id.

// executionCall sends method to path and decodes the {data: WorkflowStartResponse} envelope.
func (c *Client) executionCall(method, path string) (WorkflowStartResponse, error) {
	req, err := c.newRequest(method, path)
	if err != nil {
		return WorkflowStartResponse{}, err
	}
	body, err := c.doRequest(req)
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

// GetExecution retrieves the status and per-node progress of an execution by its id alone.
func (c *Client) GetExecution(executionID string) (WorkflowProgress, error) {
	if executionID == "" {
		return WorkflowProgress{}, fmt.Errorf("executionID cannot be empty")
	}
	req, err := c.newRequest("GET", apiPath("/api/executions/%s", executionID))
	if err != nil {
		return WorkflowProgress{}, err
	}
	body, err := c.doRequest(req)
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

// RetryNode restarts an execution from a node that ended FAILURE or TIMED_OUT. The response
// carries the id of the new execution.
func (c *Client) RetryNode(executionID, nodeID string) (WorkflowStartResponse, error) {
	if executionID == "" || nodeID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("executionID and nodeID cannot be empty")
	}
	return c.executionCall("POST", apiPath("/api/executions/%s/nodes/%s/retry", executionID, nodeID))
}

// SignalNode hands a named signal (an approval or decision) to a node that waits for it.
// data is optional.
func (c *Client) SignalNode(executionID, nodeID, name, decision, data string) (WorkflowStartResponse, error) {
	if executionID == "" || nodeID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("executionID and nodeID cannot be empty")
	}
	if name == "" || decision == "" {
		return WorkflowStartResponse{}, fmt.Errorf("signal name and decision cannot be empty")
	}
	params := url.Values{"name": {name}, "decision": {decision}}
	if data != "" {
		params.Set("data", data)
	}
	return c.executionCall("POST",
		apiPath("/api/executions/%s/nodes/%s/signal", executionID, nodeID)+"?"+params.Encode())
}

// SetBreakpoint holds a node before the first element whose field equals the given text.
func (c *Client) SetBreakpoint(executionID, nodeID, field, equals string) (WorkflowStartResponse, error) {
	if executionID == "" || nodeID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("executionID and nodeID cannot be empty")
	}
	if field == "" {
		return WorkflowStartResponse{}, fmt.Errorf("field cannot be empty")
	}
	params := url.Values{"field": {field}, "equals": {equals}}
	return c.executionCall("POST",
		apiPath("/api/executions/%s/nodes/%s/breakpoint", executionID, nodeID)+"?"+params.Encode())
}

// ClearBreakpoint removes a node's breakpoint.
func (c *Client) ClearBreakpoint(executionID, nodeID string) (WorkflowStartResponse, error) {
	if executionID == "" || nodeID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("executionID and nodeID cannot be empty")
	}
	return c.executionCall("DELETE", apiPath("/api/executions/%s/nodes/%s/breakpoint", executionID, nodeID))
}
