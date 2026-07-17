// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"context"
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
	ListPlugins() ([]PluginSummary, error)
	GetPluginDetails(pluginType string) (PluginDetails, error)
	GetActiveNodesInWorkflow(workflowID string) ([]string, error)
	GetAllActiveNodes() ([]string, error)
	GetLastHeartbeat(workflowID, nodeID string) (map[string]interface{}, error)
	SendCommand(workflowID, nodeID string, commandPayloadJSON []byte) (map[string]interface{}, error)
	StreamExecutionLogs(ctx context.Context, sessionID, executionID string, onLine func(line string) error) error
	StartWorkflow(sessionID, workflowID string) (WorkflowStartResponse, error)
	StopWorkflow(sessionID, workflowID string) (WorkflowStopResponse, error)
	StopExecution(executionID string) (WorkflowStartResponse, error)
	RestartExecution(executionID string) (WorkflowStartResponse, error)
	RestartFromNode(executionID, fromNodeID string) (WorkflowStartResponse, error)
	PauseWorkflow(sessionID, executionID string) (WorkflowStartResponse, error)
	ResumeWorkflow(sessionID, executionID string) (WorkflowStartResponse, error)
	GetWorkflowStatus(sessionID, executionID string) (WorkflowProgress, error)
	GetWorkflowHistory(sessionID string) ([]WorkflowExecutionSummary, error)
	PauseNode(sessionID, executionID, nodeID string) (WorkflowStartResponse, error)
	ResumeNode(sessionID, executionID, nodeID string) (WorkflowStartResponse, error)
	EnableStepMode(sessionID, executionID, nodeID string) (WorkflowStartResponse, error)
	DisableStepMode(sessionID, executionID, nodeID string) (WorkflowStartResponse, error)
	StepNode(sessionID, executionID, nodeID string) (WorkflowStartResponse, error)
	StopNode(sessionID, executionID, nodeID string, immediate bool, reason string) (WorkflowStartResponse, error)
	SkipNode(sessionID, executionID, nodeID string, skip bool) (WorkflowStartResponse, error)
	StreamWorkflowStatus(ctx context.Context, sessionID, executionID string, includeHistory bool, onProgress func(WorkflowProgress) error) error
}

// Verify that Client implements ClientInterface
var _ ClientInterface = (*Client)(nil)

// MockClient provides a mock implementation of ClientInterface for testing.
// Each method can be customized via function fields.
type MockClient struct {
	GetSessionsFunc              func() ([]string, error)
	GetSessionDetailsFunc        func(sessionID string) (map[string]interface{}, error)
	GetWorkflowFunc              func(sessionID, workflowID string) (map[string]interface{}, error)
	ApplyConfigFunc              func(configJSON []byte) error
	ListPluginsFunc              func() ([]PluginSummary, error)
	GetPluginDetailsFunc         func(pluginType string) (PluginDetails, error)
	GetActiveNodesInWorkflowFunc func(workflowID string) ([]string, error)
	GetAllActiveNodesFunc        func() ([]string, error)
	GetLastHeartbeatFunc         func(workflowID, nodeID string) (map[string]interface{}, error)
	SendCommandFunc              func(workflowID, nodeID string, commandPayloadJSON []byte) (map[string]interface{}, error)
	StreamExecutionLogsFunc      func(ctx context.Context, sessionID, executionID string, onLine func(line string) error) error

	StartWorkflowFunc        func(sessionID, workflowID string) (WorkflowStartResponse, error)
	StopWorkflowFunc         func(sessionID, workflowID string) (WorkflowStopResponse, error)
	StopExecutionFunc        func(executionID string) (WorkflowStartResponse, error)
	RestartExecutionFunc     func(executionID string) (WorkflowStartResponse, error)
	RestartFromNodeFunc      func(executionID, fromNodeID string) (WorkflowStartResponse, error)
	PauseWorkflowFunc        func(sessionID, executionID string) (WorkflowStartResponse, error)
	ResumeWorkflowFunc       func(sessionID, executionID string) (WorkflowStartResponse, error)
	GetWorkflowStatusFunc    func(sessionID, executionID string) (WorkflowProgress, error)
	GetWorkflowHistoryFunc   func(sessionID string) ([]WorkflowExecutionSummary, error)
	PauseNodeFunc            func(sessionID, executionID, nodeID string) (WorkflowStartResponse, error)
	ResumeNodeFunc           func(sessionID, executionID, nodeID string) (WorkflowStartResponse, error)
	EnableStepModeFunc       func(sessionID, executionID, nodeID string) (WorkflowStartResponse, error)
	DisableStepModeFunc      func(sessionID, executionID, nodeID string) (WorkflowStartResponse, error)
	StepNodeFunc             func(sessionID, executionID, nodeID string) (WorkflowStartResponse, error)
	StopNodeFunc             func(sessionID, executionID, nodeID string, immediate bool, reason string) (WorkflowStartResponse, error)
	SkipNodeFunc             func(sessionID, executionID, nodeID string, skip bool) (WorkflowStartResponse, error)
	StreamWorkflowStatusFunc func(ctx context.Context, sessionID, executionID string, includeHistory bool, onProgress func(WorkflowProgress) error) error

	// Call tracking for assertions
	GetSessionsCalls              int
	GetSessionDetailsCalls        int
	GetWorkflowCalls              int
	ApplyConfigCalls              int
	ListPluginsCalls              int
	GetPluginDetailsCalls         int
	GetActiveNodesInWorkflowCalls int
	GetAllActiveNodesCalls        int
	GetLastHeartbeatCalls         int
	SendCommandCalls              int
	StreamExecutionLogsCalls      int
	StartWorkflowCalls            int
	StopWorkflowCalls             int
	StopExecutionCalls            int
	RestartExecutionCalls         int
	RestartFromNodeCalls          int
	PauseWorkflowCalls            int
	ResumeWorkflowCalls           int
	GetWorkflowStatusCalls        int
	GetWorkflowHistoryCalls       int
	PauseNodeCalls                int
	ResumeNodeCalls               int
	EnableStepModeCalls           int
	DisableStepModeCalls          int
	StepNodeCalls                 int
	StopNodeCalls                 int
	SkipNodeCalls                 int
	StreamWorkflowStatusCalls     int
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

// ListPlugins mocks the ListPlugins method.
func (m *MockClient) ListPlugins() ([]PluginSummary, error) {
	m.ListPluginsCalls++
	if m.ListPluginsFunc != nil {
		return m.ListPluginsFunc()
	}
	return []PluginSummary{
		{Type: "plugin-1", Category: PluginCategoryTrigger},
		{Type: "plugin-2", Category: PluginCategoryProcessor},
	}, nil
}

// GetPluginDetails mocks the GetPluginDetails method.
func (m *MockClient) GetPluginDetails(pluginType string) (PluginDetails, error) {
	m.GetPluginDetailsCalls++
	if m.GetPluginDetailsFunc != nil {
		return m.GetPluginDetailsFunc(pluginType)
	}
	if pluginType == "" {
		return PluginDetails{}, fmt.Errorf("pluginType cannot be empty")
	}
	return PluginDetails{
		Type:         pluginType,
		Category:     PluginCategoryProcessor,
		Description:  "Test plugin",
		UsagePattern: "test-pattern",
		UIDesign:     UIDesign{HTML: "<div>Test</div>", Width: 100, Height: 100},
		OutputPorts:  []string{"output1"},
	}, nil
}

// GetActiveNodesInWorkflow mocks the GetActiveNodesInWorkflow method.
func (m *MockClient) GetActiveNodesInWorkflow(workflowID string) ([]string, error) {
	m.GetActiveNodesInWorkflowCalls++
	if m.GetActiveNodesInWorkflowFunc != nil {
		return m.GetActiveNodesInWorkflowFunc(workflowID)
	}
	if workflowID == "" {
		return nil, fmt.Errorf("workflowID cannot be empty")
	}
	return []string{"node-1", "node-2"}, nil
}

// GetAllActiveNodes mocks the GetAllActiveNodes method.
func (m *MockClient) GetAllActiveNodes() ([]string, error) {
	m.GetAllActiveNodesCalls++
	if m.GetAllActiveNodesFunc != nil {
		return m.GetAllActiveNodesFunc()
	}
	return []string{"global-node-1", "global-node-2"}, nil
}

// GetLastHeartbeat mocks the GetLastHeartbeat method.
func (m *MockClient) GetLastHeartbeat(workflowID, nodeID string) (map[string]interface{}, error) {
	m.GetLastHeartbeatCalls++
	if m.GetLastHeartbeatFunc != nil {
		return m.GetLastHeartbeatFunc(workflowID, nodeID)
	}
	if workflowID == "" {
		return nil, fmt.Errorf("workflowID cannot be empty")
	}
	if nodeID == "" {
		return nil, fmt.Errorf("nodeID cannot be empty")
	}
	return map[string]interface{}{
		"timestamp": "2026-07-17T10:00:00Z",
		"nodeId":    nodeID,
		"status":    "RUNNING",
	}, nil
}

// SendCommand mocks the SendCommand method.
func (m *MockClient) SendCommand(workflowID, nodeID string, commandPayloadJSON []byte) (map[string]interface{}, error) {
	m.SendCommandCalls++
	if m.SendCommandFunc != nil {
		return m.SendCommandFunc(workflowID, nodeID, commandPayloadJSON)
	}
	if workflowID == "" {
		return nil, fmt.Errorf("workflowID cannot be empty")
	}
	if nodeID == "" {
		return nil, fmt.Errorf("nodeID cannot be empty")
	}
	if len(commandPayloadJSON) == 0 {
		return nil, fmt.Errorf("commandPayloadJSON cannot be empty")
	}
	return map[string]interface{}{
		"status":  "success",
		"message": "Command executed",
	}, nil
}

// StreamExecutionLogs mocks the StreamExecutionLogs method.
func (m *MockClient) StreamExecutionLogs(
	ctx context.Context,
	sessionID string,
	executionID string,
	onLine func(line string) error,
) error {
	m.StreamExecutionLogsCalls++
	if m.StreamExecutionLogsFunc != nil {
		return m.StreamExecutionLogsFunc(ctx, sessionID, executionID, onLine)
	}
	if sessionID == "" {
		return fmt.Errorf("sessionID cannot be empty")
	}
	if executionID == "" {
		return fmt.Errorf("executionID cannot be empty")
	}
	// Call onLine with a mock log entry
	return onLine("Mock log entry")
}

// StartWorkflow mocks the StartWorkflow method.
func (m *MockClient) StartWorkflow(sessionID, workflowID string) (WorkflowStartResponse, error) {
	m.StartWorkflowCalls++
	if m.StartWorkflowFunc != nil {
		return m.StartWorkflowFunc(sessionID, workflowID)
	}
	if sessionID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("sessionID cannot be empty")
	}
	if workflowID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("workflowID cannot be empty")
	}
	return WorkflowStartResponse{ExecutionID: "exec-123"}, nil
}

// StopWorkflow mocks the StopWorkflow method.
func (m *MockClient) StopWorkflow(sessionID, workflowID string) (WorkflowStopResponse, error) {
	m.StopWorkflowCalls++
	if m.StopWorkflowFunc != nil {
		return m.StopWorkflowFunc(sessionID, workflowID)
	}
	if sessionID == "" {
		return WorkflowStopResponse{}, fmt.Errorf("sessionID cannot be empty")
	}
	if workflowID == "" {
		return WorkflowStopResponse{}, fmt.Errorf("workflowID cannot be empty")
	}
	return WorkflowStopResponse{ExecutionIDs: []string{"exec-123"}}, nil
}

// StopExecution mocks the StopExecution method.
func (m *MockClient) StopExecution(executionID string) (WorkflowStartResponse, error) {
	m.StopExecutionCalls++
	if m.StopExecutionFunc != nil {
		return m.StopExecutionFunc(executionID)
	}
	if executionID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("executionID cannot be empty")
	}
	return WorkflowStartResponse{ExecutionID: executionID}, nil
}

// RestartExecution mocks the RestartExecution method.
func (m *MockClient) RestartExecution(executionID string) (WorkflowStartResponse, error) {
	m.RestartExecutionCalls++
	if m.RestartExecutionFunc != nil {
		return m.RestartExecutionFunc(executionID)
	}
	if executionID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("executionID cannot be empty")
	}
	return WorkflowStartResponse{ExecutionID: executionID}, nil
}

// RestartFromNode mocks the RestartFromNode method.
func (m *MockClient) RestartFromNode(executionID, fromNodeID string) (WorkflowStartResponse, error) {
	m.RestartFromNodeCalls++
	if m.RestartFromNodeFunc != nil {
		return m.RestartFromNodeFunc(executionID, fromNodeID)
	}
	if executionID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("executionID cannot be empty")
	}
	if fromNodeID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("fromNodeID cannot be empty")
	}
	return WorkflowStartResponse{ExecutionID: executionID}, nil
}

// PauseWorkflow mocks the PauseWorkflow method.
func (m *MockClient) PauseWorkflow(sessionID, executionID string) (WorkflowStartResponse, error) {
	m.PauseWorkflowCalls++
	if m.PauseWorkflowFunc != nil {
		return m.PauseWorkflowFunc(sessionID, executionID)
	}
	if sessionID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("sessionID cannot be empty")
	}
	if executionID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("executionID cannot be empty")
	}
	return WorkflowStartResponse{ExecutionID: executionID}, nil
}

// ResumeWorkflow mocks the ResumeWorkflow method.
func (m *MockClient) ResumeWorkflow(sessionID, executionID string) (WorkflowStartResponse, error) {
	m.ResumeWorkflowCalls++
	if m.ResumeWorkflowFunc != nil {
		return m.ResumeWorkflowFunc(sessionID, executionID)
	}
	if sessionID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("sessionID cannot be empty")
	}
	if executionID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("executionID cannot be empty")
	}
	return WorkflowStartResponse{ExecutionID: executionID}, nil
}

// GetWorkflowStatus mocks the GetWorkflowStatus method.
func (m *MockClient) GetWorkflowStatus(sessionID, executionID string) (WorkflowProgress, error) {
	m.GetWorkflowStatusCalls++
	if m.GetWorkflowStatusFunc != nil {
		return m.GetWorkflowStatusFunc(sessionID, executionID)
	}
	if sessionID == "" {
		return WorkflowProgress{}, fmt.Errorf("sessionID cannot be empty")
	}
	if executionID == "" {
		return WorkflowProgress{}, fmt.Errorf("executionID cannot be empty")
	}
	return WorkflowProgress{
		ExecutionID: executionID,
		SessionID:   sessionID,
		Status:      "RUNNING",
		Tasks:       []TaskProgress{},
	}, nil
}

// GetWorkflowHistory mocks the GetWorkflowHistory method.
func (m *MockClient) GetWorkflowHistory(sessionID string) ([]WorkflowExecutionSummary, error) {
	m.GetWorkflowHistoryCalls++
	if m.GetWorkflowHistoryFunc != nil {
		return m.GetWorkflowHistoryFunc(sessionID)
	}
	if sessionID == "" {
		return nil, fmt.Errorf("sessionID cannot be empty")
	}
	return []WorkflowExecutionSummary{
		{ExecutionID: "exec-1", WorkflowID: "wf-1", Status: "COMPLETED"},
	}, nil
}

// PauseNode mocks the PauseNode method.
func (m *MockClient) PauseNode(sessionID, executionID, nodeID string) (WorkflowStartResponse, error) {
	m.PauseNodeCalls++
	if m.PauseNodeFunc != nil {
		return m.PauseNodeFunc(sessionID, executionID, nodeID)
	}
	if sessionID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("sessionID cannot be empty")
	}
	if executionID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("executionID cannot be empty")
	}
	if nodeID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("nodeID cannot be empty")
	}
	return WorkflowStartResponse{ExecutionID: executionID}, nil
}

// ResumeNode mocks the ResumeNode method.
func (m *MockClient) ResumeNode(sessionID, executionID, nodeID string) (WorkflowStartResponse, error) {
	m.ResumeNodeCalls++
	if m.ResumeNodeFunc != nil {
		return m.ResumeNodeFunc(sessionID, executionID, nodeID)
	}
	if sessionID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("sessionID cannot be empty")
	}
	if executionID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("executionID cannot be empty")
	}
	if nodeID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("nodeID cannot be empty")
	}
	return WorkflowStartResponse{ExecutionID: executionID}, nil
}

// EnableStepMode mocks the EnableStepMode method.
func (m *MockClient) EnableStepMode(sessionID, executionID, nodeID string) (WorkflowStartResponse, error) {
	m.EnableStepModeCalls++
	if m.EnableStepModeFunc != nil {
		return m.EnableStepModeFunc(sessionID, executionID, nodeID)
	}
	if sessionID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("sessionID cannot be empty")
	}
	if executionID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("executionID cannot be empty")
	}
	if nodeID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("nodeID cannot be empty")
	}
	return WorkflowStartResponse{ExecutionID: executionID}, nil
}

// DisableStepMode mocks the DisableStepMode method.
func (m *MockClient) DisableStepMode(sessionID, executionID, nodeID string) (WorkflowStartResponse, error) {
	m.DisableStepModeCalls++
	if m.DisableStepModeFunc != nil {
		return m.DisableStepModeFunc(sessionID, executionID, nodeID)
	}
	if sessionID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("sessionID cannot be empty")
	}
	if executionID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("executionID cannot be empty")
	}
	if nodeID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("nodeID cannot be empty")
	}
	return WorkflowStartResponse{ExecutionID: executionID}, nil
}

// StepNode mocks the StepNode method.
func (m *MockClient) StepNode(sessionID, executionID, nodeID string) (WorkflowStartResponse, error) {
	m.StepNodeCalls++
	if m.StepNodeFunc != nil {
		return m.StepNodeFunc(sessionID, executionID, nodeID)
	}
	if sessionID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("sessionID cannot be empty")
	}
	if executionID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("executionID cannot be empty")
	}
	if nodeID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("nodeID cannot be empty")
	}
	return WorkflowStartResponse{ExecutionID: executionID}, nil
}

// StopNode mocks the StopNode method.
func (m *MockClient) StopNode(sessionID, executionID, nodeID string, immediate bool, reason string) (WorkflowStartResponse, error) {
	m.StopNodeCalls++
	if m.StopNodeFunc != nil {
		return m.StopNodeFunc(sessionID, executionID, nodeID, immediate, reason)
	}
	if sessionID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("sessionID cannot be empty")
	}
	if executionID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("executionID cannot be empty")
	}
	if nodeID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("nodeID cannot be empty")
	}
	return WorkflowStartResponse{ExecutionID: executionID}, nil
}

// SkipNode mocks the SkipNode method.
func (m *MockClient) SkipNode(sessionID, executionID, nodeID string, skip bool) (WorkflowStartResponse, error) {
	m.SkipNodeCalls++
	if m.SkipNodeFunc != nil {
		return m.SkipNodeFunc(sessionID, executionID, nodeID, skip)
	}
	if sessionID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("sessionID cannot be empty")
	}
	if executionID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("executionID cannot be empty")
	}
	if nodeID == "" {
		return WorkflowStartResponse{}, fmt.Errorf("nodeID cannot be empty")
	}
	return WorkflowStartResponse{ExecutionID: executionID}, nil
}

// StreamWorkflowStatus mocks the StreamWorkflowStatus method.
func (m *MockClient) StreamWorkflowStatus(
	ctx context.Context,
	sessionID, executionID string,
	includeHistory bool,
	onProgress func(WorkflowProgress) error,
) error {
	m.StreamWorkflowStatusCalls++
	if m.StreamWorkflowStatusFunc != nil {
		return m.StreamWorkflowStatusFunc(ctx, sessionID, executionID, includeHistory, onProgress)
	}
	if sessionID == "" {
		return fmt.Errorf("sessionID cannot be empty")
	}
	if executionID == "" {
		return fmt.Errorf("executionID cannot be empty")
	}
	// Simulate a single progress update
	return onProgress(WorkflowProgress{
		ExecutionID: executionID,
		SessionID:   sessionID,
		Status:      "RUNNING",
		Tasks:       []TaskProgress{},
	})
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
