// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package test

import (
	"net/http"
	"strings"
	"testing"
	"time"

	"com.infenia.yukta/go-cli/internal/client"
)

// TestClientInitialization verifies that NewClient correctly initializes the client
// with the provided base URL, handling defaults and URL normalization.
func TestClientInitialization(t *testing.T) {
	tests := []struct {
		name        string
		inputURL    string
		expectedURL string
		description string
	}{
		{
			name:        "empty URL defaults to localhost:8080",
			inputURL:    "",
			expectedURL: "http://localhost:8080",
			description: "Empty URL should default to localhost:8080",
		},
		{
			name:        "custom URL is preserved",
			inputURL:    "http://example.com:9090",
			expectedURL: "http://example.com:9090",
			description: "Custom URL should be preserved exactly",
		},
		{
			name:        "URL with trailing slash is normalized",
			inputURL:    "http://example.com/",
			expectedURL: "http://example.com",
			description: "Trailing slash should be removed for consistency",
		},
		{
			name:        "https URL is preserved",
			inputURL:    "https://secure.example.com/",
			expectedURL: "https://secure.example.com",
			description: "HTTPS URL should be preserved with slash normalized",
		},
		{
			name:        "multiple trailing slashes are removed",
			inputURL:    "http://example.com///",
			expectedURL: "http://example.com",
			description: "Multiple trailing slashes should all be removed",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			c := client.NewClient(tt.inputURL)

			if c.BaseURL != tt.expectedURL {
				t.Errorf(
					"%s: expected BaseURL %q, got %q",
					tt.description,
					tt.expectedURL,
					c.BaseURL,
				)
			}

			// Verify client is not nil
			if c == nil {
				t.Error("NewClient returned nil client")
			}
		})
	}
}

// TestHTTPClientSetup verifies that the HTTP client is properly configured
// with appropriate timeouts and other settings.
func TestHTTPClientSetup(t *testing.T) {
	c := client.NewClient("http://localhost:8080")

	// Verify HTTPClient is not nil
	if c.HTTPClient == nil {
		t.Error("HTTPClient should not be nil")
	}

	// Verify that HTTPClient is of the correct type
	if _, ok := interface{}(c.HTTPClient).(*http.Client); !ok {
		t.Error("HTTPClient should be a pointer to http.Client")
	}

	// Verify timeout is set (default is 0, which means no timeout)
	// The client is configured with Timeout: 0 by design (no timeout)
	expectedTimeout := time.Duration(0)
	if c.HTTPClient.Timeout != expectedTimeout {
		t.Errorf(
			"HTTPClient timeout expected %v, got %v",
			expectedTimeout,
			c.HTTPClient.Timeout,
		)
	}

	// Transport is not verified here since it may be nil until first use.
	// The http.Client uses a default transport automatically when needed.
}

// TestClientURLNormalization tests various URL normalization scenarios
// to ensure consistent behavior across different URL formats.
func TestClientURLNormalization(t *testing.T) {
	tests := []struct {
		name        string
		inputURL    string
		expectedURL string
	}{
		{
			name:        "localhost with port",
			inputURL:    "http://localhost:8080/",
			expectedURL: "http://localhost:8080",
		},
		{
			name:        "IPv4 address",
			inputURL:    "http://127.0.0.1:8080/",
			expectedURL: "http://127.0.0.1:8080",
		},
		{
			name:        "domain with path",
			inputURL:    "http://api.example.com/v1/",
			expectedURL: "http://api.example.com/v1",
		},
		{
			name:        "subdomain",
			inputURL:    "https://api.staging.example.com/",
			expectedURL: "https://api.staging.example.com",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			c := client.NewClient(tt.inputURL)

			if c.BaseURL != tt.expectedURL {
				t.Errorf(
					"URL normalization failed: expected %q, got %q",
					tt.expectedURL,
					c.BaseURL,
				)
			}
		})
	}
}

// TestClientHTTPHeadersConfiguration verifies that the client can be used
// to make requests with proper headers (tested through client structure).
func TestClientHTTPHeadersConfiguration(t *testing.T) {
	c := client.NewClient("http://localhost:8080")

	if c.HTTPClient == nil {
		t.Fatal("HTTPClient should not be nil")
	}

	// Verify that the client is ready to make requests
	// The actual header setup happens in newRequest method
	if c.BaseURL == "" {
		t.Error("BaseURL should not be empty")
	}

	// Verify BaseURL is a valid URL format (starts with http)
	if !strings.HasPrefix(c.BaseURL, "http://") && !strings.HasPrefix(c.BaseURL, "https://") {
		t.Errorf("BaseURL should start with http:// or https://, got %q", c.BaseURL)
	}
}

// TestMultipleClientInstances verifies that multiple client instances
// can be created independently without interference.
func TestMultipleClientInstances(t *testing.T) {
	client1 := client.NewClient("http://localhost:8080")
	client2 := client.NewClient("http://example.com:9090")
	client3 := client.NewClient("")

	if client1.BaseURL != "http://localhost:8080" {
		t.Errorf("client1 BaseURL mismatch: got %q", client1.BaseURL)
	}

	if client2.BaseURL != "http://example.com:9090" {
		t.Errorf("client2 BaseURL mismatch: got %q", client2.BaseURL)
	}

	if client3.BaseURL != "http://localhost:8080" {
		t.Errorf("client3 should default to localhost:8080, got %q", client3.BaseURL)
	}

	// Verify each has their own HTTPClient
	if client1.HTTPClient == client2.HTTPClient {
		t.Error("client1 and client2 should have different HTTPClient instances")
	}
}
