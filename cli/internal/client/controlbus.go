// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"encoding/json"
	"fmt"
	"net/url"
)

// nodeQuery is the query of a node-level call: the session always, the run when given.
func nodeQuery(sessionID, executionID string) string {
	q := url.Values{"sessionId": {sessionID}}
	if executionID != "" {
		q.Set("executionId", executionID)
	}
	return "?" + q.Encode()
}

// GetActiveNodesInWorkflow retrieves all active nodes in a workflow of a session.
func (c *Client) GetActiveNodesInWorkflow(sessionID, workflowID string) ([]string, error) {
	if sessionID == "" {
		return nil, fmt.Errorf("sessionID cannot be empty")
	}
	if workflowID == "" {
		return nil, fmt.Errorf("workflowID cannot be empty")
	}

	path := apiPath("/api/control/workflows/%s/nodes", workflowID) + nodeQuery(sessionID, "")
	req, err := c.newRequest("GET", path)
	if err != nil {
		return nil, err
	}

	body, err := c.doRequest(req)
	if err != nil {
		return nil, err
	}

	// Parse the response: {"data": [...]}
	var response struct {
		Data []string `json:"data"`
	}

	if err := json.Unmarshal(body, &response); err != nil {
		return nil, fmt.Errorf("failed to unmarshal response: %w", err)
	}

	return response.Data, nil
}

// GetAllActiveNodes retrieves all active nodes across all workflows.
func (c *Client) GetAllActiveNodes() ([]string, error) {
	req, err := c.newRequest("GET", "/api/control/nodes")
	if err != nil {
		return nil, err
	}

	body, err := c.doRequest(req)
	if err != nil {
		return nil, err
	}

	// Parse the response: {"data": [...]}
	var response struct {
		Data []string `json:"data"`
	}

	if err := json.Unmarshal(body, &response); err != nil {
		return nil, fmt.Errorf("failed to unmarshal response: %w", err)
	}

	return response.Data, nil
}

// GetLastHeartbeat retrieves the last heartbeat for a specific node in a workflow.
// The response is a generic map since Message<?> is dynamically typed on the Java side.
// executionID may be empty when the session has one live run of the workflow.
func (c *Client) GetLastHeartbeat(sessionID, workflowID, executionID, nodeID string) (map[string]interface{}, error) {
	if sessionID == "" {
		return nil, fmt.Errorf("sessionID cannot be empty")
	}
	if workflowID == "" {
		return nil, fmt.Errorf("workflowID cannot be empty")
	}
	if nodeID == "" {
		return nil, fmt.Errorf("nodeID cannot be empty")
	}

	path := apiPath("/api/control/workflows/%s/nodes/%s/heartbeat", workflowID, nodeID) + nodeQuery(sessionID, executionID)
	req, err := c.newRequest("GET", path)
	if err != nil {
		return nil, err
	}

	body, err := c.doRequest(req)
	if err != nil {
		return nil, err
	}

	// Parse the response: {"data": {...}}
	var response struct {
		Data map[string]interface{} `json:"data"`
	}

	if err := json.Unmarshal(body, &response); err != nil {
		return nil, fmt.Errorf("failed to unmarshal response: %w", err)
	}

	return response.Data, nil
}

// SendCommand sends a command to a specific node in a workflow.
// The command payload is provided as raw JSON bytes.
// The response is a generic map since Message<?> is dynamically typed on the Java side.
// executionID may be empty when the session has one live run of the workflow.
func (c *Client) SendCommand(sessionID, workflowID, executionID, nodeID string, commandPayloadJSON []byte) (map[string]interface{}, error) {
	if sessionID == "" {
		return nil, fmt.Errorf("sessionID cannot be empty")
	}
	if workflowID == "" {
		return nil, fmt.Errorf("workflowID cannot be empty")
	}
	if nodeID == "" {
		return nil, fmt.Errorf("nodeID cannot be empty")
	}
	if len(commandPayloadJSON) == 0 {
		return nil, fmt.Errorf("commandPayloadJSON cannot be empty")
	}

	path := apiPath("/api/control/workflows/%s/nodes/%s/command", workflowID, nodeID) + nodeQuery(sessionID, executionID)
	req, err := c.newRequestWithBody("POST", path, commandPayloadJSON)
	if err != nil {
		return nil, err
	}

	body, err := c.doRequest(req)
	if err != nil {
		return nil, err
	}

	// Parse the response: {"data": {...}}
	var response struct {
		Data map[string]interface{} `json:"data"`
	}

	if err := json.Unmarshal(body, &response); err != nil {
		return nil, fmt.Errorf("failed to unmarshal response: %w", err)
	}

	return response.Data, nil
}
