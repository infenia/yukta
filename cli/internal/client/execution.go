// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"encoding/json"
	"fmt"
	"net/url"
	"strconv"
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

// NodeOutputQuery selects the slice of a node's output to read. A negative number means "not set".
type NodeOutputQuery struct {
	Stream  string // stdout, stderr or all (the default)
	Tail    int
	Head    int
	Offset  int
	Limit   int
	Grep    string
	Context int
}

// NodeOutputLine is one numbered line of a node's output.
type NodeOutputLine struct {
	Number int64  `json:"number"`
	Text   string `json:"text"`
}

// NodeOutput is a slice of the output a node wrote.
type NodeOutput struct {
	NodeID       string           `json:"nodeId"`
	Stream       string           `json:"stream"`
	TotalLines   int64            `json:"totalLines"`
	TotalBytes   int64            `json:"totalBytes"`
	MatchedLines int64            `json:"matchedLines"`
	Truncated    bool             `json:"truncated"`
	NextOffset   *int64           `json:"nextOffset"`
	Lines        []NodeOutputLine `json:"lines"`
}

// GetNodeOutput reads part of what one node wrote: the tail, the head, a page or the lines that
// match a pattern, as numbered lines.
func (c *Client) GetNodeOutput(executionID, nodeID string, q NodeOutputQuery) (NodeOutput, error) {
	if executionID == "" || nodeID == "" {
		return NodeOutput{}, fmt.Errorf("executionID and nodeID cannot be empty")
	}
	v := url.Values{}
	if q.Stream != "" {
		v.Set("stream", q.Stream)
	}
	for name, n := range map[string]int{"tail": q.Tail, "head": q.Head, "offset": q.Offset, "limit": q.Limit, "context": q.Context} {
		if n >= 0 {
			v.Set(name, strconv.Itoa(n))
		}
	}
	if q.Grep != "" {
		v.Set("grep", q.Grep)
	}
	path := apiPath("/api/executions/%s/nodes/%s/output", executionID, nodeID)
	if len(v) > 0 {
		path += "?" + v.Encode()
	}
	req, err := c.newRequest("GET", path)
	if err != nil {
		return NodeOutput{}, err
	}
	body, err := c.doRequest(req)
	if err != nil {
		return NodeOutput{}, err
	}
	var response struct {
		Data NodeOutput `json:"data"`
	}
	if err := json.Unmarshal(body, &response); err != nil {
		return NodeOutput{}, fmt.Errorf("failed to unmarshal response: %w", err)
	}
	return response.Data, nil
}
