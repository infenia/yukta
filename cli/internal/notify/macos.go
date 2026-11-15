// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package notify

import "context"

// scriptNotifier uses osascript. The text is passed as script arguments, so no quote or backslash
// in a title can end the string and run something else.
type scriptNotifier struct {
	run Runner
}

func (n *scriptNotifier) Name() string { return "osascript" }

func (n *scriptNotifier) Notify(ctx context.Context, item Item) error {
	_, err := n.run(ctx, nil, "osascript",
		"-e", "on run argv",
		"-e", "display notification (item 2 of argv) with title \"Yukta\" subtitle (item 1 of argv)",
		"-e", "end run",
		"--", Clean(item.Title), CleanBody(item.Body))
	return err
}
