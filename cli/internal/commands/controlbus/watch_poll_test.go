// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package controlbus

import (
	"io"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	"com.infenia.yukta/go-cli/internal/client"
	"com.infenia.yukta/go-cli/internal/testutil"
)

// pollServer holds the event stream open and answers the status poll with status for the first
// failures calls, then with a finished run.
func pollServer(t *testing.T, status, failures int) *httptest.Server {
	t.Helper()
	var calls atomic.Int32
	release := make(chan struct{})
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/events/stream" {
			w.Header().Set("Content-Type", "text/event-stream")
			w.(http.Flusher).Flush()
			select {
			case <-release:
			case <-r.Context().Done():
			}
			return
		}
		if int(calls.Add(1)) <= failures {
			http.Error(w, "nope", status)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"data":{"executionId":"e","workflowId":"w","status":"SUCCESS"}}`)
	}))
	t.Cleanup(func() { close(release); srv.Close() })
	return srv
}

func TestWatchCmd_untilDoneEndsWithThePermanentPollError(t *testing.T) {
	defer func(old time.Duration) { snapshotInterval = old }(snapshotInterval)
	snapshotInterval = 20 * time.Millisecond
	srv := pollServer(t, http.StatusNotFound, 1000)

	_, err := testutil.Run(t, WatchCmd(client.NewClient(srv.URL)), "--execution", "e", "--until-done")

	if err == nil || !client.IsPermanent(err) {
		t.Errorf("err=%v", err)
	}
}

func TestWatchCmd_untilDoneKeepsPollingThroughTransientErrors(t *testing.T) {
	defer func(old time.Duration) { snapshotInterval = old }(snapshotInterval)
	snapshotInterval = 20 * time.Millisecond
	srv := pollServer(t, http.StatusInternalServerError, 2)

	out, err := testutil.Run(t, WatchCmd(client.NewClient(srv.URL)), "--execution", "e", "--until-done")

	if err != nil || out != "WORKFLOW SUCCESS\n" {
		t.Errorf("out=%q err=%v", out, err)
	}
}
