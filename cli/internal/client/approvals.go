// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"encoding/json"
	"fmt"
	"net/url"
)

// Approval is a node that waits for a signal, in a session.
type Approval struct {
	SessionID   string `json:"sessionId"`
	ExecutionID string `json:"executionId"`
	WorkflowID  string `json:"workflowId"`
	NodeID      string `json:"nodeId"`
	Name        string `json:"name"`
	Since       string `json:"since"`
}

// ListApprovals reads the nodes that wait for a signal, oldest first: of one session, or of all
// sessions the key may see when sessionID is empty.
func (c *Client) ListApprovals(sessionID string) ([]Approval, error) {
	path := "/api/approvals"
	if sessionID != "" {
		path = "/api/sessions/" + url.PathEscape(sessionID) + "/approvals"
	}
	req, err := c.newRequest("GET", path)
	if err != nil {
		return nil, err
	}
	body, err := c.doRequest(req)
	if err != nil {
		return nil, err
	}
	var response struct {
		Data []Approval `json:"data"`
	}
	if err := json.Unmarshal(body, &response); err != nil {
		return nil, fmt.Errorf("failed to unmarshal response: %w", err)
	}
	for i := range response.Data {
		// the per-session endpoint leaves the session out
		if response.Data[i].SessionID == "" {
			response.Data[i].SessionID = sessionID
		}
	}
	return response.Data, nil
}
