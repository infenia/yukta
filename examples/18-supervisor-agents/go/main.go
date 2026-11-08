// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

// A minimal supervisor in Go against /api/agent/v1, with the standard library only.
//
//	YUKTA_URL=http://localhost:9858 YUKTA_AGENT_TOKEN=yag_... go run .
//
// It follows the signals of its supervisor, pauses a run when a node of it fails and has no
// opinion about reviews. The supervisor decides whether each command is allowed.
package main

import (
	"bufio"
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"strings"
	"time"
)

var (
	base  = strings.TrimRight(envOr("YUKTA_URL", "http://localhost:9858"), "/") + "/api/agent/v1"
	token = os.Getenv("YUKTA_AGENT_TOKEN")
)

type envelope struct {
	Cursor int64  `json:"cursor"`
	Kind   string `json:"kind"`
	Signal struct {
		ID    string `json:"id"`
		Event struct {
			ExecutionID string `json:"executionId"`
			NodeID      string `json:"nodeId"`
			Status      string `json:"status"`
		} `json:"event"`
	} `json:"signal"`
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func send(path string, body any) error {
	raw, _ := json.Marshal(body)
	req, _ := http.NewRequest("POST", base+path, bytes.NewReader(raw))
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		return fmt.Errorf("%s: %s", path, resp.Status)
	}
	return nil
}

// decide turns one signal into a call, or nothing.
func decide(e envelope) (path string, body any, ok bool) {
	if e.Kind == "REVIEW" {
		return "/reviews/" + e.Signal.ID, map[string]string{"verdict": "NO_OPINION"}, true
	}
	ev := e.Signal.Event
	if e.Kind == "EVENT" && ev.Status == "FAILURE" && ev.NodeID != "" {
		return "/directives", map[string]any{
			"key":     fmt.Sprintf("pause-%s-%d", ev.ExecutionID, e.Cursor),
			"cause":   "event:" + e.Signal.ID,
			"reason":  "node " + ev.NodeID + " failed",
			"command": map[string]string{"type": "PauseWorkflow", "executionId": ev.ExecutionID},
		}, true
	}
	return "", nil, false
}

func follow(after int64) (int64, error) {
	req, _ := http.NewRequest("GET", fmt.Sprintf("%s/events?after=%d", base, after), nil)
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Accept", "text/event-stream")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return after, err
	}
	defer resp.Body.Close()
	scanner := bufio.NewScanner(resp.Body)
	scanner.Buffer(make([]byte, 0, 64*1024), 1024*1024)
	for scanner.Scan() {
		line := scanner.Text()
		if !strings.HasPrefix(line, "data:") {
			continue
		}
		var e envelope
		if json.Unmarshal([]byte(strings.TrimPrefix(line, "data:")), &e) != nil {
			continue
		}
		after = e.Cursor
		if path, body, ok := decide(e); ok {
			if err := send(path, body); err != nil {
				fmt.Println("not sent:", err)
			}
		}
	}
	return after, scanner.Err()
}

func main() {
	if token == "" {
		fmt.Println("set YUKTA_AGENT_TOKEN to the credential of the supervisor")
		os.Exit(1)
	}
	var after int64
	for {
		var err error
		after, err = follow(after)
		fmt.Println("reconnecting:", err)
		time.Sleep(2 * time.Second)
	}
}
