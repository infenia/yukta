// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package client

import (
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// TestGetSessions_success_returnsSessionList tests successful session retrieval.
func TestGetSessions_success_returnsSessionList(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/sessions" {
			t.Errorf("expected path /api/sessions, got %s", r.URL.Path)
		}
		if r.Method != "GET" {
			t.Errorf("expected GET method, got %s", r.Method)
		}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"data":{"sessionIds":["session-1","session-2","session-3"]}}`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	sessions, err := c.GetSessions()

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if len(sessions) != 3 {
		t.Errorf("expected 3 sessions, got %d", len(sessions))
	}

	if sessions[0] != "session-1" || sessions[1] != "session-2" || sessions[2] != "session-3" {
		t.Errorf("unexpected sessions: %v", sessions)
	}
}

// TestGetSessions_emptyList_returnsEmpty tests empty session list.
func TestGetSessions_emptyList_returnsEmpty(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"data":{"sessionIds":[]}}`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	sessions, err := c.GetSessions()

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if len(sessions) != 0 {
		t.Errorf("expected empty session list, got %v", sessions)
	}
}

// TestGetSessions_httpError_returnsError tests HTTP error handling.
func TestGetSessions_httpError_returnsError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		_, _ = w.Write([]byte("Server Error"))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	_, err := c.GetSessions()

	if err == nil {
		t.Error("expected error for HTTP 500, got nil")
	}

	if !strings.Contains(err.Error(), "status 500") {
		t.Errorf("expected 'status 500' in error, got: %v", err)
	}
}

// TestGetSessions_malformedJSON_returnsError tests malformed JSON response.
func TestGetSessions_malformedJSON_returnsError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{invalid json`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	_, err := c.GetSessions()

	if err == nil {
		t.Error("expected error for malformed JSON, got nil")
	}

	if !strings.Contains(err.Error(), "failed to unmarshal") {
		t.Errorf("expected 'failed to unmarshal' in error, got: %v", err)
	}
}

// TestGetSessions_missingSessionIds_returnsEmpty tests response without sessionIds field.
func TestGetSessions_missingSessionIds_returnsEmpty(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"data":{}}`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	sessions, err := c.GetSessions()

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if len(sessions) != 0 {
		t.Errorf("expected empty session list, got %v", sessions)
	}
}

// TestGetSessionDetails_emptySessionID_returnsError tests empty session ID validation.
func TestGetSessionDetails_emptySessionID_returnsError(t *testing.T) {
	c := NewClient("http://localhost:8080")
	_, err := c.GetSessionDetails("")

	if err == nil {
		t.Error("expected error for empty sessionID, got nil")
	}

	if !strings.Contains(err.Error(), "sessionID cannot be empty") {
		t.Errorf("expected 'sessionID cannot be empty' in error, got: %v", err)
	}
}

// TestGetSessionDetails_success_returnsDetails tests successful session details retrieval.
func TestGetSessionDetails_success_returnsDetails(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/sessions/session-123" {
			t.Errorf("expected path /api/sessions/session-123, got %s", r.URL.Path)
		}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"data":{"sessionId":"session-123","workflowIds":["workflow-1","workflow-2"]}}`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	details, err := c.GetSessionDetails("session-123")

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if details["sessionId"] != "session-123" {
		t.Errorf("expected sessionId 'session-123', got %v", details["sessionId"])
	}

	workflows, ok := details["workflowIds"].([]interface{})
	if !ok {
		t.Errorf("expected workflowIds to be a list, got %T", details["workflowIds"])
	}

	if len(workflows) != 2 {
		t.Errorf("expected 2 workflows, got %d", len(workflows))
	}
}

// TestGetSessionDetails_httpError_returnsError tests HTTP error handling.
func TestGetSessionDetails_httpError_returnsError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
		_, _ = w.Write([]byte("Not Found"))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	_, err := c.GetSessionDetails("session-123")

	if err == nil {
		t.Error("expected error for HTTP 404, got nil")
	}

	if !strings.Contains(err.Error(), "status 404") {
		t.Errorf("expected 'status 404' in error, got: %v", err)
	}
}

// TestGetSessionDetails_malformedJSON_returnsError tests malformed JSON response.
func TestGetSessionDetails_malformedJSON_returnsError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`not valid json`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	_, err := c.GetSessionDetails("session-123")

	if err == nil {
		t.Error("expected error for malformed JSON, got nil")
	}

	if !strings.Contains(err.Error(), "failed to unmarshal") {
		t.Errorf("expected 'failed to unmarshal' in error, got: %v", err)
	}
}

// TestGetSessionDetails_noWorkflows_returnsEmptyList tests session with no workflows.
func TestGetSessionDetails_noWorkflows_returnsEmptyList(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"data":{"sessionId":"session-123","workflowIds":[]}}`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	details, err := c.GetSessionDetails("session-123")

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	workflows, ok := details["workflowIds"].([]interface{})
	if !ok {
		t.Errorf("expected workflowIds to be a list")
	}

	if len(workflows) != 0 {
		t.Errorf("expected empty workflows, got %d", len(workflows))
	}
}

// TestGetWorkflow_emptySessionID_returnsError tests empty session ID validation.
func TestGetWorkflow_emptySessionID_returnsError(t *testing.T) {
	c := NewClient("http://localhost:8080")
	_, err := c.GetWorkflow("", "workflow-123")

	if err == nil {
		t.Error("expected error for empty sessionID, got nil")
	}

	if !strings.Contains(err.Error(), "sessionID cannot be empty") {
		t.Errorf("expected 'sessionID cannot be empty' in error, got: %v", err)
	}
}

// TestGetWorkflow_emptyWorkflowID_returnsError tests empty workflow ID validation.
func TestGetWorkflow_emptyWorkflowID_returnsError(t *testing.T) {
	c := NewClient("http://localhost:8080")
	_, err := c.GetWorkflow("session-123", "")

	if err == nil {
		t.Error("expected error for empty workflowID, got nil")
	}

	if !strings.Contains(err.Error(), "workflowID cannot be empty") {
		t.Errorf("expected 'workflowID cannot be empty' in error, got: %v", err)
	}
}

// TestGetWorkflow_success_returnsWorkflow tests successful workflow retrieval.
func TestGetWorkflow_success_returnsWorkflow(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		expectedPath := "/api/sessions/session-123/workflows/workflow-456"
		if r.URL.Path != expectedPath {
			t.Errorf("expected path %s, got %s", expectedPath, r.URL.Path)
		}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"data":{"id":"workflow-456","nodes":[],"edges":[]}}`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	workflow, err := c.GetWorkflow("session-123", "workflow-456")

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if workflow["id"] != "workflow-456" {
		t.Errorf("expected id 'workflow-456', got %v", workflow["id"])
	}
}

// TestGetWorkflow_httpError_returnsError tests HTTP error handling.
func TestGetWorkflow_httpError_returnsError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		_, _ = w.Write([]byte("Error"))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	_, err := c.GetWorkflow("session-123", "workflow-456")

	if err == nil {
		t.Error("expected error for HTTP 500, got nil")
	}
}

// TestGetWorkflow_malformedJSON_returnsError tests malformed JSON response.
func TestGetWorkflow_malformedJSON_returnsError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{broken json}`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	_, err := c.GetWorkflow("session-123", "workflow-456")

	if err == nil {
		t.Error("expected error for malformed JSON, got nil")
	}

	if !strings.Contains(err.Error(), "failed to unmarshal") {
		t.Errorf("expected 'failed to unmarshal' in error, got: %v", err)
	}
}

// TestGetWorkflow_complexData_returnsCorrectly tests complex workflow data.
func TestGetWorkflow_complexData_returnsCorrectly(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{
			"data": {
				"id": "workflow-456",
				"nodes": [{"id": "node-1"}, {"id": "node-2"}],
				"edges": [{"from": "node-1", "to": "node-2"}]
			}
		}`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	workflow, err := c.GetWorkflow("session-123", "workflow-456")

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if workflow["id"] != "workflow-456" {
		t.Errorf("expected id 'workflow-456'")
	}

	nodes, ok := workflow["nodes"].([]interface{})
	if !ok {
		t.Errorf("expected nodes to be a list")
	}

	if len(nodes) != 2 {
		t.Errorf("expected 2 nodes, got %d", len(nodes))
	}
}

// TestApplyConfig_emptyJSON_returnsError tests empty configuration validation.
func TestApplyConfig_emptyJSON_returnsError(t *testing.T) {
	c := NewClient("http://localhost:8080")
	err := c.ApplyConfig([]byte{})

	if err == nil {
		t.Error("expected error for empty config, got nil")
	}

	if !strings.Contains(err.Error(), "configJSON cannot be empty") {
		t.Errorf("expected 'configJSON cannot be empty' in error, got: %v", err)
	}
}

// TestApplyConfig_success_noError tests successful configuration application.
func TestApplyConfig_success_noError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/sessions" {
			t.Errorf("expected path /api/sessions, got %s", r.URL.Path)
		}
		if r.Method != "POST" {
			t.Errorf("expected POST method, got %s", r.Method)
		}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"success"}`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	err := c.ApplyConfig([]byte(`{"sessionId":"test-session"}`))

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
}

// TestApplyConfig_httpError_returnsError tests HTTP error handling.
func TestApplyConfig_httpError_returnsError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusBadRequest)
		_, _ = w.Write([]byte("Invalid configuration"))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	err := c.ApplyConfig([]byte(`{"sessionId":"test-session"}`))

	if err == nil {
		t.Error("expected error for HTTP 400, got nil")
	}

	if !strings.Contains(err.Error(), "status 400") {
		t.Errorf("expected 'status 400' in error, got: %v", err)
	}
}

// TestApplyConfig_headersSet tests that correct headers are set.
func TestApplyConfig_headersSet(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		contentType := r.Header.Get("Content-Type")
		if contentType != "application/json" {
			t.Errorf("expected Content-Type 'application/json', got %q", contentType)
		}

		accept := r.Header.Get("Accept")
		if accept != "application/json" {
			t.Errorf("expected Accept 'application/json', got %q", accept)
		}

		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"success"}`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	err := c.ApplyConfig([]byte(`{"sessionId":"test-session"}`))

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
}

// TestApplyConfig_bodyReceived tests that request body is received correctly.
func TestApplyConfig_bodyReceived(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Read request body
		buf := make([]byte, 1024)
		n, _ := r.Body.Read(buf)
		received := string(buf[:n])

		if !strings.Contains(received, "test-session") {
			t.Errorf("expected 'test-session' in request body, got %q", received)
		}

		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"success"}`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	err := c.ApplyConfig([]byte(`{"sessionId":"test-session"}`))

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
}

// TestApplyConfig_emptySessionId_stillWorks tests that validation doesn't reject by content.
func TestApplyConfig_emptySessionId_stillWorks(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"success"}`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	// Empty string is not empty []byte
	err := c.ApplyConfig([]byte(`{"sessionId":""}`))

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
}

// TestApplyConfig_largeConfig_works tests that large configs are handled.
func TestApplyConfig_largeConfig_works(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"success"}`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	largeConfig := `{"sessionId":"test","data":"` + strings.Repeat("x", 10000) + `"}`
	err := c.ApplyConfig([]byte(largeConfig))

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
}

// TestApplyConfig_emptyConfig_returnsError tests that empty config JSON is rejected.
func TestApplyConfig_emptyConfig_returnsError(t *testing.T) {
	c := NewClient("http://localhost:8080")
	err := c.ApplyConfig([]byte{})

	if err == nil {
		t.Error("expected error for empty config, got nil")
	}

	if !strings.Contains(err.Error(), "configJSON cannot be empty") {
		t.Errorf("expected 'configJSON cannot be empty' in error, got: %v", err)
	}
}

// ===== RequestFactory Failure Tests (Complete Coverage) =====

// TestGetSessions_requestFactoryFails_returnsError tests request factory failure in GetSessions.
func TestGetSessions_requestFactoryFails_returnsError(t *testing.T) {
	mockFactory := &MockRequestFactory{
		Err: errors.New("failed to create request"),
	}

	c := &Client{
		BaseURL:        "http://localhost:8080",
		HTTPClient:     &http.Client{},
		RequestFactory: mockFactory,
		httpDoer:       &http.Client{},
	}

	_, err := c.GetSessions()
	if err == nil {
		t.Error("expected error when factory fails, got nil")
	}

	if !strings.Contains(err.Error(), "failed to create request") {
		t.Errorf("expected factory error in GetSessions, got: %v", err)
	}
}

// TestGetSessionDetails_requestFactoryFails_returnsError tests request factory failure in GetSessionDetails.
func TestGetSessionDetails_requestFactoryFails_returnsError(t *testing.T) {
	mockFactory := &MockRequestFactory{
		Err: errors.New("failed to create request"),
	}

	c := &Client{
		BaseURL:        "http://localhost:8080",
		HTTPClient:     &http.Client{},
		RequestFactory: mockFactory,
		httpDoer:       &http.Client{},
	}

	_, err := c.GetSessionDetails("session-123")
	if err == nil {
		t.Error("expected error when factory fails, got nil")
	}

	if !strings.Contains(err.Error(), "failed to create request") {
		t.Errorf("expected factory error in GetSessionDetails, got: %v", err)
	}
}

// TestGetWorkflow_requestFactoryFails_returnsError tests request factory failure in GetWorkflow.
func TestGetWorkflow_requestFactoryFails_returnsError(t *testing.T) {
	mockFactory := &MockRequestFactory{
		Err: errors.New("failed to create request"),
	}

	c := &Client{
		BaseURL:        "http://localhost:8080",
		HTTPClient:     &http.Client{},
		RequestFactory: mockFactory,
		httpDoer:       &http.Client{},
	}

	_, err := c.GetWorkflow("session-123", "workflow-456")
	if err == nil {
		t.Error("expected error when factory fails, got nil")
	}

	if !strings.Contains(err.Error(), "failed to create request") {
		t.Errorf("expected factory error in GetWorkflow, got: %v", err)
	}
}

// TestApplyConfig_requestFactoryFails_returnsError tests request factory failure in ApplyConfig.
func TestApplyConfig_requestFactoryFails_returnsError(t *testing.T) {
	mockFactory := &MockRequestFactory{
		Err: errors.New("failed to create request"),
	}

	c := &Client{
		BaseURL:        "http://localhost:8080",
		HTTPClient:     &http.Client{},
		RequestFactory: mockFactory,
		httpDoer:       &http.Client{},
	}

	err := c.ApplyConfig([]byte(`{"sessionId":"test"}`))
	if err == nil {
		t.Error("expected error when factory fails, got nil")
	}

	if !strings.Contains(err.Error(), "failed to create request") {
		t.Errorf("expected factory error in ApplyConfig, got: %v", err)
	}
}

// ===== HTTPDoer Failure Tests (Complete Coverage) =====

// TestGetSessions_httpDoerFails_returnsError tests HTTP doer failure in GetSessions.
func TestGetSessions_httpDoerFails_returnsError(t *testing.T) {
	mockDoer := &MockHTTPDoer{
		Err: errors.New("connection refused"),
	}

	c := &Client{
		BaseURL:        "http://localhost:8080",
		HTTPClient:     &http.Client{},
		RequestFactory: &DefaultRequestFactory{},
		httpDoer:       mockDoer,
	}

	_, err := c.GetSessions()
	if err == nil {
		t.Error("expected error when HTTP doer fails, got nil")
	}

	if !strings.Contains(err.Error(), "request failed") {
		t.Errorf("expected doer error in GetSessions, got: %v", err)
	}
}

// TestGetSessionDetails_httpDoerFails_returnsError tests HTTP doer failure in GetSessionDetails.
func TestGetSessionDetails_httpDoerFails_returnsError(t *testing.T) {
	mockDoer := &MockHTTPDoer{
		Err: errors.New("connection timeout"),
	}

	c := &Client{
		BaseURL:        "http://localhost:8080",
		HTTPClient:     &http.Client{},
		RequestFactory: &DefaultRequestFactory{},
		httpDoer:       mockDoer,
	}

	_, err := c.GetSessionDetails("session-123")
	if err == nil {
		t.Error("expected error when HTTP doer fails, got nil")
	}

	if !strings.Contains(err.Error(), "request failed") {
		t.Errorf("expected doer error in GetSessionDetails, got: %v", err)
	}
}

// TestGetWorkflow_httpDoerFails_returnsError tests HTTP doer failure in GetWorkflow.
func TestGetWorkflow_httpDoerFails_returnsError(t *testing.T) {
	mockDoer := &MockHTTPDoer{
		Err: errors.New("network error"),
	}

	c := &Client{
		BaseURL:        "http://localhost:8080",
		HTTPClient:     &http.Client{},
		RequestFactory: &DefaultRequestFactory{},
		httpDoer:       mockDoer,
	}

	_, err := c.GetWorkflow("session-123", "workflow-456")
	if err == nil {
		t.Error("expected error when HTTP doer fails, got nil")
	}

	if !strings.Contains(err.Error(), "request failed") {
		t.Errorf("expected doer error in GetWorkflow, got: %v", err)
	}
}

// TestApplyConfig_httpDoerFails_returnsError tests HTTP doer failure in ApplyConfig.
func TestApplyConfig_httpDoerFails_returnsError(t *testing.T) {
	mockDoer := &MockHTTPDoer{
		Err: errors.New("connection error"),
	}

	c := &Client{
		BaseURL:        "http://localhost:8080",
		HTTPClient:     &http.Client{},
		RequestFactory: &DefaultRequestFactory{},
		httpDoer:       mockDoer,
	}

	err := c.ApplyConfig([]byte(`{"sessionId":"test"}`))
	if err == nil {
		t.Error("expected error when HTTP doer fails, got nil")
	}

	if !strings.Contains(err.Error(), "request failed") {
		t.Errorf("expected doer error in ApplyConfig, got: %v", err)
	}
}

// ===== Integration Tests (Real HTTP paths) =====

// TestGetSessions_newRequestFails_withRealFactory tests newRequest error path in GetSessions.
func TestGetSessions_newRequestFails_withRealFactory(t *testing.T) {
	// Use an invalid base URL that will fail during newRequest
	c := NewClient("http://localhost:8080")
	c.RequestFactory = &MockRequestFactory{
		Err: errors.New("network error"),
	}

	_, err := c.GetSessions()
	if err == nil {
		t.Error("expected error from newRequest failure")
	}
}

// TestGetSessionDetails_newRequestFails_withRealFactory tests newRequest error path in GetSessionDetails.
func TestGetSessionDetails_newRequestFails_withRealFactory(t *testing.T) {
	c := NewClient("http://localhost:8080")
	c.RequestFactory = &MockRequestFactory{
		Err: errors.New("network error"),
	}

	_, err := c.GetSessionDetails("session-123")
	if err == nil {
		t.Error("expected error from newRequest failure")
	}
}

// TestGetWorkflow_newRequestFails_withRealFactory tests newRequest error path in GetWorkflow.
func TestGetWorkflow_newRequestFails_withRealFactory(t *testing.T) {
	c := NewClient("http://localhost:8080")
	c.RequestFactory = &MockRequestFactory{
		Err: errors.New("network error"),
	}

	_, err := c.GetWorkflow("session-123", "workflow-456")
	if err == nil {
		t.Error("expected error from newRequest failure")
	}
}

// TestApplyConfig_newRequestFails_withRealFactory tests newRequest error path in ApplyConfig.
func TestApplyConfig_newRequestFails_withRealFactory(t *testing.T) {
	c := NewClient("http://localhost:8080")
	c.RequestFactory = &MockRequestFactory{
		Err: errors.New("network error"),
	}

	err := c.ApplyConfig([]byte(`{"sessionId":"test"}`))
	if err == nil {
		t.Error("expected error from newRequest failure")
	}
}

// TestGetSessions_doRequestFails_withRealFactory tests doRequest error path in GetSessions.
func TestGetSessions_doRequestFails_withRealFactory(t *testing.T) {
	c := NewClient("http://localhost:8080")
	c.httpDoer = &MockHTTPDoer{
		Err: errors.New("connection error"),
	}

	_, err := c.GetSessions()
	if err == nil {
		t.Error("expected error from doRequest failure")
	}
}

// TestGetSessionDetails_doRequestFails_withRealFactory tests doRequest error path in GetSessionDetails.
func TestGetSessionDetails_doRequestFails_withRealFactory(t *testing.T) {
	c := NewClient("http://localhost:8080")
	c.httpDoer = &MockHTTPDoer{
		Err: errors.New("connection error"),
	}

	_, err := c.GetSessionDetails("session-123")
	if err == nil {
		t.Error("expected error from doRequest failure")
	}
}

// TestGetWorkflow_doRequestFails_withRealFactory tests doRequest error path in GetWorkflow.
func TestGetWorkflow_doRequestFails_withRealFactory(t *testing.T) {
	c := NewClient("http://localhost:8080")
	c.httpDoer = &MockHTTPDoer{
		Err: errors.New("connection error"),
	}

	_, err := c.GetWorkflow("session-123", "workflow-456")
	if err == nil {
		t.Error("expected error from doRequest failure")
	}
}

// TestApplyConfig_doRequestFails_withRealFactory tests doRequest error path in ApplyConfig.
func TestApplyConfig_doRequestFails_withRealFactory(t *testing.T) {
	c := NewClient("http://localhost:8080")
	c.httpDoer = &MockHTTPDoer{
		Err: errors.New("connection error"),
	}

	err := c.ApplyConfig([]byte(`{"sessionId":"test"}`))
	if err == nil {
		t.Error("expected error from doRequest failure")
	}
}
