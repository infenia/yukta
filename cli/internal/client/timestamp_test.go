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

func TestTimestamp_UnmarshalJSON_withOffset_keepsTheInstant(t *testing.T) {
	var ts Timestamp
	if err := json.Unmarshal([]byte(`"2026-10-16T04:26:14.183+05:30"`), &ts); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	want := time.Date(2026, 10, 15, 22, 56, 14, 183000000, time.UTC)
	if !ts.Equal(want) {
		t.Errorf("expected %v, got %v", want, ts.Time)
	}
	if got, want := ts.String(), "2026-10-16 04:26:14 +05:30"; got != want {
		t.Errorf("expected %q, got %q", want, got)
	}
}

// A server that sends no offset leaves the zone unknown. The CLI must not guess its own zone: the
// value is shown and passed on as it came.
func TestTimestamp_UnmarshalJSON_zoneless_doesNotDependOnTheCLITimeZone(t *testing.T) {
	old := time.Local
	time.Local = time.FixedZone("CLI", -8*3600)
	defer func() { time.Local = old }()

	var ts Timestamp
	if err := json.Unmarshal([]byte(`"2026-10-12T16:03:14.055411769"`), &ts); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if ts.Hour() != 16 || ts.Minute() != 3 {
		t.Errorf("the clock reading must be kept, got %v", ts.Time)
	}
	if got, want := ts.String(), "2026-10-12 16:03:14"; got != want {
		t.Errorf("expected %q, got %q", want, got)
	}
	out, err := json.Marshal(ts)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if got, want := string(out), `"2026-10-12T16:03:14.055411769"`; got != want {
		t.Errorf("expected %s, got %s", want, got)
	}
}

func TestTimestamp_Differences_ofZonelessValuesAreRight(t *testing.T) {
	var a, b Timestamp
	_ = json.Unmarshal([]byte(`"2026-10-12T16:03:14"`), &a)
	_ = json.Unmarshal([]byte(`"2026-10-12T16:05:44"`), &b)
	if got, want := b.Sub(a.Time), 150*time.Second; got != want {
		t.Errorf("expected %v, got %v", want, got)
	}
}

func TestTimestamp_MarshalJSON_withOffset_isRFC3339(t *testing.T) {
	var ts Timestamp
	_ = json.Unmarshal([]byte(`"2026-10-16T04:26:14.183+05:30"`), &ts)
	out, err := json.Marshal(ts)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if got, want := string(out), `"2026-10-16T04:26:14.183+05:30"`; got != want {
		t.Errorf("expected %s, got %s", want, got)
	}
}

func TestTimestamp_String_ofAnUnsetTime_isADash(t *testing.T) {
	var ts Timestamp
	_ = json.Unmarshal([]byte(`null`), &ts)
	if got := ts.String(); got != "-" {
		t.Errorf("expected -, got %q", got)
	}
	_ = json.Unmarshal([]byte(`"0001-01-01T00:00:00Z"`), &ts)
	if got := ts.String(); got != "-" {
		t.Errorf("the server's unset marker should read as -, got %q", got)
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
