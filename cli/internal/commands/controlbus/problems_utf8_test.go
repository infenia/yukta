// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package controlbus

import (
	"strings"
	"testing"
	"unicode/utf8"
)

func TestProblemFilter_truncatesAtARuneBoundary(t *testing.T) {
	// "é" is two bytes, so a cut at an even/odd offset lands inside a character for one of them.
	for _, pad := range []string{"", "x"} {
		f := newProblemFilter()

		got := f.admit("n", "ERROR", pad+strings.Repeat("é", 400))

		if len(got) != 1 || !utf8.ValidString(got[0]) {
			t.Errorf("pad %q: invalid UTF-8 in %q", pad, got)
		}
	}
}
