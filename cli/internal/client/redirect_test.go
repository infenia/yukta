// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"net/http"
	"strings"
	"testing"
)

func TestToken_refusedOnAnHttpsToHttpRedirectHop(t *testing.T) {
	c := NewClient("https://prod.example.com")
	c.Token = "secret"
	check := c.HTTPClient.CheckRedirect

	secure, _ := http.NewRequest("GET", "https://prod.example.com/x", nil)
	if err := check(secure, nil); err != nil {
		t.Errorf("https hop should pass: %v", err)
	}
	plain, _ := http.NewRequest("GET", "http://prod.example.com/x", nil)
	if err := check(plain, nil); err == nil || !strings.Contains(err.Error(), "--insecure-token") {
		t.Errorf("http downgrade with a token should be refused: %v", err)
	}
}
