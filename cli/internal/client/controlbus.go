// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"encoding/json"
	"fmt"
)

// GetActiveNodesInWorkflow retrieves all active nodes in a specific workflow.
func (c *Client) GetActiveNodesInWorkflow(workflowID string) ([]string, error) {
	if workflowID == "" {
		return nil, fmt.Errorf("workflowID cannot be empty")
	}

	path := fmt.Sprintf("/api/control/workflows/%s/nodes", workflowID)
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
func (c *Client) GetLastHeartbeat(workflowID, nodeID string) (map[string]interface{}, error) {
	if workflowID == "" {
		return nil, fmt.Errorf("workflowID cannot be empty")
	}
	if nodeID == "" {
		return nil, fmt.Errorf("nodeID cannot be empty")
	}

	path := fmt.Sprintf("/api/control/workflows/%s/nodes/%s/heartbeat", workflowID, nodeID)
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
func (c *Client) SendCommand(workflowID, nodeID string, commandPayloadJSON []byte) (map[string]interface{}, error) {
	if workflowID == "" {
		return nil, fmt.Errorf("workflowID cannot be empty")
	}
	if nodeID == "" {
		return nil, fmt.Errorf("nodeID cannot be empty")
	}
	if len(commandPayloadJSON) == 0 {
		return nil, fmt.Errorf("commandPayloadJSON cannot be empty")
	}

	path := fmt.Sprintf("/api/control/workflows/%s/nodes/%s/command", workflowID, nodeID)
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
