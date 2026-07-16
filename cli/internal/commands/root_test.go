// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package commands

import (
	"io"
	"os"
	"strings"
	"testing"

	"com.infenia.yukta/go-cli/internal/client"
	"github.com/spf13/cobra"
)

// TestRootCmd_createsCommand_withCorrectUse tests that RootCmd creates command with correct Use.
func TestRootCmd_createsCommand_withCorrectUse(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := RootCmd(c)

	if cmd.Use != "yukta" {
		t.Errorf("expected Use 'yukta', got %q", cmd.Use)
	}
}

// TestRootCmd_hasShortDescription tests that command has short description.
func TestRootCmd_hasShortDescription(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := RootCmd(c)

	if cmd.Short == "" {
		t.Error("expected non-empty Short description")
	}

	if !strings.Contains(cmd.Short, "Yukta CLI") {
		t.Errorf("expected 'Yukta CLI' in Short, got %q", cmd.Short)
	}
}

// TestRootCmd_hasLongDescription tests that command has long description.
func TestRootCmd_hasLongDescription(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := RootCmd(c)

	if cmd.Long == "" {
		t.Error("expected non-empty Long description")
	}

	if !strings.Contains(cmd.Long, "quality") {
		t.Errorf("expected 'quality' in Long description, got %q", cmd.Long)
	}
}

// TestRootCmd_setPersistentFlags_urlFlag tests that --url flag is set.
func TestRootCmd_setPersistentFlags_urlFlag(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := RootCmd(c)

	urlFlag := cmd.PersistentFlags().Lookup("url")
	if urlFlag == nil {
		t.Fatal("expected --url flag to be defined")
	}

	if urlFlag.Usage == "" {
		t.Error("expected --url flag to have usage description")
	}
}

// TestRootCmd_setPersistentFlags_outputFlag tests that -o/--output flag is set.
func TestRootCmd_setPersistentFlags_outputFlag(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := RootCmd(c)

	outputFlag := cmd.PersistentFlags().Lookup("output")
	if outputFlag == nil {
		t.Fatal("expected --output flag to be defined")
	}

	shorthand := cmd.PersistentFlags().ShorthandLookup("o")
	if shorthand == nil {
		t.Fatal("expected -o shorthand for output flag")
	}

	if outputFlag.Value.String() != "table" {
		t.Errorf("expected default output value 'table', got %q", outputFlag.Value.String())
	}
}

// TestRootCmd_defaultURLFromEnv tests that YUKTA_SERVER_URL env var is used if set.
func TestRootCmd_defaultURLFromEnv(t *testing.T) {
	oldEnv := os.Getenv("YUKTA_SERVER_URL")
	defer os.Setenv("YUKTA_SERVER_URL", oldEnv)

	os.Setenv("YUKTA_SERVER_URL", "http://custom.example.com:9090")
	c := client.NewClient("http://localhost:8080")
	cmd := RootCmd(c)

	urlFlag := cmd.PersistentFlags().Lookup("url")
	if urlFlag.Value.String() != "http://custom.example.com:9090" {
		t.Errorf("expected URL from env var, got %q", urlFlag.Value.String())
	}
}

// TestRootCmd_defaultURLWithoutEnv tests that default URL is used when env var is not set.
func TestRootCmd_defaultURLWithoutEnv(t *testing.T) {
	oldEnv := os.Getenv("YUKTA_SERVER_URL")
	defer os.Setenv("YUKTA_SERVER_URL", oldEnv)

	os.Unsetenv("YUKTA_SERVER_URL")
	c := client.NewClient("http://localhost:8080")
	cmd := RootCmd(c)

	urlFlag := cmd.PersistentFlags().Lookup("url")
	if urlFlag.Value.String() != "http://localhost:8080" {
		t.Errorf("expected default URL, got %q", urlFlag.Value.String())
	}
}

// TestPersistentPreRunE_updatesClientBaseURL tests that client BaseURL is updated.
func TestPersistentPreRunE_updatesClientBaseURL(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := RootCmd(c)

	// Set the URL flag
	cmd.SetArgs([]string{"--url", "http://example.com:9090"})
	cmd.SetOut(io.Discard)
	cmd.SetErr(io.Discard)

	// Execute the command (this will parse flags and run PersistentPreRunE)
	// Ignore the error since the root command has no default action
	err := cmd.Execute()
	if err != nil && !strings.Contains(err.Error(), "unknown command") && !strings.Contains(err.Error(), "no subcommands") {
		t.Fatalf("unexpected error: %v", err)
	}

	if c.BaseURL != "http://example.com:9090" {
		t.Errorf("expected client BaseURL to be updated, got %q", c.BaseURL)
	}
}

// TestPersistentPreRunE_trimsTrailingSlash tests that trailing slash is trimmed.
func TestPersistentPreRunE_trimsTrailingSlash(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := RootCmd(c)

	cmd.SetArgs([]string{"--url", "http://example.com/"})
	cmd.SetOut(io.Discard)
	cmd.SetErr(io.Discard)

	_ = cmd.Execute()

	if c.BaseURL != "http://example.com" {
		t.Errorf("expected trailing slash to be trimmed, got %q", c.BaseURL)
	}
}

// TestPersistentPreRunE_multipleTrailingSlashes tests that multiple trailing slashes are trimmed.
func TestPersistentPreRunE_multipleTrailingSlashes(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := RootCmd(c)

	cmd.SetArgs([]string{"--url", "http://example.com///"})
	cmd.SetOut(io.Discard)
	cmd.SetErr(io.Discard)

	_ = cmd.Execute()

	if c.BaseURL != "http://example.com" {
		t.Errorf("expected all trailing slashes to be trimmed, got %q", c.BaseURL)
	}
}

// TestGetOutputFormat_returnsFormatValue tests GetOutputFormat returns the flag value.
func TestGetOutputFormat_returnsFormatValue(t *testing.T) {
	// Reset outputFormat to known state
	outputFormat = "table"

	result := GetOutputFormat()
	if result != "table" {
		t.Errorf("expected 'table', got %q", result)
	}
}

// TestGetOutputFormat_returnsJSON tests GetOutputFormat returns JSON when set.
func TestGetOutputFormat_returnsJSON(t *testing.T) {
	outputFormat = "json"
	defer func() { outputFormat = "table" }()

	result := GetOutputFormat()
	if result != "json" {
		t.Errorf("expected 'json', got %q", result)
	}
}

// TestOutputFormatFlagIntegration tests output format flag through command execution.
func TestOutputFormatFlagIntegration(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := RootCmd(c)

	// Create a test subcommand that uses GetOutputFormat
	testCmd := &cobra.Command{
		Use: "test",
		Run: func(cmd *cobra.Command, args []string) {
			// This would normally be called by a subcommand
		},
	}
	cmd.AddCommand(testCmd)

	cmd.SetArgs([]string{"-o", "json", "test"})
	cmd.SetOut(io.Discard)
	cmd.SetErr(io.Discard)

	_ = cmd.Execute()

	if GetOutputFormat() != "json" {
		t.Errorf("expected output format to be 'json', got %q", GetOutputFormat())
	}

	// Reset
	outputFormat = "table"
}

// TestRootCmd_hasNoRunFunction tests that root command has no direct Run action.
func TestRootCmd_hasNoRunFunction(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := RootCmd(c)

	// Root command should have PersistentPreRunE but not Run
	if cmd.Run != nil {
		t.Error("expected root command to have no Run function")
	}
}

// TestRootCmd_hasPersistentPreRunE tests that PersistentPreRunE is set.
func TestRootCmd_hasPersistentPreRunE(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := RootCmd(c)

	if cmd.PersistentPreRunE == nil {
		t.Error("expected PersistentPreRunE to be set")
	}
}

// TestPersistentPreRunE_withoutURL tests PersistentPreRunE without setting URL flag.
func TestPersistentPreRunE_withoutURL(t *testing.T) {
	oldEnv := os.Getenv("YUKTA_SERVER_URL")
	defer os.Setenv("YUKTA_SERVER_URL", oldEnv)
	os.Unsetenv("YUKTA_SERVER_URL")

	c := client.NewClient("http://localhost:8080")
	cmd := RootCmd(c)

	cmd.SetArgs([]string{})
	cmd.SetOut(io.Discard)
	cmd.SetErr(io.Discard)

	_ = cmd.Execute()

	// Should use default from flag definition
	if c.BaseURL != "http://localhost:8080" {
		t.Errorf("expected default URL to be set, got %q", c.BaseURL)
	}
}

// TestServerURLPackageVariable tests that serverURL package variable exists.
func TestServerURLPackageVariable(t *testing.T) {
	// This test verifies the package variable exists by creating a root command
	c := client.NewClient("http://localhost:8080")
	cmd := RootCmd(c)

	// If the variable didn't exist, the command creation would fail
	if cmd == nil {
		t.Error("expected command to be created")
	}
}

// TestURLFlagShorthand tests that --url flag has no shorthand.
func TestURLFlagShorthand(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := RootCmd(c)

	shorthand := cmd.PersistentFlags().ShorthandLookup("u")
	if shorthand != nil {
		t.Errorf("expected no -u shorthand for --url flag")
	}
}

// TestOutputFlagShorthandIsO tests that output flag has -o shorthand.
func TestOutputFlagShorthandIsO(t *testing.T) {
	c := client.NewClient("http://localhost:8080")
	cmd := RootCmd(c)

	shorthand := cmd.PersistentFlags().ShorthandLookup("o")
	if shorthand == nil {
		t.Fatal("expected -o shorthand for output flag")
	}

	if shorthand.Name != "output" {
		t.Errorf("expected -o to map to 'output', got %q", shorthand.Name)
	}
}

// TestSetTestOutputFormat_setsTableFormat tests SetTestOutputFormat sets format to 'table'.
func TestSetTestOutputFormat_setsTableFormat(t *testing.T) {
	// Save original state
	original := outputFormat
	defer func() { outputFormat = original }()

	SetTestOutputFormat("table")

	result := GetOutputFormat()
	if result != "table" {
		t.Errorf("expected 'table', got %q", result)
	}
}

// TestSetTestOutputFormat_setsJSONFormat tests SetTestOutputFormat sets format to 'json'.
func TestSetTestOutputFormat_setsJSONFormat(t *testing.T) {
	// Save original state
	original := outputFormat
	defer func() { outputFormat = original }()

	SetTestOutputFormat("json")

	result := GetOutputFormat()
	if result != "json" {
		t.Errorf("expected 'json', got %q", result)
	}
}

// TestSetTestOutputFormat_overwritesPreviousValue tests SetTestOutputFormat overwrites previous value.
func TestSetTestOutputFormat_overwritesPreviousValue(t *testing.T) {
	// Save original state
	original := outputFormat
	defer func() { outputFormat = original }()

	SetTestOutputFormat("table")
	SetTestOutputFormat("json")

	result := GetOutputFormat()
	if result != "json" {
		t.Errorf("expected 'json' after overwrite, got %q", result)
	}
}

// TestSetTestOutputFormat_setsCustomFormat tests SetTestOutputFormat can set custom format strings.
func TestSetTestOutputFormat_setsCustomFormat(t *testing.T) {
	// Save original state
	original := outputFormat
	defer func() { outputFormat = original }()

	SetTestOutputFormat("custom")

	result := GetOutputFormat()
	if result != "custom" {
		t.Errorf("expected 'custom', got %q", result)
	}
}

// TestSetTestOutputFormat_setsEmptyFormat tests SetTestOutputFormat can set empty format.
func TestSetTestOutputFormat_setsEmptyFormat(t *testing.T) {
	// Save original state
	original := outputFormat
	defer func() { outputFormat = original }()

	SetTestOutputFormat("")

	result := GetOutputFormat()
	if result != "" {
		t.Errorf("expected empty string, got %q", result)
	}
}

// TestSetTestOutputFormat_multipleCallsInSequence tests SetTestOutputFormat with multiple sequential calls.
func TestSetTestOutputFormat_multipleCallsInSequence(t *testing.T) {
	// Save original state
	original := outputFormat
	defer func() { outputFormat = original }()

	formats := []string{"table", "json", "yaml", "xml"}
	for _, format := range formats {
		SetTestOutputFormat(format)
		result := GetOutputFormat()
		if result != format {
			t.Errorf("expected %q, got %q", format, result)
		}
	}
}

// TestSetTestOutputFormat_affectsGlobalVariable tests SetTestOutputFormat affects the global outputFormat variable.
func TestSetTestOutputFormat_affectsGlobalVariable(t *testing.T) {
	// Save original state
	original := outputFormat
	defer func() { outputFormat = original }()

	testFormat := "test-format"
	SetTestOutputFormat(testFormat)

	// Verify the global variable was updated by calling GetOutputFormat
	if GetOutputFormat() != testFormat {
		t.Errorf("expected global outputFormat to be set to %q", testFormat)
	}
}
