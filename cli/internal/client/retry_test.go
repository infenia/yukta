// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

// flaky answers with status for the first failures calls, then 200.
func flaky(t *testing.T, status, failures int, header map[string]string) (*Client, *atomic.Int32, *[]string) {
	t.Helper()
	var calls atomic.Int32
	keys := []string{}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		keys = append(keys, r.Header.Get("Idempotency-Key"))
		if int(calls.Add(1)) <= failures {
			for k, v := range header {
				w.Header().Set(k, v)
			}
			w.WriteHeader(status)
			return
		}
		_, _ = w.Write([]byte(`{"data":["a"]}`))
	}))
	t.Cleanup(srv.Close)
	c := NewClient(srv.URL)
	c.Retries = 3
	c.RetryWait = time.Millisecond
	return c, &calls, &keys
}

func TestRetry_getRetriesTransientStatusesAndSucceeds(t *testing.T) {
	for _, status := range []int{429, 502, 503, 504} {
		c, calls, _ := flaky(t, status, 2, nil)
		if got, err := c.GetSessions(); err != nil || len(got) != 1 || calls.Load() != 3 {
			t.Errorf("status %d: got=%v err=%v calls=%d", status, got, err, calls.Load())
		}
	}
}

func TestRetry_givesUpAfterTheConfiguredAttempts(t *testing.T) {
	c, calls, _ := flaky(t, 503, 100, nil)
	if _, err := c.GetSessions(); err == nil || calls.Load() != 4 {
		t.Errorf("err=%v calls=%d, want an error after 1+3 calls", err, calls.Load())
	}
}

func TestRetry_isOffByDefaultAndSkipsOtherErrors(t *testing.T) {
	c, calls, _ := flaky(t, 503, 1, nil)
	c.Retries = 0
	if _, err := c.GetSessions(); err == nil || calls.Load() != 1 {
		t.Errorf("err=%v calls=%d", err, calls.Load())
	}
	c, calls, _ = flaky(t, 404, 1, nil)
	if _, err := c.GetSessions(); err == nil || calls.Load() != 1 {
		t.Errorf("404 must not be retried: err=%v calls=%d", err, calls.Load())
	}
}

func TestRetry_honoursRetryAfter(t *testing.T) {
	c, _, _ := flaky(t, 429, 1, map[string]string{"Retry-After": "1"})
	start := time.Now()
	if _, err := c.GetSessions(); err != nil || time.Since(start) < time.Second {
		t.Errorf("err=%v waited %v, want >= 1s", err, time.Since(start))
	}
}

func TestRetry_networkErrorIsRetriedForReads(t *testing.T) {
	c := NewClient("http://127.0.0.1:1")
	c.Retries = 1
	c.RetryWait = time.Millisecond
	if _, err := c.GetSessions(); err == nil {
		t.Error("expected an error")
	}
}

func TestRetry_startRetriesWithOneGeneratedKey(t *testing.T) {
	c, calls, keys := flaky(t, 503, 2, nil)
	c.Retries = 3
	srvBody := func() error { _, err := c.StartWorkflow("s", "w", map[string]any{"k": "v"}); return err }
	_ = srvBody() // the mock returns a list; the decode error is irrelevant, the attempts are the test
	if calls.Load() != 3 {
		t.Fatalf("calls=%d", calls.Load())
	}
	k := *keys
	if k[0] == "" || k[0] != k[1] || k[1] != k[2] {
		t.Errorf("keys differ between attempts: %v", k)
	}
}

func TestRetry_startWithoutRetriesSendsNoKey_andAPostWithoutAKeyIsNeverRetried(t *testing.T) {
	c, calls, keys := flaky(t, 503, 1, nil)
	c.Retries = 0
	_, _ = c.StartWorkflow("s", "w", nil)
	if (*keys)[0] != "" || calls.Load() != 1 {
		t.Errorf("keys=%v calls=%d", *keys, calls.Load())
	}
	c, calls, _ = flaky(t, 503, 1, nil)
	req, _ := c.newRequestWithBody("POST", "/api/x", []byte(`{}`))
	if _, err := c.doRequest(req); err == nil || calls.Load() != 1 {
		t.Errorf("unkeyed POST was retried: err=%v calls=%d", err, calls.Load())
	}
}

func TestRetry_cancelledContextStopsWaiting(t *testing.T) {
	c, _, _ := flaky(t, 503, 100, nil)
	c.RetryWait = time.Hour
	req, _ := c.newRequest("GET", "/api/sessions")
	ctx, cancel := contextWithTimeout(20 * time.Millisecond)
	defer cancel()
	if _, err := c.doRequest(req.WithContext(ctx)); err == nil {
		t.Error("expected an error")
	}
}

func contextWithTimeout(d time.Duration) (context.Context, context.CancelFunc) {
	return context.WithTimeout(context.Background(), d)
}

func TestRetry_bodyThatCannotBeRewound_failsTheRetry(t *testing.T) {
	c, _, _ := flaky(t, 503, 100, map[string]string{})
	req, _ := c.newRequestWithBody("POST", "/api/x", []byte(`{}`))
	req.Header.Set("Idempotency-Key", "k")
	req.GetBody = func() (io.ReadCloser, error) { return nil, errors.New("gone") }
	if _, err := c.doRequest(req); err == nil || !strings.Contains(err.Error(), "rewind") {
		t.Errorf("err=%v", err)
	}
}
