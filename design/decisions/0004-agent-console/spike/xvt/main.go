// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

// Spike for AC0 (#2364): run sh in a PTY (go-pty) and print the screen (charmbracelet/x/vt).
package main

import (
	"fmt"
	"io"
	"time"

	"github.com/aymanbagabas/go-pty"
	"github.com/charmbracelet/x/vt"
)

func main() {
	p, err := pty.New()
	if err != nil {
		panic(err)
	}
	defer p.Close()
	_ = p.Resize(80, 10)
	c := p.Command("sh", "-c", `printf 'hello \033[31mred\033[0m\n'; printf 'line2\n'; sleep 0.3`)
	if err := c.Start(); err != nil {
		panic(err)
	}
	term := vt.NewEmulator(80, 10)
	done := make(chan struct{})
	go func() { _, _ = io.Copy(term, p); close(done) }()
	_ = c.Wait()
	select {
	case <-done:
	case <-time.After(500 * time.Millisecond):
	}
	fmt.Print(term.String())
}
