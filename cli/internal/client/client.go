// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

// DefaultTimeout bounds each non-streaming request. Streams are cancelled by their context.
const DefaultTimeout = 30 * time.Second

// Client provides an HTTP client wrapper for communicating with the Yukta API.
type Client struct {
	BaseURL string
	Token   string // optional bearer token sent as Authorization
	// AllowInsecureToken lets Token go over plain http to a host that is not loopback.
	AllowInsecureToken bool
	Timeout            time.Duration // per-request limit for non-streaming calls; <= 0 disables
	HTTPClient         *http.Client
	// Retries is the number of extra attempts for reads and keyed starts on network errors, 429 and
	// 502-504; 0 = none. RetryWait is the first pause between attempts; it doubles each time unless
	// Retry-After says more.
	Retries   int
	RetryWait time.Duration
	// StreamIdle is how long a followed stream may be silent (pings count) before it is treated as
	// dropped; StreamReconnects bounds the reopenings in a row; StreamWait is the first pause.
	StreamIdle       time.Duration
	StreamReconnects int
	StreamWait       time.Duration
}

// NewClient creates a new HTTP client with the given base URL.
// If baseURL is empty, defaults to "http://localhost:8080".
func NewClient(baseURL string) *Client {
	if baseURL == "" {
		baseURL = "http://localhost:8080"
	}
	c := &Client{
		BaseURL:          strings.TrimRight(baseURL, "/"),
		Timeout:          DefaultTimeout,
		StreamIdle:       DefaultStreamIdle,
		StreamReconnects: DefaultStreamReconnects,
		StreamWait:       DefaultStreamWait,
		// No client-wide Timeout on HTTPClient: it would also cut SSE streams.
	}
	// Go keeps Authorization on a same-host redirect whatever the scheme, so re-run the transport
	// guard on every hop to stop an https -> http downgrade from leaking the token.
	c.HTTPClient = &http.Client{CheckRedirect: func(req *http.Request, _ []*http.Request) error {
		return c.checkTokenTransport(req.URL.String())
	}}
	return c
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

	if err := c.checkTokenTransport(urlStr); err != nil {
		return nil, err
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

// checkTokenTransport refuses to send the token in clear text over the network.
func (c *Client) checkTokenTransport(urlStr string) error {
	if c.Token == "" || c.AllowInsecureToken {
		return nil
	}
	u, _ := url.Parse(urlStr) // already parsed by the caller
	if u.Scheme != "http" || isLoopbackHost(u.Hostname()) {
		return nil
	}
	return fmt.Errorf("refusing to send the token over plain http to %q; use https or pass --insecure-token or set YUKTA_INSECURE_TOKEN=1", u.Hostname())
}

func isLoopbackHost(host string) bool {
	if host == "localhost" {
		return true
	}
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}

// newRequest creates a new HTTP request for the given method and path.
// The path should be relative to the base URL (e.g., "/api/sessions").
func (c *Client) newRequest(method string, path string) (*http.Request, error) {
	return c.newRequestWithBody(method, path, nil)
}

// doRequest executes an HTTP request and returns the response body as bytes.
// It handles response status codes and errors appropriately.
func (c *Client) doRequest(req *http.Request) ([]byte, error) {
	body, _, err := c.doRequestHeader(req)
	return body, err
}

// apiError is a non-2xx answer.
type apiError struct {
	status     int
	body       string
	retryAfter time.Duration // from the Retry-After header; 0 if absent
}

func (e *apiError) Error() string {
	hint := ""
	if e.status == http.StatusUnauthorized {
		hint = " (the server needs an API key: set YUKTA_TOKEN, or use --token-file)"
	}
	return fmt.Sprintf("API error: status %d: %s%s", e.status, e.body, hint)
}

// maxRetryWait caps one pause, whatever Retry-After asks for.
const maxRetryWait = 30 * time.Second

// retryable tells whether a failed attempt may be repeated without risk: only reads, or a request
// that carries an Idempotency-Key, and only for a network error or a 429/502/503/504 answer.
func retryable(req *http.Request, err error) bool {
	if req.Method != http.MethodGet && req.Method != http.MethodHead && req.Header.Get("Idempotency-Key") == "" {
		return false
	}
	var ae *apiError
	if errors.As(err, &ae) {
		switch ae.status {
		case http.StatusTooManyRequests, http.StatusBadGateway, http.StatusServiceUnavailable, http.StatusGatewayTimeout:
			return true
		}
		return false
	}
	return true
}

// doRequestHeader is doRequest that also returns the response headers. With c.Retries > 0 it
// repeats a retryable failure, pausing c.RetryWait (doubling) or the server's Retry-After.
func (c *Client) doRequestHeader(req *http.Request) ([]byte, http.Header, error) {
	if req == nil {
		return nil, nil, errors.New("request cannot be nil")
	}
	wait := c.RetryWait
	for attempt := 0; ; attempt++ {
		body, header, err := c.send(req)
		if err == nil || attempt >= c.Retries || !retryable(req, err) {
			return body, header, err
		}
		pause := wait
		var ae *apiError
		if errors.As(err, &ae) && ae.retryAfter > pause {
			pause = ae.retryAfter
		}
		select {
		case <-time.After(min(pause, maxRetryWait)):
		case <-req.Context().Done():
			return nil, nil, err
		}
		wait *= 2
		if req.GetBody != nil {
			if req.Body, err = req.GetBody(); err != nil {
				return nil, nil, fmt.Errorf("failed to rewind request body: %w", err)
			}
		}
	}
}

// send makes one attempt.
func (c *Client) send(req *http.Request) ([]byte, http.Header, error) {

	if c.Timeout > 0 {
		ctx, cancel := context.WithTimeout(req.Context(), c.Timeout)
		defer cancel()
		req = req.WithContext(ctx)
	}

	resp, err := c.HTTPClient.Do(req)
	if err != nil {
		return nil, nil, fmt.Errorf("request failed: %w", err)
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, nil, fmt.Errorf("failed to read response body: %w", err)
	}

	// Check for non-2xx status codes
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		ae := &apiError{status: resp.StatusCode, body: string(body)}
		if secs, convErr := strconv.Atoi(resp.Header.Get("Retry-After")); convErr == nil && secs > 0 {
			ae.retryAfter = time.Duration(secs) * time.Second
		}
		return nil, nil, ae
	}

	return body, resp.Header, nil
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
