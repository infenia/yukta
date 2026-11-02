// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestListApprovals(t *testing.T) {
	tests := []struct {
		name        string
		sessionID   string
		status      int
		body        string
		wantPath    string
		wantSession []string
		wantErr     bool
	}{
		{
			name:        "all sessions keeps the session id from the response",
			sessionID:   "",
			status:      200,
			body:        `{"data":[{"sessionId":"s1","executionId":"e1","workflowId":"w1","nodeId":"n1","name":"approve","since":"2026-10-30T10:00:00Z"}]}`,
			wantPath:    "/api/approvals",
			wantSession: []string{"s1"},
		},
		{
			name:        "one session fills in the session id left out by the endpoint",
			sessionID:   "s2",
			status:      200,
			body:        `{"data":[{"executionId":"e2","workflowId":"w2","nodeId":"n2","name":"review","since":"2026-10-30T11:00:00Z"}]}`,
			wantPath:    "/api/sessions/s2/approvals",
			wantSession: []string{"s2"},
		},
		{
			name:        "one session keeps a session id the response already sets",
			sessionID:   "s3",
			status:      200,
			body:        `{"data":[{"sessionId":"s3","executionId":"e3","workflowId":"w3","nodeId":"n3","name":"check","since":"2026-10-30T12:00:00Z"}]}`,
			wantPath:    "/api/sessions/s3/approvals",
			wantSession: []string{"s3"},
		},
		{
			name:        "session id is path-escaped",
			sessionID:   "a/b",
			status:      200,
			body:        `{"data":[]}`,
			wantPath:    "/api/sessions/a%2Fb/approvals",
			wantSession: nil,
		},
		{
			name:      "server error is returned",
			sessionID: "",
			status:    500,
			body:      `{"message":"boom"}`,
			wantPath:  "/api/approvals",
			wantErr:   true,
		},
		{
			name:      "body that is not JSON is an error",
			sessionID: "s1",
			status:    200,
			body:      `not json`,
			wantPath:  "/api/sessions/s1/approvals",
			wantErr:   true,
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			var gotPath string
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				gotPath = r.URL.EscapedPath()
				w.WriteHeader(tt.status)
				_, _ = w.Write([]byte(tt.body))
			}))
			defer server.Close()

			approvals, err := NewClient(server.URL).ListApprovals(tt.sessionID)
			if (err != nil) != tt.wantErr {
				t.Fatalf("ListApprovals error = %v, wantErr %v", err, tt.wantErr)
			}
			if gotPath != tt.wantPath {
				t.Errorf("requested path = %q, want %q", gotPath, tt.wantPath)
			}
			if tt.wantErr {
				return
			}
			if len(approvals) != len(tt.wantSession) {
				t.Fatalf("got %d approvals, want %d: %+v", len(approvals), len(tt.wantSession), approvals)
			}
			for i, want := range tt.wantSession {
				if approvals[i].SessionID != want {
					t.Errorf("approval %d session id = %q, want %q", i, approvals[i].SessionID, want)
				}
			}
		})
	}
}

func TestListApprovals_fieldsAreDecoded(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"data":[{"sessionId":"s1","executionId":"e1","workflowId":"w1","nodeId":"n1","name":"approve","since":"2026-10-30T10:00:00Z"}]}`))
	}))
	defer server.Close()

	approvals, err := NewClient(server.URL).ListApprovals("")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	want := Approval{
		SessionID:   "s1",
		ExecutionID: "e1",
		WorkflowID:  "w1",
		NodeID:      "n1",
		Name:        "approve",
		Since:       "2026-10-30T10:00:00Z",
	}
	if len(approvals) != 1 || approvals[0] != want {
		t.Errorf("unexpected approvals: %+v", approvals)
	}
}

func TestListApprovals_badURL(t *testing.T) {
	if _, err := NewClient("http://bad host\x7f").ListApprovals("s1"); err == nil {
		t.Error("expected an error for an unusable base URL")
	}
}
