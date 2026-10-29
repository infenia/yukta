// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestVersionApprovalAndValidateCalls(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte(`{"data":{"ok":true}}`))
	}))
	defer srv.Close()
	c := NewClient(srv.URL)

	calls := map[string]func() (any, error){
		"validate":  func() (any, error) { return c.ValidateWorkflow([]byte(`{}`)) },
		"approvals": func() (any, error) { return c.GetPendingApprovals("s") },
		"versions":  func() (any, error) { return c.GetWorkflowVersions("s", "w") },
		"diff":      func() (any, error) { return c.DiffWorkflowVersions("s", "w", "1", "") },
		"diff to":   func() (any, error) { return c.DiffWorkflowVersions("s", "w", "1", "2") },
		"rollback":  func() (any, error) { return c.RollbackWorkflow("s", "w", "1") },
		"note":      func() (any, error) { return c.NoteWorkflowVersion("s", "w", "1", "n") },
		"clear":     func() (any, error) { return c.NoteWorkflowVersion("s", "w", "1", "") },
	}
	for name, call := range calls {
		if data, err := call(); err != nil || data == nil {
			t.Errorf("%s: %v %v", name, data, err)
		}
	}
}
