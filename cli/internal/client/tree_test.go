// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// treeControlServer answers the tree control calls and records each request as "METHOD path?query".
func treeControlServer(t *testing.T, seen *[]string) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if answerSession(w, r) {
			return
		}
		if r.Method == "POST" { // the GETs are the session check before a pause or resume
			*seen = append(*seen, r.Method+" "+r.URL.Path+"?"+r.URL.RawQuery)
		}
		w.Header().Set("Content-Type", "application/json")
		if strings.HasSuffix(r.URL.Path, "/tree") {
			_, _ = w.Write([]byte(`{"data":[{"executionId":"e1","workflowId":"w","status":"RUNNING"},` +
				`{"executionId":"c1","workflowId":"child","status":"PAUSED","parentExecutionId":"e1","rootExecutionId":"e1"}]}`))
			return
		}
		_, _ = w.Write([]byte(`{"data":{"executionId":"e1"}}`))
	}))
}

func TestTreeControl_asksForTheTree_onlyWhenAsked(t *testing.T) {
	var seen []string
	srv := treeControlServer(t, &seen)
	defer srv.Close()
	c := NewClient(srv.URL)

	calls := []func() (WorkflowStartResponse, error){
		func() (WorkflowStartResponse, error) { return c.PauseWorkflowTree("s1", "e1", true) },
		func() (WorkflowStartResponse, error) { return c.ResumeWorkflowTree("s1", "e1", true) },
		func() (WorkflowStartResponse, error) { return c.StopExecutionTree("e1", true) },
		func() (WorkflowStartResponse, error) { return c.PauseWorkflow("s1", "e1") },
	}
	for _, call := range calls {
		if resp, err := call(); err != nil || resp.ExecutionID != "e1" {
			t.Fatalf("got %+v, %v", resp, err)
		}
	}
	want := []string{
		"POST /api/executions/e1/pause?tree=true",
		"POST /api/executions/e1/resume?tree=true",
		"POST /api/executions/e1/stop?tree=true",
		"POST /api/executions/e1/pause?",
	}
	if strings.Join(seen, "\n") != strings.Join(want, "\n") {
		t.Errorf("requests:\n%s\nwant:\n%s", strings.Join(seen, "\n"), strings.Join(want, "\n"))
	}
}

func TestGetExecutionTree_listsTheRunsWithTheirParents(t *testing.T) {
	var seen []string
	srv := treeControlServer(t, &seen)
	defer srv.Close()

	runs, err := NewClient(srv.URL).GetExecutionTree("e1")
	if err != nil {
		t.Fatal(err)
	}
	if len(runs) != 2 || runs[1].ParentExecutionID != "e1" || runs[1].RootExecutionID != "e1" {
		t.Errorf("runs = %+v", runs)
	}
	if _, err := NewClient(srv.URL).GetExecutionTree(""); err == nil {
		t.Error("an empty execution id must be refused")
	}
}
