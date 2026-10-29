// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"encoding/json"
	"fmt"
)

// Whoami is who the server thinks the caller is and what that key may do.
type Whoami struct {
	Authenticated bool            `json:"authenticated"`
	KeyName       *string         `json:"keyName"`
	Role          string          `json:"role"`
	Sessions      []string        `json:"sessions"`
	AuthEnabled   bool            `json:"authEnabled"`
	Permissions   map[string]bool `json:"permissions"`
}

// GetWhoami asks the server who the caller is.
func (c *Client) GetWhoami() (Whoami, error) {
	req, err := c.newRequest("GET", "/api/me")
	if err != nil {
		return Whoami{}, err
	}
	body, err := c.doRequest(req)
	if err != nil {
		return Whoami{}, err
	}
	var response struct {
		Data Whoami `json:"data"`
	}
	if err := json.Unmarshal(body, &response); err != nil {
		return Whoami{}, fmt.Errorf("failed to unmarshal response: %w", err)
	}
	return response.Data, nil
}
