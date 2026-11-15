// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

// Package pluginspec checks a community plugin against the plugin wire contract v1: its
// yukta-plugin.json manifest, its directory digest, and one run with a sample message. The schemas
// are copies of plugin-api's (make sync-schemas); a test fails when they drift.
package pluginspec

import (
	"bytes"
	"embed"
	"fmt"

	"github.com/santhosh-tekuri/jsonschema/v6"
)

//go:embed schema/*.json
var schemas embed.FS

// Manifest is the name of a plugin's manifest file.
const Manifest = "yukta-plugin.json"

var (
	manifestSchema = mustCompile("manifest")
	outputSchema   = mustCompile("message-out")
)

// mustCompile compiles an embedded schema; the files ship with the binary, so failing is a bug.
func mustCompile(name string) *jsonschema.Schema {
	s, err := compile(name)
	if err != nil {
		panic(err)
	}
	return s
}

func compile(name string) (*jsonschema.Schema, error) {
	data, err := schemas.ReadFile("schema/" + name + ".schema.json")
	if err != nil {
		return nil, err
	}
	return compileJSON(name+".schema.json", data)
}

// compileJSON compiles one schema document, known by url.
func compileJSON(url string, data []byte) (*jsonschema.Schema, error) {
	doc, err := jsonschema.UnmarshalJSON(bytes.NewReader(data))
	if err != nil {
		return nil, err
	}
	c := jsonschema.NewCompiler()
	if err := c.AddResource(url, doc); err != nil {
		return nil, err
	}
	return c.Compile(url)
}

// parse reads one JSON document in the form the validator expects.
func parse(data []byte) (any, error) {
	return jsonschema.UnmarshalJSON(bytes.NewReader(data))
}

// CheckManifest validates a manifest and returns it as a map.
func CheckManifest(data []byte) (map[string]any, error) {
	doc, err := parse(data)
	if err != nil {
		return nil, fmt.Errorf("%s is not JSON: %w", Manifest, err)
	}
	if err := manifestSchema.Validate(doc); err != nil {
		return nil, fmt.Errorf("%s is invalid: %w", Manifest, err)
	}
	return doc.(map[string]any), nil
}

// CheckOutput validates one line a plugin wrote to stdout and returns it as a map.
func CheckOutput(line []byte) (map[string]any, error) {
	doc, err := parse(line)
	if err != nil {
		return nil, fmt.Errorf("not JSON: %w", err)
	}
	if err := outputSchema.Validate(doc); err != nil {
		return nil, fmt.Errorf("invalid output: %w", err)
	}
	return doc.(map[string]any), nil
}
