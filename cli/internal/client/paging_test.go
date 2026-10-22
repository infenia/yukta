// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestGetSessions_followsNextCursorToTheLastPage(t *testing.T) {
	var cursors []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		cursor := r.URL.Query().Get("cursor")
		cursors = append(cursors, cursor)
		switch cursor {
		case "":
			w.Header().Set("X-Next-Cursor", "b")
			_, _ = w.Write([]byte(`{"data":["a","b"]}`))
		case "b":
			w.Header().Set("X-Next-Cursor", "d e")
			_, _ = w.Write([]byte(`{"data":["c","d"]}`))
		default:
			_, _ = w.Write([]byte(`{"data":["e"]}`))
		}
	}))
	defer srv.Close()

	got, err := NewClient(srv.URL).GetSessions()

	if err != nil || strings.Join(got, ",") != "a,b,c,d,e" {
		t.Fatalf("got %v, err=%v", got, err)
	}
	if strings.Join(cursors, "|") != "|b|d e" {
		t.Errorf("cursors sent: %q", cursors)
	}
}

func TestGetWorkflowHistory_followsNextCursor(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("cursor") == "" {
			w.Header().Set("X-Next-Cursor", "x")
			_, _ = w.Write([]byte(`{"data":[{"executionId":"1"}]}`))
			return
		}
		_, _ = w.Write([]byte(`{"data":[{"executionId":"2"}]}`))
	}))
	defer srv.Close()

	got, err := NewClient(srv.URL).GetWorkflowHistory("s")

	if err != nil || len(got) != 2 {
		t.Fatalf("got %v, err=%v", got, err)
	}
}

func TestGetPages_repeatedCursor_failsInsteadOfLooping(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("X-Next-Cursor", "same")
		_, _ = w.Write([]byte(`{"data":["a"]}`))
	}))
	defer srv.Close()

	_, err := NewClient(srv.URL).GetSessions()

	if err == nil || !strings.Contains(err.Error(), "repeats cursor") {
		t.Errorf("expected repeated-cursor error, got %v", err)
	}
}

func TestGetPages_tooManyPages_fails(t *testing.T) {
	n := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		n++
		w.Header().Set("X-Next-Cursor", strings.Repeat("c", 1)+string(rune('a'+n%26))+string(rune('a'+n/26%26))+string(rune('a'+n/676%26)))
		_, _ = w.Write([]byte(`{"data":[]}`))
	}))
	defer srv.Close()

	_, err := NewClient(srv.URL).GetSessions()

	if err == nil || !strings.Contains(err.Error(), "pages") {
		t.Errorf("expected page-limit error, got %v", err)
	}
}

func TestGetAll_itemOfWrongType_fails(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte(`{"data":[1]}`))
	}))
	defer srv.Close()

	if _, err := NewClient(srv.URL).GetSessions(); err == nil {
		t.Error("expected an unmarshal error")
	}
}
