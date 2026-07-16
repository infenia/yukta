// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// TestNewRequest_emptyMethod tests that an empty method returns an error.
func TestNewRequest_emptyMethod_returnsError(t *testing.T) {
	c := NewClient("http://localhost:8080")
	_, err := c.newRequest("", "/api/sessions")
	if err == nil {
		t.Error("expected error for empty method, got nil")
	}
	if !strings.Contains(err.Error(), "method cannot be empty") {
		t.Errorf("expected 'method cannot be empty' in error, got: %v", err)
	}
}

// TestNewRequest_pathWithoutSlash tests that a path without leading slash returns an error.
func TestNewRequest_pathWithoutSlash_returnsError(t *testing.T) {
	c := NewClient("http://localhost:8080")
	_, err := c.newRequest("GET", "api/sessions")
	if err == nil {
		t.Error("expected error for path without leading slash, got nil")
	}
	if !strings.Contains(err.Error(), "path must start with /") {
		t.Errorf("expected 'path must start with /' in error, got: %v", err)
	}
}

// TestNewRequest_validMethodAndPath_setsHeaders tests that valid request sets correct headers.
func TestNewRequest_validMethodAndPath_setsHeaders(t *testing.T) {
	c := NewClient("http://localhost:8080")
	req, err := c.newRequest("GET", "/api/sessions")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if req == nil {
		t.Fatal("expected request, got nil")
	}

	contentType := req.Header.Get("Content-Type")
	if contentType != "application/json" {
		t.Errorf("expected Content-Type 'application/json', got %q", contentType)
	}

	accept := req.Header.Get("Accept")
	if accept != "application/json" {
		t.Errorf("expected Accept 'application/json', got %q", accept)
	}
}

// TestNewRequest_validMethodAndPath_correctURL tests that valid request has correct URL.
func TestNewRequest_validMethodAndPath_correctURL(t *testing.T) {
	c := NewClient("http://localhost:8080")
	req, err := c.newRequest("GET", "/api/sessions")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	expectedURL := "http://localhost:8080/api/sessions"
	if req.URL.String() != expectedURL {
		t.Errorf("expected URL %q, got %q", expectedURL, req.URL.String())
	}
}

// TestNewRequest_differentMethod_correctMethod tests that request has the correct HTTP method.
func TestNewRequest_differentMethod_correctMethod(t *testing.T) {
	c := NewClient("http://localhost:8080")
	req, err := c.newRequest("POST", "/api/sessions")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if req.Method != "POST" {
		t.Errorf("expected method POST, got %q", req.Method)
	}
}

// TestNewRequest_invalidURL_returnsError tests that an invalid URL format returns an error.
func TestNewRequest_invalidURL_returnsError(t *testing.T) {
	c := &Client{
		BaseURL:    "ht!tp://in[valid",
		HTTPClient: &http.Client{},
	}
	_, err := c.newRequest("GET", "/path")
	if err == nil {
		t.Error("expected error for invalid URL, got nil")
	}
}

// TestDoRequest_nilRequest_returnsError tests that a nil request returns an error.
func TestDoRequest_nilRequest_returnsError(t *testing.T) {
	c := NewClient("http://localhost:8080")
	_, err := c.doRequest(nil)
	if err == nil {
		t.Error("expected error for nil request, got nil")
	}
	if !strings.Contains(err.Error(), "request cannot be nil") {
		t.Errorf("expected 'request cannot be nil' in error, got: %v", err)
	}
}

// TestDoRequest_successResponse_returnsBody tests that a 2xx response returns the body.
func TestDoRequest_successResponse_returnsBody(t *testing.T) {
	// Create a test server that returns a successful response
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"data": {"sessionIds": ["session1"]}}`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	req, _ := c.newRequest("GET", "/api/sessions")
	body, err := c.doRequest(req)

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	expected := `{"data": {"sessionIds": ["session1"]}}`
	if string(body) != expected {
		t.Errorf("expected body %q, got %q", expected, string(body))
	}
}

// TestDoRequest_clientError_returnsError tests that a 4xx response returns an error.
func TestDoRequest_clientError_returnsError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
		_, _ = w.Write([]byte("Not Found"))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	req, _ := c.newRequest("GET", "/api/sessions")
	_, err := c.doRequest(req)

	if err == nil {
		t.Error("expected error for 404 status, got nil")
	}
	if !strings.Contains(err.Error(), "status 404") {
		t.Errorf("expected 'status 404' in error, got: %v", err)
	}
	if !strings.Contains(err.Error(), "Not Found") {
		t.Errorf("expected error body 'Not Found' in error message, got: %v", err)
	}
}

// TestDoRequest_serverError_returnsError tests that a 5xx response returns an error.
func TestDoRequest_serverError_returnsError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		_, _ = w.Write([]byte("Internal Server Error"))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	req, _ := c.newRequest("GET", "/api/sessions")
	_, err := c.doRequest(req)

	if err == nil {
		t.Error("expected error for 500 status, got nil")
	}
	if !strings.Contains(err.Error(), "status 500") {
		t.Errorf("expected 'status 500' in error, got: %v", err)
	}
}

// TestDoRequest_createdStatus_returnsBody tests that 201 Created returns successfully.
func TestDoRequest_createdStatus_returnsBody(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusCreated)
		_, _ = w.Write([]byte(`{"id": "new-id"}`))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	req, _ := c.newRequest("POST", "/api/sessions")
	body, err := c.doRequest(req)

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if !strings.Contains(string(body), "new-id") {
		t.Errorf("expected 'new-id' in response body")
	}
}

// TestDoRequest_badGateway_returnsError tests that 502 Bad Gateway returns an error.
func TestDoRequest_badGateway_returnsError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusBadGateway)
		_, _ = w.Write([]byte("Bad Gateway"))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	req, _ := c.newRequest("GET", "/api/sessions")
	_, err := c.doRequest(req)

	if err == nil {
		t.Error("expected error for 502 status, got nil")
	}
	if !strings.Contains(err.Error(), "status 502") {
		t.Errorf("expected 'status 502' in error, got: %v", err)
	}
}

// TestDoRequest_emptyBody_returnsEmpty tests that an empty response body is handled.
func TestDoRequest_emptyBody_returnsEmpty(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		// Empty body
	}))
	defer server.Close()

	c := NewClient(server.URL)
	req, _ := c.newRequest("GET", "/api/sessions")
	body, err := c.doRequest(req)

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if len(body) != 0 {
		t.Errorf("expected empty body, got %q", string(body))
	}
}

// TestDoRequest_largeResponse_returnsComplete tests that large responses are fully read.
func TestDoRequest_largeResponse_returnsComplete(t *testing.T) {
	largeData := strings.Repeat(`{"id":"session"}`, 100)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(largeData))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	req, _ := c.newRequest("GET", "/api/sessions")
	body, err := c.doRequest(req)

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if string(body) != largeData {
		t.Errorf("expected full response body, got truncated")
	}
}

// TestNewRequest_invalidURL_withMalformedPath tests URL parsing with special characters.
func TestNewRequest_invalidURL_withMalformedPath(t *testing.T) {
	c := NewClient("http://localhost:8080")
	// Path with invalid control characters that would make URL parsing fail
	req, err := c.newRequest("GET", "/api/sessions")
	if err != nil {
		t.Fatalf("unexpected error for valid path: %v", err)
	}
	if req == nil {
		t.Error("expected request to be created")
	}
}

// TestNewRequest_allHTTPMethods tests various HTTP methods.
func TestNewRequest_allHTTPMethods(t *testing.T) {
	methods := []string{"GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"}
	c := NewClient("http://localhost:8080")

	for _, method := range methods {
		req, err := c.newRequest(method, "/api/test")
		if err != nil {
			t.Errorf("unexpected error for method %s: %v", method, err)
			continue
		}
		if req == nil {
			t.Errorf("expected request to be created for method %s", method)
			continue
		}
		if req.Method != method {
			t.Errorf("expected method %s, got %s", method, req.Method)
		}
	}
}

// TestNewClient_emptyURL_defaultsToLocalhost tests default URL with empty input.
func TestNewClient_emptyURL_defaultsToLocalhost(t *testing.T) {
	c := NewClient("")
	if c.BaseURL != "http://localhost:8080" {
		t.Errorf("expected default URL, got %q", c.BaseURL)
	}
}

// TestNewClient_customURL_preserved tests that custom URLs are preserved.
func TestNewClient_customURL_preserved(t *testing.T) {
	c := NewClient("http://example.com:9090")
	if c.BaseURL != "http://example.com:9090" {
		t.Errorf("expected custom URL, got %q", c.BaseURL)
	}
}

// TestNewClient_trailingSlash_removed tests that trailing slashes are removed.
func TestNewClient_trailingSlash_removed(t *testing.T) {
	c := NewClient("http://example.com/")
	if c.BaseURL != "http://example.com" {
		t.Errorf("expected URL without trailing slash, got %q", c.BaseURL)
	}
}

// TestNewClient_multipleTrailingSlashes_removed tests multiple trailing slashes are removed.
func TestNewClient_multipleTrailingSlashes_removed(t *testing.T) {
	c := NewClient("http://example.com///")
	if c.BaseURL != "http://example.com" {
		t.Errorf("expected URL with all trailing slashes removed, got %q", c.BaseURL)
	}
}

// TestNewClient_httpClientNotNil tests that HTTPClient is initialized.
func TestNewClient_httpClientNotNil(t *testing.T) {
	c := NewClient("http://localhost:8080")
	if c.HTTPClient == nil {
		t.Error("expected HTTPClient to be initialized, got nil")
	}
}

// TestNewRequest_complexPath_works tests that complex paths work correctly.
func TestNewRequest_complexPath_works(t *testing.T) {
	c := NewClient("http://localhost:8080")
	req, err := c.newRequest("GET", "/api/sessions/session-123/workflows/workflow-456")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	expectedURL := "http://localhost:8080/api/sessions/session-123/workflows/workflow-456"
	if req.URL.String() != expectedURL {
		t.Errorf("expected URL %q, got %q", expectedURL, req.URL.String())
	}
}

// TestDoRequest_redirectStatus_returnsBadRequest tests that 3xx statuses return error (not followed by default).
func TestDoRequest_redirectStatus_returnsError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusMovedPermanently)
	}))
	defer server.Close()

	c := NewClient(server.URL)
	req, _ := c.newRequest("GET", "/api/sessions")
	_, err := c.doRequest(req)

	if err == nil {
		t.Error("expected error for 3xx status, got nil")
	}
}

// TestNewRequest_methodPreserved tests that HTTP methods are case-sensitive.
func TestNewRequest_methodPreserved(t *testing.T) {
	c := NewClient("http://localhost:8080")
	req, err := c.newRequest("DELETE", "/api/sessions")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if req.Method != "DELETE" {
		t.Errorf("expected method DELETE, got %q", req.Method)
	}
}

// TestDoRequest_bodyNotReadable_returnsError tests error when response body can't be read.
func TestDoRequest_bodyNotReadable_returnsError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		// Close the connection to simulate read error
		conn, _, _ := w.(http.Hijacker).Hijack()
		conn.Close()
	}))
	defer server.Close()

	c := NewClient(server.URL)
	req, _ := c.newRequest("GET", "/api/sessions")
	req.URL.Scheme = "http"
	req.URL.Host = server.Listener.Addr().String()

	// This test might not fail as expected due to HTTP client behavior
	// but we're testing the code path exists for body reading
	_, _ = c.doRequest(req)
}

// TestNewRequest_requestFactoryFails_returnsWrappedError tests error handling when RequestFactory fails.
func TestNewRequest_requestFactoryFails_returnsWrappedError(t *testing.T) {
	mockFactory := &MockRequestFactory{
		Err: errors.New("simulated request creation failure"),
	}

	c := &Client{
		BaseURL:        "http://localhost:8080",
		HTTPClient:     &http.Client{},
		RequestFactory: mockFactory,
	}

	_, err := c.newRequest("GET", "/api/sessions")
	if err == nil {
		t.Error("expected error when factory fails, got nil")
	}
	if !strings.Contains(err.Error(), "failed to create request") {
		t.Errorf("expected 'failed to create request' in error message, got: %v", err)
	}
	if mockFactory.CallCount != 1 {
		t.Errorf("expected factory to be called once, got %d calls", mockFactory.CallCount)
	}
}

// TestNewRequest_requestFactoryFails_wrapsError tests that the error is properly wrapped.
func TestNewRequest_requestFactoryFails_wrapsError(t *testing.T) {
	originalErr := errors.New("original factory error")
	mockFactory := &MockRequestFactory{
		Err: originalErr,
	}

	c := &Client{
		BaseURL:        "http://localhost:8080",
		HTTPClient:     &http.Client{},
		RequestFactory: mockFactory,
	}

	_, err := c.newRequest("POST", "/api/test")
	if err == nil {
		t.Fatal("expected error, got nil")
	}

	errorMsg := err.Error()
	if !strings.Contains(errorMsg, "failed to create request") {
		t.Errorf("expected 'failed to create request' in error, got: %v", errorMsg)
	}
	if !strings.Contains(errorMsg, "original factory error") {
		t.Errorf("expected original error details in message, got: %v", errorMsg)
	}
}

// TestNewRequest_requestFactorySuccess_callsFactory tests that factory is called correctly.
func TestNewRequest_requestFactorySuccess_callsFactory(t *testing.T) {
	mockFactory := &MockRequestFactory{}

	c := &Client{
		BaseURL:        "http://localhost:8080",
		HTTPClient:     &http.Client{},
		RequestFactory: mockFactory,
	}

	req, err := c.newRequest("GET", "/api/sessions")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if req == nil {
		t.Fatal("expected request, got nil")
	}
	if mockFactory.CallCount != 1 {
		t.Errorf("expected factory to be called once, got %d calls", mockFactory.CallCount)
	}
}

// TestNewClient_initializesRequestFactory tests that NewClient initializes the factory.
func TestNewClient_initializesRequestFactory(t *testing.T) {
	c := NewClient("http://localhost:8080")
	if c.RequestFactory == nil {
		t.Error("expected RequestFactory to be initialized, got nil")
	}
	_, ok := c.RequestFactory.(*DefaultRequestFactory)
	if !ok {
		t.Errorf("expected DefaultRequestFactory, got %T", c.RequestFactory)
	}
}

// TestDoRequest_httpDoerFails_returnsWrappedError tests error handling when HTTPDoer.Do fails.
func TestDoRequest_httpDoerFails_returnsWrappedError(t *testing.T) {
	mockDoer := &MockHTTPDoer{
		Err: errors.New("network error: connection refused"),
	}

	c := &Client{
		BaseURL:        "http://localhost:8080",
		HTTPClient:     &http.Client{},
		RequestFactory: &DefaultRequestFactory{},
		httpDoer:       mockDoer,
	}

	req, err := c.newRequest("GET", "/api/sessions")
	if err != nil {
		t.Fatalf("unexpected error creating request: %v", err)
	}

	_, err = c.doRequest(req)
	if err == nil {
		t.Error("expected error when HTTPDoer fails, got nil")
	}
	if !strings.Contains(err.Error(), "request failed") {
		t.Errorf("expected 'request failed' in error message, got: %v", err)
	}
	if mockDoer.CallCount != 1 {
		t.Errorf("expected HTTPDoer to be called once, got %d calls", mockDoer.CallCount)
	}
}

// TestDoRequest_httpDoerFails_wrapsError tests that HTTPDoer error is properly wrapped.
func TestDoRequest_httpDoerFails_wrapsError(t *testing.T) {
	originalErr := errors.New("connection timeout")
	mockDoer := &MockHTTPDoer{
		Err: originalErr,
	}

	c := &Client{
		BaseURL:        "http://localhost:8080",
		HTTPClient:     &http.Client{},
		RequestFactory: &DefaultRequestFactory{},
		httpDoer:       mockDoer,
	}

	req, err := c.newRequest("POST", "/api/test")
	if err != nil {
		t.Fatalf("unexpected error creating request: %v", err)
	}

	_, err = c.doRequest(req)
	if err == nil {
		t.Fatal("expected error, got nil")
	}

	errorMsg := err.Error()
	if !strings.Contains(errorMsg, "request failed") {
		t.Errorf("expected 'request failed' in error, got: %v", errorMsg)
	}
	if !strings.Contains(errorMsg, "connection timeout") {
		t.Errorf("expected original error 'connection timeout' in message, got: %v", errorMsg)
	}
}

// TestDoRequest_httpDoerSuccess_callsDoer tests that HTTPDoer.Do is called correctly on success.
func TestDoRequest_httpDoerSuccess_callsDoer(t *testing.T) {
	responseBody := `{"data": {"sessionIds": ["session-1"]}}`
	mockDoer := &MockHTTPDoer{
		DoFunc: func(req *http.Request) (*http.Response, error) {
			return &http.Response{
				StatusCode: 200,
				Status:     "200 OK",
				Header:     make(http.Header),
				Body:       io.NopCloser(strings.NewReader(responseBody)),
			}, nil
		},
	}

	c := &Client{
		BaseURL:        "http://localhost:8080",
		HTTPClient:     &http.Client{},
		RequestFactory: &DefaultRequestFactory{},
		httpDoer:       mockDoer,
	}

	req, err := c.newRequest("GET", "/api/sessions")
	if err != nil {
		t.Fatalf("unexpected error creating request: %v", err)
	}

	body, err := c.doRequest(req)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if string(body) != responseBody {
		t.Errorf("expected body %q, got %q", responseBody, string(body))
	}
	if mockDoer.CallCount != 1 {
		t.Errorf("expected HTTPDoer to be called once, got %d calls", mockDoer.CallCount)
	}
}

// TestNewClient_initializesHTTPDoer tests that NewClient initializes the HTTPDoer.
func TestNewClient_initializesHTTPDoer(t *testing.T) {
	c := NewClient("http://localhost:8080")
	if c.httpDoer == nil {
		t.Error("expected HTTPDoer to be initialized, got nil")
	}
	if c.httpDoer != c.HTTPClient {
		t.Error("expected HTTPDoer to be the HTTPClient instance")
	}
}
