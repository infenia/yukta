// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"encoding/json"
	"testing"
	"time"
)

func TestTimestamp_UnmarshalJSON_rfc3339(t *testing.T) {
	var ts Timestamp
	if err := json.Unmarshal([]byte(`"2026-07-17T10:00:00Z"`), &ts); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	want := time.Date(2026, 7, 17, 10, 0, 0, 0, time.UTC)
	if !ts.Equal(want) {
		t.Errorf("expected %v, got %v", want, ts.Time)
	}
}

func TestTimestamp_UnmarshalJSON_zoneless(t *testing.T) {
	var ts Timestamp
	if err := json.Unmarshal([]byte(`"2026-10-12T16:03:14.055411769"`), &ts); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	want := time.Date(2026, 10, 12, 16, 3, 14, 55411769, time.Local)
	if !ts.Equal(want) {
		t.Errorf("expected %v, got %v", want, ts.Time)
	}
}

func TestTimestamp_UnmarshalJSON_nullAndEmpty_areZero(t *testing.T) {
	for _, in := range []string{`null`, `""`} {
		ts := Timestamp{Time: time.Now()}
		if err := json.Unmarshal([]byte(in), &ts); err != nil {
			t.Fatalf("%s: unexpected error: %v", in, err)
		}
		if !ts.IsZero() {
			t.Errorf("%s: expected zero time, got %v", in, ts.Time)
		}
	}
}

func TestTimestamp_UnmarshalJSON_invalid_returnsError(t *testing.T) {
	var ts Timestamp
	if err := json.Unmarshal([]byte(`"not-a-time"`), &ts); err == nil {
		t.Error("expected error for invalid timestamp")
	}
	if err := json.Unmarshal([]byte(`123`), &ts); err == nil {
		t.Error("expected error for non-string timestamp")
	}
}
