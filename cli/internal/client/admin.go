// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"net/url"
	"strconv"
)

// GetSupervisors lists the supervisors that run, with their scope and command counts.
func (c *Client) GetSupervisors() (any, error) { return c.getData("/api/supervisors/status") }

// ListSupervisors lists the configured supervisors, those of the server included.
func (c *Client) ListSupervisors() (any, error) { return c.getData("/api/supervisors") }

// GetSupervisor reads one configured supervisor.
func (c *Client) GetSupervisor(id string) (any, error) {
	return c.getData(apiPath("/api/supervisors/%s", id))
}

// GetSupervisorTypes lists the supervisor types in the catalog.
func (c *Client) GetSupervisorTypes() (any, error) { return c.getData("/api/supervisor-types") }

// GetSupervisorType describes one supervisor type with the settings it takes.
func (c *Client) GetSupervisorType(name string) (any, error) {
	return c.getData(apiPath("/api/supervisor-types/%s", name))
}

// GetWorkers lists the workers.
func (c *Client) GetWorkers() (any, error) { return c.getData("/api/workers") }

// GetSessionConfig reads the configuration a session was created from.
func (c *Client) GetSessionConfig(sessionID string) (any, error) {
	return c.getData(apiPath("/api/sessions/%s/config", sessionID))
}

// GetControlAudit lists control commands, newest first; limit 0 and an empty executionID mean no filter.
func (c *Client) GetControlAudit(limit int, executionID string) (any, error) {
	return c.getData("/api/control/audit" + query(limit, "limit", "executionId", executionID))
}

// SearchExecutions searches runs across sessions; empty or zero arguments are left out.
func (c *Client) SearchExecutions(sessionID, filter string, limit int) (any, error) {
	return c.getData("/api/executions" + query(limit, "limit", "sessionId", sessionID, "filter", filter))
}

// RecoverExecution recovers an aborted execution.
func (c *Client) RecoverExecution(executionID string) (any, error) {
	return c.dataCall("POST", apiPath("/api/executions/%s/recover", executionID), nil)
}

// SetExecutionPriority changes the priority of a queued execution.
func (c *Client) SetExecutionPriority(executionID string, priority int) (any, error) {
	q := url.Values{"priority": {strconv.Itoa(priority)}}.Encode()
	return c.dataCall("POST", apiPath("/api/executions/%s/priority", executionID)+"?"+q, nil)
}

// query builds "?k=v&..." from a limit (left out at 0) and key/value pairs (left out when empty).
func query(limit int, limitKey string, pairs ...string) string {
	q := url.Values{}
	if limit > 0 {
		q.Set(limitKey, strconv.Itoa(limit))
	}
	for i := 0; i+1 < len(pairs); i += 2 {
		if pairs[i+1] != "" {
			q.Set(pairs[i], pairs[i+1])
		}
	}
	if len(q) == 0 {
		return ""
	}
	return "?" + q.Encode()
}
