// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestStreamExecutionResults_deliversEventsAndEscapesTheNodeFilter(t *testing.T) {
	var gotPath, gotQuery string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if answerDone(w, r) {
			return
		}
		gotPath, gotQuery = r.URL.Path, r.URL.RawQuery
		w.Header().Set("Content-Type", "text/event-stream")
		fmt.Fprint(w, "data: {\"kind\":\"RESULT\"}\n\ndata: {\"kind\":\"EXECUTION_END\"}\n\n")
	}))
	defer server.Close()

	var got []string
	err := NewClient(server.URL).StreamExecutionResults(
		context.Background(), "s1", "e1", "term 1",
		func(_ string, data string) error {
			got = append(got, data)
			return nil
		})

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if gotPath != "/api/sessions/s1/executions/e1/results" || gotQuery != "nodeId=term+1" {
		t.Errorf("path %q query %q", gotPath, gotQuery)
	}
	if len(got) != 2 || got[1] != "{\"kind\":\"EXECUTION_END\"}" {
		t.Errorf("events %v", got)
	}
}

func TestStreamExecutionResults_withoutNodeFilterSendsNoQuery(t *testing.T) {
	var gotQuery string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if answerDone(w, r) {
			return
		}
		gotQuery = r.URL.RawQuery
		w.Header().Set("Content-Type", "text/event-stream")
	}))
	defer server.Close()

	err := NewClient(server.URL).StreamExecutionResults(
		context.Background(), "s1", "e1", "", func(string, string) error { return nil })

	if err != nil || gotQuery != "" {
		t.Errorf("err=%v query=%q", err, gotQuery)
	}
}

func TestStreamExecutionResults_rejectsBadArguments(t *testing.T) {
	c := NewClient("http://localhost:8080")
	noop := func(string, string) error { return nil }
	if err := c.StreamExecutionResults(context.Background(), "", "e1", "", noop); err == nil {
		t.Error("expected error for empty sessionID")
	}
	if err := c.StreamExecutionResults(context.Background(), "s1", "", "", noop); err == nil {
		t.Error("expected error for empty executionID")
	}
	if err := c.StreamExecutionResults(context.Background(), "s1", "e1", "", nil); err == nil {
		t.Error("expected error for nil callback")
	}
}

func TestStreamExecutionResults_apiErrorIsReturned(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		http.Error(w, "nope", http.StatusNotFound)
	}))
	defer server.Close()

	err := NewClient(server.URL).StreamExecutionResults(
		context.Background(), "s1", "e1", "", func(string, string) error { return nil })
	if err == nil {
		t.Error("expected API error")
	}
}
