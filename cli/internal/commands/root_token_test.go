// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package commands

import (
	"io"
	"strings"
	"testing"

	"github.com/spf13/cobra"

	"com.infenia.yukta/go-cli/internal/client"
)

func TestRootCmd_tokenEnvIsNotPrintedInHelpButStillUsed(t *testing.T) {
	t.Setenv("YUKTA_TOKEN", "s3cret-env")
	c := client.NewClient("http://localhost:9858")
	cmd := RootCmd(c)
	cmd.AddCommand(&cobra.Command{Use: "noop", Run: func(*cobra.Command, []string) {}})
	var out strings.Builder
	cmd.SetOut(&out)
	cmd.SetErr(&out)

	cmd.SetArgs([]string{"--help"})
	_ = cmd.Execute()
	if strings.Contains(out.String(), "s3cret-env") {
		t.Errorf("help leaks the token:\n%s", out.String())
	}

	cmd.SetArgs([]string{"noop"})
	if err := cmd.Execute(); err != nil || c.Token != "s3cret-env" {
		t.Errorf("err=%v token=%q", err, c.Token)
	}
}

func TestRootCmd_tokenAndTokenFileConflict(t *testing.T) {
	cmd := RootCmd(client.NewClient("http://localhost:9858"))
	cmd.AddCommand(&cobra.Command{Use: "noop", Run: func(*cobra.Command, []string) {}})
	cmd.SetArgs([]string{"--token", "a", "--token-file", "f", "noop"})
	cmd.SetOut(io.Discard)
	cmd.SetErr(io.Discard)

	if err := cmd.Execute(); err == nil || !strings.Contains(err.Error(), "mutually exclusive") {
		t.Errorf("err=%v", err)
	}
}
