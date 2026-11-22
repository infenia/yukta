// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestSearchLogs(t *testing.T) {
	var method, path, query string
	server := callServer(t, `{"executionId":"e1","mode":"LINES","matches":1,"nodes":[{"nodeId":"b","matches":1}],"lines":[{"nodeId":"b","sequence":3,"text":"boom","match":true}],"truncated":true,"nextCursor":"e1:3","hint":"more"}`,
		&method, &path, &query)
	defer server.Close()

	out, err := NewClient(server.URL).SearchLogs("e1", LogSearchQuery{
		IncludeChildren: true, Nodes: "b", Stream: "stderr", Level: "ERROR", Grep: "a b",
		Context: 2, Mode: "LINES", MaxMatches: 5, Cursor: "e1:1",
	})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if method != "GET" || path != "/api/executions/e1/logs/search" {
		t.Errorf("request = %s %s", method, path)
	}
	for _, want := range []string{"includeChildren=true", "nodes=b", "stream=stderr", "level=ERROR", "grep=a+b",
		"context=2", "mode=LINES", "maxMatches=5", "cursor=e1%3A1"} {
		if !contains(query, want) {
			t.Errorf("query %q lacks %q", query, want)
		}
	}
	if out.NextCursor != "e1:3" || len(out.Lines) != 1 || out.Lines[0].Text != "boom" || out.Nodes[0].NodeID != "b" {
		t.Errorf("output = %+v", out)
	}
}

func TestSearchLogs_noQueryAndBadInput(t *testing.T) {
	var method, path, query string
	server := callServer(t, `{}`, &method, &path, &query)
	defer server.Close()
	c := NewClient(server.URL)

	if _, err := c.SearchLogs("e1", LogSearchQuery{}); err != nil || query != "" {
		t.Errorf("err=%v query=%q", err, query)
	}
	if _, err := c.SearchLogs("", LogSearchQuery{}); err == nil {
		t.Error("expected an error for an empty execution id")
	}
}

func TestGetDigest(t *testing.T) {
	var method, path, query string
	server := callServer(t, `{"executionId":"e1","status":"FAILURE","complete":true,"failedNodes":[{"nodeId":"b","status":"FAILURE"}],"findings":[{"kind":"TEST","name":"T > m()","location":"T.java:3","message":"boom","line":7}],"next":[{"kind":"NODE_OUTPUT","nodeId":"b","offset":1,"why":"look"}]}`,
		&method, &path, &query)
	defer server.Close()

	out, err := NewClient(server.URL).GetDigest("e1")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if path != "/api/executions/e1/digest" || out.Status != "FAILURE" || out.Findings[0].Line != 7 || *out.Next[0].Offset != 1 {
		t.Errorf("path=%s output=%+v", path, out)
	}
	if _, err := NewClient(server.URL).GetDigest(""); err == nil {
		t.Error("expected an error for an empty execution id")
	}
}

func TestDiagnosis_serverErrorsAndBadJSON(t *testing.T) {
	bad := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { _, _ = w.Write([]byte("not json")) }))
	defer bad.Close()
	if _, err := NewClient(bad.URL).GetDigest("e1"); err == nil {
		t.Error("expected a decode error")
	}
	down := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { http.Error(w, "no", http.StatusNotFound) }))
	defer down.Close()
	if _, err := NewClient(down.URL).SearchLogs("e1", LogSearchQuery{}); err == nil {
		t.Error("expected an error from the server")
	}
	if _, err := NewClient("http://bad host").GetDigest("e1"); err == nil {
		t.Error("expected a request error")
	}
}
