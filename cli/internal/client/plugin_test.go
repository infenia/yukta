// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestListPlugins_success(t *testing.T) {
	expectedPlugins := []PluginSummary{
		{Type: "trigger-1", Category: PluginCategoryTrigger},
		{Type: "processor-1", Category: PluginCategoryProcessor},
		{Type: "terminal-1", Category: PluginCategoryTerminal},
	}

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "GET" {
			t.Errorf("expected GET, got %s", r.Method)
		}
		if r.URL.Path != "/api/plugins" {
			t.Errorf("expected /api/plugins, got %s", r.URL.Path)
		}

		response := map[string]interface{}{
			"data": expectedPlugins,
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(response)
	}))
	defer server.Close()

	c := NewClient(server.URL)
	plugins, err := c.ListPlugins()

	if err != nil {
		t.Errorf("unexpected error: %v", err)
	}
	if len(plugins) != len(expectedPlugins) {
		t.Fatalf("expected %d plugins, got %d", len(expectedPlugins), len(plugins))
	}
	for i, p := range plugins {
		if p.Type != expectedPlugins[i].Type || p.Category != expectedPlugins[i].Category {
			t.Errorf("plugin mismatch at index %d: expected %+v, got %+v", i, expectedPlugins[i], p)
		}
	}
}

func TestListPlugins_empty(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		response := map[string]interface{}{
			"data": []PluginSummary{},
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(response)
	}))
	defer server.Close()

	c := NewClient(server.URL)
	plugins, err := c.ListPlugins()

	if err != nil {
		t.Errorf("unexpected error: %v", err)
	}
	if len(plugins) != 0 {
		t.Errorf("expected 0 plugins, got %d", len(plugins))
	}
}

func TestListPlugins_apiError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		_, _ = w.Write([]byte("Internal server error"))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	_, err := c.ListPlugins()

	if err == nil {
		t.Fatal("expected error, got nil")
	}
	if !strings.Contains(err.Error(), "API error") {
		t.Errorf("expected API error message, got: %v", err)
	}
}

func TestListPlugins_newRequestFails(t *testing.T) {
	c := NewClient("http://localhost:8080")
	c.RequestFactory = &MockRequestFactory{
		Err: errors.New("network error"),
	}

	_, err := c.ListPlugins()
	if err == nil {
		t.Error("expected error from newRequest failure")
	}
}

func TestListPlugins_malformedJSON(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte("invalid json {"))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	_, err := c.ListPlugins()

	if err == nil {
		t.Fatal("expected error for malformed JSON, got nil")
	}
	if !strings.Contains(err.Error(), "unmarshal") {
		t.Errorf("expected unmarshal error, got: %v", err)
	}
}

func TestGetPluginDetails_success(t *testing.T) {
	expectedDetails := PluginDetails{
		Type:         "test-plugin",
		Category:     PluginCategoryProcessor,
		Description:  "A test plugin",
		UsagePattern: "test-pattern",
		UIDesign: UIDesign{
			HTML:   "<div>Test UI</div>",
			Width:  200,
			Height: 150,
		},
		OutputPorts: []string{"output1", "output2"},
	}

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "GET" {
			t.Errorf("expected GET, got %s", r.Method)
		}
		if r.URL.Path != "/api/plugins/test-plugin" {
			t.Errorf("expected /api/plugins/test-plugin, got %s", r.URL.Path)
		}

		response := map[string]interface{}{
			"data": expectedDetails,
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(response)
	}))
	defer server.Close()

	c := NewClient(server.URL)
	details, err := c.GetPluginDetails("test-plugin")

	if err != nil {
		t.Errorf("unexpected error: %v", err)
	}
	if details.Type != expectedDetails.Type {
		t.Errorf("expected type %s, got %s", expectedDetails.Type, details.Type)
	}
	if details.Category != expectedDetails.Category {
		t.Errorf("expected category %s, got %s", expectedDetails.Category, details.Category)
	}
	if details.Description != expectedDetails.Description {
		t.Errorf("expected description %s, got %s", expectedDetails.Description, details.Description)
	}
	if len(details.OutputPorts) != len(expectedDetails.OutputPorts) {
		t.Errorf("expected %d output ports, got %d", len(expectedDetails.OutputPorts), len(details.OutputPorts))
	}
}

func TestGetPluginDetails_notFound(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
		_, _ = w.Write([]byte("Plugin not found"))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	_, err := c.GetPluginDetails("nonexistent")

	if err == nil {
		t.Fatal("expected error, got nil")
	}
	if !strings.Contains(err.Error(), "API error") || !strings.Contains(err.Error(), "404") {
		t.Errorf("expected 404 API error, got: %v", err)
	}
}

func TestGetPluginDetails_emptyPluginType(t *testing.T) {
	c := NewClient("http://localhost:8080")
	_, err := c.GetPluginDetails("")

	if err == nil {
		t.Fatal("expected error for empty pluginType, got nil")
	}
	if !strings.Contains(err.Error(), "empty") {
		t.Errorf("expected empty error message, got: %v", err)
	}
}

func TestGetPluginDetails_newRequestFails(t *testing.T) {
	c := NewClient("http://localhost:8080")
	c.RequestFactory = &MockRequestFactory{
		Err: errors.New("network error"),
	}

	_, err := c.GetPluginDetails("test-plugin")
	if err == nil {
		t.Error("expected error from newRequest failure")
	}
}

func TestGetPluginDetails_malformedJSON(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte("invalid json {"))
	}))
	defer server.Close()

	c := NewClient(server.URL)
	_, err := c.GetPluginDetails("test")

	if err == nil {
		t.Fatal("expected error for malformed JSON, got nil")
	}
	if !strings.Contains(err.Error(), "unmarshal") {
		t.Errorf("expected unmarshal error, got: %v", err)
	}
}
