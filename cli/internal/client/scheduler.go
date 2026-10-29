// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"encoding/json"
	"fmt"
)

// SchedulerLimits are the execution limits in force and the slots in use. 0 means unlimited.
type SchedulerLimits struct {
	MaxConcurrent  int `json:"maxConcurrent"`
	MaxPerWorkflow int `json:"maxPerWorkflow"`
	Running        int `json:"running"`
	Queued         int `json:"queued"`
	QueueCapacity  int `json:"queueCapacity"`
}

// GetSchedulerLimits reads the execution limits and how much of them is in use.
func (c *Client) GetSchedulerLimits() (SchedulerLimits, error) {
	return c.schedulerLimits("GET", "/api/scheduler/limits")
}

// SetSchedulerLimits changes the execution limits (0 for unlimited) and returns them as now in force.
func (c *Client) SetSchedulerLimits(maxConcurrent, maxPerWorkflow int) (SchedulerLimits, error) {
	if maxConcurrent < 0 || maxPerWorkflow < 0 {
		return SchedulerLimits{}, fmt.Errorf("limits cannot be negative")
	}
	query := fmt.Sprintf("?maxConcurrent=%d&maxPerWorkflow=%d", maxConcurrent, maxPerWorkflow)
	if _, err := c.schedulerLimits("POST", "/api/scheduler/limits"+query); err != nil {
		return SchedulerLimits{}, err
	}
	return c.GetSchedulerLimits()
}

// schedulerLimits runs a request and reads limits from its {"data": {...}} body. The POST answers
// with only the two limits, so the caller reads the full snapshot again.
func (c *Client) schedulerLimits(method, path string) (SchedulerLimits, error) {
	req, err := c.newRequest(method, path)
	if err != nil {
		return SchedulerLimits{}, err
	}
	body, err := c.doRequest(req)
	if err != nil {
		return SchedulerLimits{}, err
	}
	var response struct {
		Data SchedulerLimits `json:"data"`
	}
	if err := json.Unmarshal(body, &response); err != nil {
		return SchedulerLimits{}, fmt.Errorf("failed to unmarshal response: %w", err)
	}
	return response.Data, nil
}
