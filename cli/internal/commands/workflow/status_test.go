// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package workflow

import (
	"reflect"
	"testing"

	"com.infenia.yukta/go-cli/internal/client"
)

func TestTaskRowsShowsTheBreakpointOnlyWhereOneIsSet(t *testing.T) {
	rows := taskRows([]client.TaskProgress{
		{NodeID: "a", Module: "m", Status: "RUNNING", Breakpoint: &client.Breakpoint{Field: "payload.kind", Equals: "error"}},
		{NodeID: "b", Module: "m", Status: "PENDING"},
	})
	want := [][]string{
		{"a", "m", "RUNNING", "payload.kind=error"},
		{"b", "m", "PENDING", ""},
	}
	if !reflect.DeepEqual(rows, want) {
		t.Fatalf("rows = %v, want %v", rows, want)
	}
}
