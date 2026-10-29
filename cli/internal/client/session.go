// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"encoding/json"
	"fmt"
	"net/url"
)

// GetSessions retrieves all available session identifiers from the API.
// It makes a GET request to /api/sessions, following the next-page cursor, and returns the session IDs.
func (c *Client) GetSessions() ([]string, error) {
	return getAll[string](c, "/api/sessions")
}

// GetSessionDetails retrieves details of a specific session.
// It makes a GET request to /api/sessions/{sessionId} and returns the session data.
// The returned map contains sessionId and workflowIds keys.
func (c *Client) GetSessionDetails(sessionID string) (map[string]interface{}, error) {
	if sessionID == "" {
		return nil, fmt.Errorf("sessionID cannot be empty")
	}

	path := apiPath("/api/sessions/%s", sessionID)
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

	path := apiPath("/api/sessions/%s/workflows/%s", sessionID, workflowID)
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

	req, err := c.newRequestWithBody("POST", "/api/sessions", configJSON)
	if err != nil {
		return err
	}

	_, err = c.doRequest(req)
	return err
}

// getData GETs path and returns the "data" of the answer.
func (c *Client) getData(path string) (any, error) {
	return c.dataCall("GET", path, nil)
}

// dataCall sends one request and returns the "data" of the answer.
func (c *Client) dataCall(method, path string, body []byte) (any, error) {
	req, err := c.newRequestWithBody(method, path, body)
	if err != nil {
		return nil, err
	}
	resp, err := c.doRequest(req)
	if err != nil {
		return nil, err
	}
	var response struct {
		Data any `json:"data"`
	}
	if err := json.Unmarshal(resp, &response); err != nil {
		return nil, fmt.Errorf("failed to unmarshal response: %w", err)
	}
	return response.Data, nil
}

// GetSessionSummaries retrieves every session with its workflow summaries.
func (c *Client) GetSessionSummaries() (any, error) {
	return getAll[any](c, "/api/sessions/summaries")
}

// GetWorkflowGraph retrieves the node/edge graph of a workflow.
func (c *Client) GetWorkflowGraph(sessionID, workflowID string) (any, error) {
	if sessionID == "" || workflowID == "" {
		return nil, fmt.Errorf("sessionID and workflowID cannot be empty")
	}
	return c.getData(apiPath("/api/sessions/%s/workflows/%s/graph", sessionID, workflowID))
}

// GetWorkflowExecutions retrieves the execution history of one workflow, most recent first.
func (c *Client) GetWorkflowExecutions(sessionID, workflowID string) (any, error) {
	if sessionID == "" || workflowID == "" {
		return nil, fmt.Errorf("sessionID and workflowID cannot be empty")
	}
	return getAll[any](c, apiPath("/api/sessions/%s/workflows/%s/executions", sessionID, workflowID))
}

// DeleteSession deletes a session with its workflows and versions. Active runs refuse it (409)
// unless force cancels them first; run history stays.
func (c *Client) DeleteSession(sessionID string, force bool) error {
	return c.delete(apiPath("/api/sessions/%s", sessionID), force)
}

// DeleteWorkflow deletes one workflow of a session, with its versions.
func (c *Client) DeleteWorkflow(sessionID, workflowID string, force bool) error {
	return c.delete(apiPath("/api/sessions/%s/workflows/%s", sessionID, workflowID), force)
}

func (c *Client) delete(path string, force bool) error {
	if force {
		path += "?force=true"
	}
	req, err := c.newRequest("DELETE", path)
	if err != nil {
		return err
	}
	_, err = c.doRequest(req)
	return err
}

// ValidateWorkflow checks a workflow definition (POST /api/sessions/validate) without saving it.
func (c *Client) ValidateWorkflow(definitionJSON []byte) (any, error) {
	return c.dataCall("POST", "/api/sessions/validate", definitionJSON)
}

// GetPendingApprovals lists the approvals a session's runs wait for.
func (c *Client) GetPendingApprovals(sessionID string) (any, error) {
	return c.getData(apiPath("/api/sessions/%s/approvals", sessionID))
}

// GetWorkflowVersions lists the saved versions of a workflow.
func (c *Client) GetWorkflowVersions(sessionID, workflowID string) (any, error) {
	return c.getData(apiPath("/api/sessions/%s/workflows/%s/versions", sessionID, workflowID))
}

// DiffWorkflowVersions compares version from with version to (the current one when to is empty).
func (c *Client) DiffWorkflowVersions(sessionID, workflowID, from, to string) (any, error) {
	q := url.Values{"from": {from}}
	if to != "" {
		q.Set("to", to)
	}
	return c.getData(apiPath("/api/sessions/%s/workflows/%s/versions/diff", sessionID, workflowID) + "?" + q.Encode())
}

// RollbackWorkflow restores a saved version of a workflow.
func (c *Client) RollbackWorkflow(sessionID, workflowID, version string) (any, error) {
	path := apiPath("/api/sessions/%s/workflows/%s/rollback", sessionID, workflowID)
	return c.dataCall("POST", path+"?"+url.Values{"version": {version}}.Encode(), nil)
}

// NoteWorkflowVersion writes the note of a saved version; an empty note clears it.
func (c *Client) NoteWorkflowVersion(sessionID, workflowID, version, note string) (any, error) {
	q := url.Values{"version": {version}}
	if note != "" {
		q.Set("note", note)
	}
	path := apiPath("/api/sessions/%s/workflows/%s/versions/note", sessionID, workflowID)
	return c.dataCall("POST", path+"?"+q.Encode(), nil)
}
