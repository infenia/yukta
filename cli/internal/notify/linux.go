// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package notify

import (
	"context"
	"strings"
)

// sendNotifier uses notify-send (libnotify). Where it supports actions, a notification with a link
// has an Open button that runs xdg-open.
type sendNotifier struct {
	run     Runner
	actions bool
}

// supportsActions tells whether this notify-send knows --action.
func (n *sendNotifier) supportsActions() bool {
	out, err := n.run(context.Background(), nil, "notify-send", "--help")
	return err == nil && strings.Contains(out, "--action")
}

func (n *sendNotifier) Name() string { return "notify-send" }

func (n *sendNotifier) Notify(ctx context.Context, item Item) error {
	args := []string{"--app-name=Yukta", "--urgency=" + urgency(item.Severity)}
	withAction := n.actions && item.Link != ""
	if withAction {
		args = append(args, "--action=open=Open", "--wait")
	}
	args = append(args, "--", Clean(item.Title), CleanBody(item.Body))
	out, err := n.run(ctx, nil, "notify-send", args...)
	if err != nil {
		return err
	}
	if withAction && strings.TrimSpace(out) == "open" {
		_, err = n.run(ctx, nil, "xdg-open", item.Link)
	}
	return err
}

// urgency maps a severity to a notify-send urgency.
func urgency(severity string) string {
	switch severity {
	case "ERROR":
		return "critical"
	case "WARN":
		return "normal"
	}
	return "low"
}
