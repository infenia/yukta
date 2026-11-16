// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

//go:build !windows

package conversations

import (
	"os"
	"os/signal"
	"syscall"
)

// resizeSignals fires when the person resizes the window.
func resizeSignals() (<-chan struct{}, func()) {
	signals := make(chan os.Signal, 1)
	signal.Notify(signals, syscall.SIGWINCH)
	fired := make(chan struct{}, 1)
	done := make(chan struct{})
	go func() {
		for {
			select {
			case <-signals:
				select {
				case fired <- struct{}{}:
				default:
				}
			case <-done:
				return
			}
		}
	}()
	return fired, func() { signal.Stop(signals); close(done) }
}
