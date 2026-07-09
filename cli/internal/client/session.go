// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited


package client

import (
	"bytes"
	"encoding/json"
	"fmt"
)

// GetSessions retrieves all available session identifiers from the API.
// It makes a GET request to /api/sessions and extracts the sessionIds from the response data.
func (c *Client) GetSessions() ([]string, error) {
	req, err := c.newRequest("GET", "/api/sessions")
	if err != nil {
		return nil, err
	}

	body, err := c.doRequest(req)
	if err != nil {
		return nil, err
	}

	// Parse the response: {"data": {"sessionIds": [...]}}
	var response struct {
		Data struct {
			SessionIDs []string `json:"sessionIds"`
		} `json:"data"`
	}

	if err := json.Unmarshal(body, &response); err != nil {
		return nil, fmt.Errorf("failed to unmarshal response: %w", err)
	}

	return response.Data.SessionIDs, nil
}

// GetSessionDetails retrieves details of a specific session.
// It makes a GET request to /api/sessions/{sessionId} and returns the session data.
// The returned map contains sessionId and workflowIds keys.
func (c *Client) GetSessionDetails(sessionID string) (map[string]interface{}, error) {
	if sessionID == "" {
		return nil, fmt.Errorf("sessionID cannot be empty")
	}

	path := fmt.Sprintf("/api/sessions/%s", sessionID)
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

// GetWorkflow retrieves the definition of a specific workflow within a session.
// It makes a GET request to /api/sessions/{sessionId}/workflows/{workflowId}
// and returns the complete workflow definition as a map.
func (c *Client) GetWorkflow(sessionID, workflowID string) (map[string]interface{}, error) {
	if sessionID == "" {
		return nil, fmt.Errorf("sessionID cannot be empty")
	}

	if workflowID == "" {
		return nil, fmt.Errorf("workflowID cannot be empty")
	}

	path := fmt.Sprintf("/api/sessions/%s/workflows/%s", sessionID, workflowID)
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

// ApplyConfig applies a session configuration by sending a POST request to /api/sessions.
// It accepts JSON-serialized configuration data and returns an error if the request fails.
func (c *Client) ApplyConfig(configJSON []byte) error {
	if len(configJSON) == 0 {
		return fmt.Errorf("configJSON cannot be empty")
	}

	// Create request with body using the factory
	req, err := c.RequestFactory.NewRequest("POST", c.BaseURL+"/api/sessions", bytes.NewReader(configJSON))
	if err != nil {
		return fmt.Errorf("failed to create request with body: %w", err)
	}

	// Set headers
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")

	// Execute the request
	_, err = c.doRequest(req)
	return err
}
