// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"fmt"
	"io"
	"net/http"
	"strings"
)

// ClientInterface defines the contract for HTTP client operations.
// This allows for easy mocking in tests.
type ClientInterface interface {
	GetSessions() ([]string, error)
	GetSessionDetails(sessionID string) (map[string]interface{}, error)
	GetWorkflow(sessionID, workflowID string) (map[string]interface{}, error)
	ApplyConfig(configJSON []byte) error
}

// Verify that Client implements ClientInterface
var _ ClientInterface = (*Client)(nil)

// MockClient provides a mock implementation of ClientInterface for testing.
// Each method can be customized via function fields.
type MockClient struct {
	GetSessionsFunc       func() ([]string, error)
	GetSessionDetailsFunc func(sessionID string) (map[string]interface{}, error)
	GetWorkflowFunc       func(sessionID, workflowID string) (map[string]interface{}, error)
	ApplyConfigFunc       func(configJSON []byte) error

	// Call tracking for assertions
	GetSessionsCalls       int
	GetSessionDetailsCalls int
	GetWorkflowCalls       int
	ApplyConfigCalls       int
}

// GetSessions mocks the GetSessions method.
func (m *MockClient) GetSessions() ([]string, error) {
	m.GetSessionsCalls++
	if m.GetSessionsFunc != nil {
		return m.GetSessionsFunc()
	}
	return []string{"session-1", "session-2"}, nil
}

// GetSessionDetails mocks the GetSessionDetails method.
func (m *MockClient) GetSessionDetails(sessionID string) (map[string]interface{}, error) {
	m.GetSessionDetailsCalls++
	if m.GetSessionDetailsFunc != nil {
		return m.GetSessionDetailsFunc(sessionID)
	}
	if sessionID == "" {
		return nil, fmt.Errorf("sessionID cannot be empty")
	}
	return map[string]interface{}{
		"sessionId":   sessionID,
		"workflowIds": []string{"workflow-1"},
	}, nil
}

// GetWorkflow mocks the GetWorkflow method.
func (m *MockClient) GetWorkflow(sessionID, workflowID string) (map[string]interface{}, error) {
	m.GetWorkflowCalls++
	if m.GetWorkflowFunc != nil {
		return m.GetWorkflowFunc(sessionID, workflowID)
	}
	if sessionID == "" {
		return nil, fmt.Errorf("sessionID cannot be empty")
	}
	if workflowID == "" {
		return nil, fmt.Errorf("workflowID cannot be empty")
	}
	return map[string]interface{}{
		"id":    workflowID,
		"nodes": []map[string]interface{}{},
	}, nil
}

// ApplyConfig mocks the ApplyConfig method.
func (m *MockClient) ApplyConfig(configJSON []byte) error {
	m.ApplyConfigCalls++
	if m.ApplyConfigFunc != nil {
		return m.ApplyConfigFunc(configJSON)
	}
	if len(configJSON) == 0 {
		return fmt.Errorf("configJSON cannot be empty")
	}
	return nil
}

// MockRequestFactory provides a mock implementation of RequestFactory for testing.
// It allows simulating http.NewRequest failures.
type MockRequestFactory struct {
	NewRequestFunc func(method, urlStr string, body io.Reader) (*http.Request, error)
	Err            error
	CallCount      int
}

// NewRequest implements the RequestFactory interface.
// If NewRequestFunc is set, it calls that; otherwise, it returns the Err field.
func (m *MockRequestFactory) NewRequest(method, urlStr string, body io.Reader) (*http.Request, error) {
	m.CallCount++
	if m.NewRequestFunc != nil {
		return m.NewRequestFunc(method, urlStr, body)
	}
	if m.Err != nil {
		return nil, m.Err
	}
	return http.NewRequest(method, urlStr, body)
}

// MockHTTPDoer provides a mock implementation of HTTPDoer for testing.
// It allows simulating http.Client.Do failures.
type MockHTTPDoer struct {
	DoFunc    func(req *http.Request) (*http.Response, error)
	Err       error
	CallCount int
}

// Do implements the HTTPDoer interface.
// If DoFunc is set, it calls that; otherwise, it returns the Err field.
func (m *MockHTTPDoer) Do(req *http.Request) (*http.Response, error) {
	m.CallCount++
	if m.DoFunc != nil {
		return m.DoFunc(req)
	}
	if m.Err != nil {
		return nil, m.Err
	}
	// Return a default successful response
	return &http.Response{
		StatusCode: 200,
		Status:     "200 OK",
		Header:     make(http.Header),
		Body:       io.NopCloser(strings.NewReader("{}")),
	}, nil
}
