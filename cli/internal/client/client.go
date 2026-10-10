// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// DefaultTimeout bounds each non-streaming request. Streams are cancelled by their context.
const DefaultTimeout = 30 * time.Second

// Client provides an HTTP client wrapper for communicating with the Yukta API.
type Client struct {
	BaseURL    string
	Token      string        // optional bearer token sent as Authorization
	Timeout    time.Duration // per-request limit for non-streaming calls; <= 0 disables
	HTTPClient *http.Client
}

// NewClient creates a new HTTP client with the given base URL.
// If baseURL is empty, defaults to "http://localhost:8080".
func NewClient(baseURL string) *Client {
	if baseURL == "" {
		baseURL = "http://localhost:8080"
	}
	return &Client{
		BaseURL: strings.TrimRight(baseURL, "/"),
		Timeout: DefaultTimeout,
		// No client-wide Timeout: it would also cut SSE streams.
		HTTPClient: &http.Client{},
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

	urlStr := strings.TrimRight(c.BaseURL, "/") + path

	// Validate that the URL is properly formed
	_, err := url.Parse(urlStr)
	if err != nil {
		return nil, fmt.Errorf("invalid URL: %w", err)
	}

	var bodyReader io.Reader
	if body != nil {
		bodyReader = bytes.NewReader(body)
	}

	req, err := http.NewRequest(method, urlStr, bodyReader)
	if err != nil {
		return nil, fmt.Errorf("failed to create request: %w", err)
	}

	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")
	if c.Token != "" {
		req.Header.Set("Authorization", "Bearer "+c.Token)
	}

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

	if c.Timeout > 0 {
		ctx, cancel := context.WithTimeout(req.Context(), c.Timeout)
		defer cancel()
		req = req.WithContext(ctx)
	}

	resp, err := c.HTTPClient.Do(req)
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

// apiPath fills the %s placeholders in format with URL-path-escaped args, so IDs
// containing "/", "?" or "#" can't redirect the request to another endpoint.
func apiPath(format string, args ...string) string {
	escaped := make([]any, len(args))
	for i, a := range args {
		escaped[i] = url.PathEscape(a)
	}
	return fmt.Sprintf(format, escaped...)
}
