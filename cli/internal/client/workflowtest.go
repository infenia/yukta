// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"encoding/json"
	"fmt"
)

// TestCheck is one assertion of a workflow test.
type TestCheck struct {
	Name   string `json:"name"`
	Passed bool   `json:"passed"`
	Detail string `json:"detail,omitempty"`
}

// TestReport is the outcome of a workflow test.
type TestReport struct {
	Passed     bool        `json:"passed"`
	Status     string      `json:"status"`
	Assertions []TestCheck `json:"assertions"`
}

// TestWorkflow runs a saved workflow once with stubbed nodes and checks the assertions in spec
// (input, stubs, expect, allow, timeoutSeconds). A failing assertion is a report with Passed
// false, not an error.
func (c *Client) TestWorkflow(sessionID, workflowID string, spec map[string]any) (TestReport, error) {
	if sessionID == "" {
		return TestReport{}, fmt.Errorf("sessionID cannot be empty")
	}
	if workflowID == "" {
		return TestReport{}, fmt.Errorf("workflowID cannot be empty")
	}
	body, err := json.Marshal(spec)
	if err != nil {
		return TestReport{}, fmt.Errorf("failed to encode the test: %w", err)
	}
	req, err := c.newRequestWithBody(
		"POST", apiPath("/api/sessions/%s/workflows/%s/test", sessionID, workflowID), body)
	if err != nil {
		return TestReport{}, err
	}
	resp, err := c.doRequest(req)
	if err != nil {
		return TestReport{}, err
	}
	var response struct {
		Data TestReport `json:"data"`
	}
	if err := json.Unmarshal(resp, &response); err != nil {
		return TestReport{}, fmt.Errorf("failed to unmarshal response: %w", err)
	}
	return response.Data, nil
}
