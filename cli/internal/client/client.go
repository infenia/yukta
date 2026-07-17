// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"bytes"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
)

// RequestFactory abstracts HTTP request creation for testability.
// It allows injecting mock implementations to simulate request creation failures.
type RequestFactory interface {
	NewRequest(method, urlStr string, body io.Reader) (*http.Request, error)
}

// DefaultRequestFactory uses the standard http.NewRequest function.
type DefaultRequestFactory struct{}

// NewRequest creates an HTTP request using the standard library.
func (f *DefaultRequestFactory) NewRequest(method, urlStr string, body io.Reader) (*http.Request, error) {
	return http.NewRequest(method, urlStr, body)
}

// HTTPDoer abstracts the http.Client.Do method for testability.
// It allows injecting mock implementations to simulate HTTP execution failures.
type HTTPDoer interface {
	Do(req *http.Request) (*http.Response, error)
}

// Client provides an HTTP client wrapper for communicating with the Yukta API.
type Client struct {
	BaseURL        string
	HTTPClient     *http.Client
	RequestFactory RequestFactory
	httpDoer       HTTPDoer
}

// NewClient creates a new HTTP client with the given base URL.
// If baseURL is empty, defaults to "http://localhost:8080".
func NewClient(baseURL string) *Client {
	if baseURL == "" {
		baseURL = "http://localhost:8080"
	}
	httpClient := &http.Client{
		Timeout: 0, // No timeout by default; can be customized later
	}
	return &Client{
		BaseURL:        strings.TrimRight(baseURL, "/"),
		HTTPClient:     httpClient,
		RequestFactory: &DefaultRequestFactory{},
		httpDoer:       httpClient,
	}
}

// newRequestWithBody creates a new HTTP request with optional body.
// The path should be relative to the base URL (e.g., "/api/sessions").
// If body is nil, the request will have no body.
func (c *Client) newRequestWithBody(method string, path string, body []byte) (*http.Request, error) {
	if method == "" {
		return nil, errors.New("method cannot be empty")
	}

	if !strings.HasPrefix(path, "/") {
		return nil, errors.New("path must start with /")
	}

	urlStr := c.BaseURL + path

	// Validate that the URL is properly formed
	_, err := url.Parse(urlStr)
	if err != nil {
		return nil, fmt.Errorf("invalid URL: %w", err)
	}

	var bodyReader io.Reader
	if body != nil {
		bodyReader = bytes.NewReader(body)
	}

	req, err := c.RequestFactory.NewRequest(method, urlStr, bodyReader)
	if err != nil {
		return nil, fmt.Errorf("failed to create request: %w", err)
	}

	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")

	return req, nil
}

// newRequest creates a new HTTP request for the given method and path.
// The path should be relative to the base URL (e.g., "/api/sessions").
func (c *Client) newRequest(method string, path string) (*http.Request, error) {
	return c.newRequestWithBody(method, path, nil)
}

// doRequest executes an HTTP request and returns the response body as bytes.
// It handles response status codes and errors appropriately.
func (c *Client) doRequest(req *http.Request) ([]byte, error) {
	if req == nil {
		return nil, errors.New("request cannot be nil")
	}

	resp, err := c.httpDoer.Do(req)
	if err != nil {
		return nil, fmt.Errorf("request failed: %w", err)
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("failed to read response body: %w", err)
	}

	// Check for non-2xx status codes
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, fmt.Errorf("API error: status %d: %s", resp.StatusCode, string(body))
	}

	return body, nil
}
