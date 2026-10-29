// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

// snapshotServer answers the limits endpoint: a POST changes the limits, a GET reads them.
func snapshotServer(t *testing.T) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/scheduler/limits" {
			t.Errorf("expected /api/scheduler/limits, got %s", r.URL.Path)
		}
		w.Header().Set("Content-Type", "application/json")
		if r.Method == "POST" {
			if r.URL.Query().Get("maxConcurrent") != "4" || r.URL.Query().Get("maxPerWorkflow") != "2" {
				t.Errorf("unexpected query %s", r.URL.RawQuery)
			}
			_ = json.NewEncoder(w).Encode(map[string]interface{}{
				"data": map[string]int{"maxConcurrent": 4, "maxPerWorkflow": 2},
			})
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]interface{}{
			"data": map[string]int{
				"maxConcurrent":  4,
				"maxPerWorkflow": 2,
				"running":        1,
				"queued":         3,
				"queueCapacity":  10,
			},
		})
	}))
}

func TestGetSchedulerLimits_readsTheSnapshot(t *testing.T) {
	server := snapshotServer(t)
	defer server.Close()

	limits, err := NewClient(server.URL).GetSchedulerLimits()

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	want := SchedulerLimits{MaxConcurrent: 4, MaxPerWorkflow: 2, Running: 1, Queued: 3, QueueCapacity: 10}
	if limits != want {
		t.Errorf("expected %+v, got %+v", want, limits)
	}
}

func TestSetSchedulerLimits_postsThenReadsTheFullSnapshot(t *testing.T) {
	server := snapshotServer(t)
	defer server.Close()

	limits, err := NewClient(server.URL).SetSchedulerLimits(4, 2)

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if limits.Queued != 3 || limits.QueueCapacity != 10 {
		t.Errorf("expected the full snapshot after the change, got %+v", limits)
	}
}

func TestSetSchedulerLimits_refusesNegativeLimits(t *testing.T) {
	if _, err := NewClient("http://unused").SetSchedulerLimits(-1, 0); err == nil {
		t.Error("expected an error for a negative limit")
	}
}

func TestSetSchedulerLimits_postFailureIsReturned(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		http.Error(w, "boom", http.StatusInternalServerError)
	}))
	defer server.Close()

	if _, err := NewClient(server.URL).SetSchedulerLimits(4, 2); err == nil {
		t.Error("expected the POST failure to be returned")
	}
}

func TestGetSchedulerLimits_invalidJSONFails(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte("not json"))
	}))
	defer server.Close()

	if _, err := NewClient(server.URL).GetSchedulerLimits(); err == nil {
		t.Error("expected an unmarshal error")
	}
}

func TestGetSchedulerLimits_badURLFails(t *testing.T) {
	if _, err := NewClient("://bad-url").GetSchedulerLimits(); err == nil {
		t.Error("expected an error for a malformed base URL")
	}
}
