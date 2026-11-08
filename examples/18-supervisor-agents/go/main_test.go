// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package main

import "testing"

func TestDecide(t *testing.T) {
	var failed envelope
	failed.Kind = "EVENT"
	failed.Cursor = 7
	failed.Signal.ID = "e7"
	failed.Signal.Event.ExecutionID = "run1"
	failed.Signal.Event.NodeID = "build"
	failed.Signal.Event.Status = "FAILURE"
	if path, _, ok := decide(failed); !ok || path != "/directives" {
		t.Errorf("a failed node should pause its run: %s %v", path, ok)
	}

	var review envelope
	review.Kind = "REVIEW"
	review.Signal.ID = "r1"
	if path, _, ok := decide(review); !ok || path != "/reviews/r1" {
		t.Errorf("a review should be answered: %s %v", path, ok)
	}

	var quiet envelope
	quiet.Kind = "EVENT"
	quiet.Signal.Event.Status = "SUCCESS"
	if _, _, ok := decide(quiet); ok {
		t.Error("a success needs no action")
	}
}
