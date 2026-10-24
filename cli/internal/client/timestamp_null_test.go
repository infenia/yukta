// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"encoding/json"
	"testing"
)

func TestTimestamp_MarshalJSON_ofAnUnsetTime_isNull(t *testing.T) {
	out, err := json.Marshal(struct {
		End Timestamp `json:"end"`
	}{})

	if err != nil || string(out) != `{"end":null}` {
		t.Errorf("out=%s err=%v", out, err)
	}
}
