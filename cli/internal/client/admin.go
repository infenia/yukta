// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"encoding/json"
	"fmt"
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

// GetSupervisorPresets lists the ready-made settings of a supervisor type.
func (c *Client) GetSupervisorPresets(name string) (any, error) {
	return c.getData(apiPath("/api/supervisor-types/%s/presets", name))
}

// GetSupervisorVersions lists the saved versions of a supervisor, newest first.
func (c *Client) GetSupervisorVersions(id string) (any, error) {
	return c.getData(apiPath("/api/supervisors/%s/versions", id))
}

// RollbackSupervisor makes an earlier version of a supervisor current again.
func (c *Client) RollbackSupervisor(id string, version int) (any, error) {
	if version < 1 {
		return nil, fmt.Errorf("version must be at least 1")
	}
	return c.dataCall("POST", apiPath("/api/supervisors/%s/rollback", id)+fmt.Sprintf("?version=%d", version), nil)
}

// ReplaySupervisor tries a supervisor on the recorded events of a past run, in dry run. A non-empty
// settingsJSON is tried instead of the stored settings.
func (c *Client) ReplaySupervisor(id, executionID, settingsJSON string) (any, error) {
	if executionID == "" {
		return nil, fmt.Errorf("an execution is needed")
	}
	body := map[string]any{"executionId": executionID}
	if settingsJSON != "" {
		var settings map[string]any
		if err := json.Unmarshal([]byte(settingsJSON), &settings); err != nil {
			return nil, fmt.Errorf("the settings are not a JSON object: %w", err)
		}
		body["settings"] = settings
	}
	raw, err := json.Marshal(body)
	if err != nil {
		return nil, err
	}
	return c.dataCall("POST", apiPath("/api/supervisors/%s/replay", id), raw)
}

// IssueAgentCredential makes the credential of an http supervisor (the token is shown once); a
// ttlHours of 0 means no expiry.
func (c *Client) IssueAgentCredential(id string, ttlHours int) (any, error) {
	path := apiPath("/api/supervisors/%s/credential", id)
	if ttlHours > 0 {
		path += fmt.Sprintf("?ttlHours=%d", ttlHours)
	}
	return c.dataCall("POST", path, nil)
}

// RevokeAgentCredential cuts the credential of an http supervisor at once.
func (c *Client) RevokeAgentCredential(id string) (any, error) {
	return c.dataCall("DELETE", apiPath("/api/supervisors/%s/credential", id), nil)
}

// GetSupervision asks what supervision applies to a target: who owns it and whether a command
// would be allowed. Empty parts are left out.
func (c *Client) GetSupervision(session, workflow, execution, node string) (any, error) {
	query := url.Values{}
	for key, value := range map[string]string{"session": session, "workflow": workflow, "execution": execution, "node": node} {
		if value != "" {
			query.Set(key, value)
		}
	}
	path := "/api/supervision"
	if len(query) > 0 {
		path += "?" + query.Encode()
	}
	return c.getData(path)
}

// GetOwnership shows which supervisor carries the run level and each node of a workflow.
func (c *Client) GetOwnership(session, workflow string) (any, error) {
	query := url.Values{}
	query.Set("session", session)
	query.Set("workflow", workflow)
	return c.getData("/api/supervision/ownership?" + query.Encode())
}

// GetSupervisorContract reads the supervisor contract version and its operation names.
func (c *Client) GetSupervisorContract() (any, error) { return c.getData("/api/supervisor-contract") }

// GetSupervisorDecisions lists the newest decisions of the supervisors; an empty supervisor means
// all of them.
func (c *Client) GetSupervisorDecisions(supervisor string, limit int) (any, error) {
	query := url.Values{}
	if supervisor != "" {
		query.Set("supervisor", supervisor)
	}
	if limit > 0 {
		query.Set("limit", strconv.Itoa(limit))
	}
	path := "/api/supervisors/decisions"
	if len(query) > 0 {
		path += "?" + query.Encode()
	}
	return c.getData(path)
}

// SaveSupervisor creates or replaces a supervisor from its JSON body, which names its id.
func (c *Client) SaveSupervisor(body []byte) (any, error) {
	var named struct {
		ID string `json:"id"`
	}
	if err := json.Unmarshal(body, &named); err != nil || named.ID == "" {
		return nil, fmt.Errorf("the supervisor file must be a JSON object with an id")
	}
	return c.dataCall("PUT", apiPath("/api/supervisors/%s", named.ID), body)
}

// ValidateSupervisor checks a draft supervisor without saving it.
func (c *Client) ValidateSupervisor(body []byte) (any, error) {
	return c.dataCall("POST", "/api/supervisors/validate", body)
}

// DeleteSupervisor deletes a supervisor that nothing uses.
func (c *Client) DeleteSupervisor(id string) (any, error) {
	return c.dataCall("DELETE", apiPath("/api/supervisors/%s", id), nil)
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
