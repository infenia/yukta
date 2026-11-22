// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"encoding/json"
	"fmt"
	"net/url"
	"strconv"
)

// LogSearchQuery selects the lines of a run log search; zero values mean "not set".
type LogSearchQuery struct {
	IncludeChildren bool
	Nodes           string
	Stream          string
	Level           string
	Grep            string
	Context         int
	Mode            string
	MaxMatches      int
	Cursor          string
}

// LogSearchNode holds the counts of one node of one run.
type LogSearchNode struct {
	ExecutionID string `json:"executionId"`
	NodeID      string `json:"nodeId"`
	Lines       int64  `json:"lines"`
	Errors      int64  `json:"errors"`
	Warnings    int64  `json:"warnings"`
	Matches     int64  `json:"matches"`
}

// LogSearchLine is one line of a LINES search.
type LogSearchLine struct {
	ExecutionID string `json:"executionId"`
	NodeID      string `json:"nodeId"`
	Sequence    int64  `json:"sequence"`
	Stream      string `json:"stream"`
	Level       string `json:"level"`
	Match       bool   `json:"match"`
	Text        string `json:"text"`
}

// LogSearch is the answer of a run log search.
type LogSearch struct {
	ExecutionID       string          `json:"executionId"`
	Mode              string          `json:"mode"`
	ExecutionsScanned int64           `json:"executionsScanned"`
	LinesScanned      int64           `json:"linesScanned"`
	Matches           int64           `json:"matches"`
	Nodes             []LogSearchNode `json:"nodes"`
	NodesOmitted      int             `json:"nodesOmitted"`
	Lines             []LogSearchLine `json:"lines"`
	Truncated         bool            `json:"truncated"`
	NextCursor        string          `json:"nextCursor"`
	Hint              string          `json:"hint"`
}

// DigestNode is a node that failed or timed out.
type DigestNode struct {
	ExecutionID string `json:"executionId"`
	NodeID      string `json:"nodeId"`
	Module      string `json:"module"`
	Status      string `json:"status"`
	Error       string `json:"error"`
	Lines       int64  `json:"lines"`
	ErrorLines  int64  `json:"errorLines"`
}

// DigestFinding is one failure recognised in a node's output.
type DigestFinding struct {
	Kind        string `json:"kind"`
	ExecutionID string `json:"executionId"`
	NodeID      string `json:"nodeId"`
	Name        string `json:"name"`
	Location    string `json:"location"`
	Message     string `json:"message"`
	Line        int64  `json:"line"`
}

// DigestRead is a read that shows more of the failure.
type DigestRead struct {
	Kind        string `json:"kind"`
	ExecutionID string `json:"executionId"`
	NodeID      string `json:"nodeId"`
	Offset      *int64 `json:"offset"`
	Level       string `json:"level"`
	Why         string `json:"why"`
}

// FailureDigest says why a run failed, in a few kilobytes.
type FailureDigest struct {
	ExecutionID     string          `json:"executionId"`
	WorkflowID      string          `json:"workflowId"`
	Status          string          `json:"status"`
	Complete        bool            `json:"complete"`
	RunsSearched    int             `json:"runsSearched"`
	FailedNodes     []DigestNode    `json:"failedNodes"`
	Findings        []DigestFinding `json:"findings"`
	FindingsOmitted int             `json:"findingsOmitted"`
	Next            []DigestRead    `json:"next"`
}

// SearchLogs searches the logs of a run: counts per node, or the matching lines.
func (c *Client) SearchLogs(executionID string, q LogSearchQuery) (LogSearch, error) {
	if executionID == "" {
		return LogSearch{}, fmt.Errorf("executionID cannot be empty")
	}
	v := url.Values{}
	if q.IncludeChildren {
		v.Set("includeChildren", "true")
	}
	for name, s := range map[string]string{
		"nodes": q.Nodes, "stream": q.Stream, "level": q.Level, "grep": q.Grep, "mode": q.Mode, "cursor": q.Cursor,
	} {
		if s != "" {
			v.Set(name, s)
		}
	}
	for name, n := range map[string]int{"context": q.Context, "maxMatches": q.MaxMatches} {
		if n > 0 {
			v.Set(name, strconv.Itoa(n))
		}
	}
	path := apiPath("/api/executions/%s/logs/search", executionID)
	if len(v) > 0 {
		path += "?" + v.Encode()
	}
	var out LogSearch
	return out, c.getDataInto(path, &out)
}

// GetDigest returns why a run failed.
func (c *Client) GetDigest(executionID string) (FailureDigest, error) {
	if executionID == "" {
		return FailureDigest{}, fmt.Errorf("executionID cannot be empty")
	}
	var out FailureDigest
	return out, c.getDataInto(apiPath("/api/executions/%s/digest", executionID), &out)
}

// getDataInto GETs a path and decodes the data field of the response envelope into out.
func (c *Client) getDataInto(path string, out any) error {
	req, err := c.newRequest("GET", path)
	if err != nil {
		return err
	}
	body, err := c.doRequest(req)
	if err != nil {
		return err
	}
	envelope := struct {
		Data any `json:"data"`
	}{Data: out}
	if err := json.Unmarshal(body, &envelope); err != nil {
		return fmt.Errorf("failed to unmarshal response: %w", err)
	}
	return nil
}
