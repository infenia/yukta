// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"encoding/json"
	"fmt"
)

// PluginCategory represents the category of a plugin (TRIGGER, PROCESSOR, TERMINAL).
type PluginCategory string

const (
	PluginCategoryTrigger   PluginCategory = "TRIGGER"
	PluginCategoryProcessor PluginCategory = "PROCESSOR"
	PluginCategoryTerminal  PluginCategory = "TERMINAL"
)

// PluginSummary represents a high-level summary of a plugin.
type PluginSummary struct {
	Type     string         `json:"type"`
	Category PluginCategory `json:"category"`
}

// UIDesign represents the UI design metadata for a plugin.
type UIDesign struct {
	HTML   string `json:"html"`
	Width  int    `json:"width"`
	Height int    `json:"height"`
}

// PluginDetails represents the full details of a plugin.
type PluginDetails struct {
	Type         string         `json:"type"`
	Category     PluginCategory `json:"category"`
	Description  string         `json:"description"`
	UsagePattern string         `json:"usagePattern"`
	UIDesign     UIDesign       `json:"uiDesign"`
	OutputPorts  []string       `json:"outputPorts"`
}

// ListPlugins retrieves all registered workflow plugins.
func (c *Client) ListPlugins() ([]PluginSummary, error) {
	req, err := c.newRequest("GET", "/api/plugins")
	if err != nil {
		return nil, err
	}

	body, err := c.doRequest(req)
	if err != nil {
		return nil, err
	}

	// Parse the response: {"data": [...]}
	var response struct {
		Data []PluginSummary `json:"data"`
	}

	if err := json.Unmarshal(body, &response); err != nil {
		return nil, fmt.Errorf("failed to unmarshal response: %w", err)
	}

	return response.Data, nil
}

// GetPluginDetails retrieves the full details of a specific plugin.
func (c *Client) GetPluginDetails(pluginType string) (PluginDetails, error) {
	if pluginType == "" {
		return PluginDetails{}, fmt.Errorf("pluginType cannot be empty")
	}

	path := fmt.Sprintf("/api/plugins/%s", pluginType)
	req, err := c.newRequest("GET", path)
	if err != nil {
		return PluginDetails{}, err
	}

	body, err := c.doRequest(req)
	if err != nil {
		return PluginDetails{}, err
	}

	// Parse the response: {"data": {...}}
	var response struct {
		Data PluginDetails `json:"data"`
	}

	if err := json.Unmarshal(body, &response); err != nil {
		return PluginDetails{}, fmt.Errorf("failed to unmarshal response: %w", err)
	}

	return response.Data, nil
}
