// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package commands

import (
	"errors"
	"testing"
)

func TestDataCmd(t *testing.T) {
	ok := DataCmd("x", "x", 0, func([]string) (any, error) { return map[string]int{"a": 1}, nil })
	if err := ok.RunE(ok, nil); err != nil {
		t.Fatal(err)
	}
	bad := DataCmd("x", "x", 0, func([]string) (any, error) { return nil, errors.New("boom") })
	if err := bad.RunE(bad, nil); err == nil {
		t.Fatal("expected error")
	}
}
