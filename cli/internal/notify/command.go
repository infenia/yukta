// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package notify

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"text/template"
)

// Fields are what a --exec template can use: {{.Title}}, {{.Body}}, {{.Severity}}, {{.Kind}},
// {{.Category}}, {{.ID}}, {{.Session}}, {{.Execution}}, {{.Request}}, {{.Link}} and {{.Answer}} (the
// ready answer command of a question).
type Fields struct {
	Title, Body, Severity, Kind, Category string
	ID, Session, Execution, Request       string
	Link, Answer                          string
}

// SplitCommand splits a command line into words the way a shell would for plain words and quotes:
// spaces separate, single and double quotes group, a backslash escapes the next character in double
// quotes and outside quotes. Nothing is expanded, and nothing is run through a shell.
func SplitCommand(line string) ([]string, error) {
	var words []string
	var word []rune
	inWord := false
	var quote rune
	escaped := false
	for _, r := range line {
		switch {
		case escaped:
			word = append(word, r)
			escaped = false
		case r == '\\' && quote != '\'':
			escaped = true
			inWord = true
		case quote != 0 && r == quote:
			quote = 0
		case quote != 0:
			word = append(word, r)
		case r == '\'' || r == '"':
			quote = r
			inWord = true
		case r == ' ' || r == '\t':
			if inWord {
				words = append(words, string(word))
				word, inWord = nil, false
			}
		default:
			word = append(word, r)
			inWord = true
		}
	}
	if quote != 0 || escaped {
		return nil, errors.New("the --exec command has an unfinished quote")
	}
	if inWord {
		words = append(words, string(word))
	}
	if len(words) == 0 {
		return nil, errors.New("the --exec command is empty")
	}
	return words, nil
}

// Command is a program and arguments to run for each notification; each word may use the Fields.
type Command struct {
	words []*template.Template
}

// ParseCommand reads a --exec command line. A word is expanded after the split, so a title with
// spaces or quotes stays one argument.
func ParseCommand(line string) (*Command, error) {
	words, err := SplitCommand(line)
	if err != nil {
		return nil, err
	}
	command := &Command{}
	for _, word := range words {
		t, err := template.New("arg").Option("missingkey=error").Parse(word)
		if err != nil {
			return nil, fmt.Errorf("the --exec command: %w", err)
		}
		command.words = append(command.words, t)
	}
	return command, nil
}

// Run runs the command for one notification, without a shell.
func (c *Command) Run(ctx context.Context, run Runner, fields Fields) error {
	args := make([]string, 0, len(c.words))
	for _, t := range c.words {
		var buf bytes.Buffer
		if err := t.Execute(&buf, fields); err != nil {
			return fmt.Errorf("the --exec command: %w", err)
		}
		args = append(args, buf.String())
	}
	_, err := run(ctx, nil, args[0], args[1:]...)
	return err
}
