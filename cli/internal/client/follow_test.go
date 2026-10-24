// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

// answerDone replies to the "has the execution ended?" check that follows a stream with a finished
// execution, and returns true; any other request is left to the caller.
func answerDone(w http.ResponseWriter, r *http.Request) bool {
	if !strings.HasPrefix(r.URL.Path, "/api/executions/") || strings.HasSuffix(r.URL.Path, "/stream") {
		return false
	}
	_, _ = w.Write([]byte(`{"data":{"status":"SUCCESS"}}`))
	return true
}

func fastClient(url string) *Client {
	c := NewClient(url)
	c.StreamWait = time.Millisecond
	return c
}

func TestEventStream_cleanCloseIsAnError_notSuccess(t *testing.T) {
	srv := eventServer(t, "", `{"type":"LogLine","event":{"line":"x"}}`)
	defer srv.Close()

	err := fastClient(srv.URL).StreamEvents(context.Background(), "", "", "", func(ExecutionEvent) error { return nil })

	if err == nil || !strings.Contains(err.Error(), "ended unexpectedly") {
		t.Errorf("err=%v", err)
	}
}

func TestLogs_reconnectWithLastEventID_untilTheExecutionIsDone(t *testing.T) {
	var connections atomic.Int32
	var sentIDs []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/api/executions/") {
			status := "RUNNING"
			if connections.Load() >= 2 {
				status = "SUCCESS"
			}
			fmt.Fprintf(w, `{"data":{"status":%q}}`, status)
			return
		}
		sentIDs = append(sentIDs, r.Header.Get("Last-Event-ID"))
		w.Header().Set("Content-Type", "text/event-stream")
		if connections.Add(1) == 1 {
			fmt.Fprint(w, "id: 1\ndata: one\n\nid: 2\ndata: two\n\n")
		} else {
			fmt.Fprint(w, "id: 3\ndata: three\n\n")
		}
	}))
	defer srv.Close()

	var lines []string
	err := fastClient(srv.URL).StreamExecutionLogs(context.Background(), "s", "e", func(l string) error {
		lines = append(lines, l)
		return nil
	})

	if err != nil || strings.Join(lines, ",") != "one,two,three" {
		t.Fatalf("lines=%v err=%v", lines, err)
	}
	if strings.Join(sentIDs, "|") != "|2" {
		t.Errorf("Last-Event-ID sent: %q", sentIDs)
	}
}

func TestLogs_streamThatNeverFinishes_failsAfterTheReconnectBudget(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/api/executions/") {
			_, _ = w.Write([]byte(`{"data":{"status":"RUNNING"}}`))
			return
		}
		w.Header().Set("Content-Type", "text/event-stream")
	}))
	defer srv.Close()
	c := fastClient(srv.URL)
	c.StreamReconnects = 2

	err := c.StreamExecutionLogs(context.Background(), "s", "e", func(string) error { return nil })

	if err == nil || !strings.Contains(err.Error(), "before the execution finished") {
		t.Errorf("err=%v", err)
	}
}

func TestStream_silentConnectionIsDroppedAfterTheIdleTimeout(t *testing.T) {
	var connections atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/api/executions/") {
			_, _ = w.Write([]byte(`{"data":{"status":"SUCCESS"}}`))
			return
		}
		w.Header().Set("Content-Type", "text/event-stream")
		w.WriteHeader(http.StatusOK)
		w.(http.Flusher).Flush()
		connections.Add(1)
		select { // sends nothing, not even a ping
		case <-r.Context().Done():
		case <-time.After(5 * time.Second):
		}
	}))
	defer srv.Close()
	c := fastClient(srv.URL)
	c.StreamIdle = 50 * time.Millisecond

	start := time.Now()
	err := c.StreamExecutionLogs(context.Background(), "s", "e", func(string) error { return nil })

	if err != nil || time.Since(start) > 3*time.Second || connections.Load() != 1 {
		t.Errorf("err=%v after %v, connections=%d", err, time.Since(start), connections.Load())
	}
}

func TestStream_pingsKeepAQuietConnectionAlive(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if answerDone(w, r) {
			return
		}
		w.Header().Set("Content-Type", "text/event-stream")
		for i := 0; i < 5; i++ {
			fmt.Fprint(w, ": ping\n\n")
			w.(http.Flusher).Flush()
			time.Sleep(30 * time.Millisecond)
		}
		fmt.Fprint(w, "data: late\n\n")
	}))
	defer srv.Close()
	c := fastClient(srv.URL)
	c.StreamIdle = 100 * time.Millisecond

	var lines []string
	err := c.StreamExecutionLogs(context.Background(), "s", "e", func(l string) error { lines = append(lines, l); return nil })

	if err != nil || len(lines) != 1 || lines[0] != "late" {
		t.Errorf("lines=%v err=%v", lines, err)
	}
}

func TestScanSSE_dropsAnEventNotFollowedByABlankLine(t *testing.T) {
	var got []SSEEvent
	err := ScanSSE(context.Background(), strings.NewReader("id: 7\nevent: e\ndata: tail"), func(e SSEEvent) error {
		got = append(got, e)
		return nil
	})

	if err != nil || len(got) != 0 {
		t.Errorf("got=%+v err=%v", got, err)
	}
}

func TestFollow_callbackErrorEndsTheStreamWithThatError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		fmt.Fprint(w, "data: x\n\n")
	}))
	defer srv.Close()

	err := fastClient(srv.URL).StreamExecutionLogs(context.Background(), "s", "e", func(string) error { return errSentinel })

	if err != errSentinel {
		t.Errorf("err=%v", err)
	}
}

func TestFollow_cancelledContext_stopsReconnecting(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/api/executions/") {
			_, _ = w.Write([]byte(`{"data":{"status":"RUNNING"}}`))
			return
		}
		w.Header().Set("Content-Type", "text/event-stream")
	}))
	defer srv.Close()
	c := NewClient(srv.URL)
	c.StreamWait = time.Hour
	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()

	err := c.StreamExecutionLogs(ctx, "s", "e", func(string) error { return nil })

	if err == nil || ctx.Err() == nil {
		t.Errorf("err=%v", err)
	}
}

var errSentinel = fmt.Errorf("stop")

func TestEventStream_silentFeedIsReportedWithTheReason(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		w.WriteHeader(http.StatusOK)
		w.(http.Flusher).Flush()
		<-r.Context().Done()
	}))
	defer srv.Close()
	c := fastClient(srv.URL)
	c.StreamIdle = 30 * time.Millisecond

	err := c.StreamEvents(context.Background(), "", "", "", func(ExecutionEvent) error { return nil })

	if err == nil || !strings.Contains(err.Error(), "no data or ping") {
		t.Errorf("err=%v", err)
	}
}

func TestCallbackError_wrapsTheHandlersError(t *testing.T) {
	e := callbackError{errSentinel}
	if e.Error() != "stop" || e.Unwrap() != errSentinel {
		t.Errorf("%v / %v", e.Error(), e.Unwrap())
	}
}

func TestFollow_silentServerBeforeHeadersTimesOut(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(_ http.ResponseWriter, r *http.Request) {
		<-r.Context().Done()
	}))
	defer srv.Close()
	c := fastClient(srv.URL)
	c.StreamIdle = 50 * time.Millisecond

	err := c.StreamExecutionLogs(context.Background(), "s", "e", func(string) error { return nil })

	if err == nil || !strings.Contains(err.Error(), "no response") {
		t.Errorf("err=%v", err)
	}
}

func TestFollow_reconnectStopsAtOnceOnANonRetryableStatus(t *testing.T) {
	var calls int
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasSuffix(r.URL.Path, "/stream") && !strings.Contains(r.URL.Path, "logs") {
			_, _ = w.Write([]byte(`{"data":{"status":"RUNNING"}}`))
			return
		}
		calls++
		if calls > 1 {
			http.Error(w, "revoked", http.StatusUnauthorized)
			return
		}
		w.Header().Set("Content-Type", "text/event-stream")
		fmt.Fprint(w, "id: 1\ndata: x\n\n")
	}))
	defer srv.Close()
	c := fastClient(srv.URL)

	err := c.StreamExecutionLogs(context.Background(), "s", "e", func(string) error { return nil })

	var ae *apiError
	if !errors.As(err, &ae) || ae.status != 401 || calls != 2 {
		t.Errorf("calls=%d err=%v", calls, err)
	}
}

func TestFollow_reconnectHonoursRetryAfter(t *testing.T) {
	var calls int
	var first time.Time
	var gap time.Duration
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if calls >= 3 && answerDone(w, r) {
			return
		}
		if !strings.Contains(r.URL.Path, "logs") {
			_, _ = w.Write([]byte(`{"data":{"status":"RUNNING"}}`))
			return
		}
		calls++
		switch calls {
		case 1:
			w.Header().Set("Content-Type", "text/event-stream")
			fmt.Fprint(w, "id: 1\ndata: x\n\n")
			first = time.Now()
		case 2:
			gap = time.Since(first)
			w.Header().Set("Retry-After", "1")
			http.Error(w, "busy", http.StatusServiceUnavailable)
		default:
			w.Header().Set("Content-Type", "text/event-stream")
			fmt.Fprint(w, "data: y\n\n")
		}
	}))
	defer srv.Close()
	c := fastClient(srv.URL)
	c.StreamReconnects = 3

	_ = c.StreamExecutionLogs(context.Background(), "s", "e", func(string) error { return nil })

	if calls < 3 {
		t.Errorf("calls=%d gap=%v", calls, gap)
	}
}
