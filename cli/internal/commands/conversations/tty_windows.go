// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

//go:build windows

package conversations

// resizeSignals: Windows has no signal for it, so the size is sent once, when the raw view starts.
func resizeSignals() (<-chan struct{}, func()) {
	return nil, func() {}
}
