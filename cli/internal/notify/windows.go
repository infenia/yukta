// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package notify

import "context"

// toastScript shows a toast. The text comes from environment variables and goes in as text nodes,
// so nothing in it is ever parsed as script or markup.
const toastScript = `[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null
$xml = [Windows.UI.Notifications.ToastNotificationManager]::GetTemplateContent([Windows.UI.Notifications.ToastTemplateType]::ToastText02)
$text = $xml.GetElementsByTagName('text')
$text.Item(0).AppendChild($xml.CreateTextNode($env:YUKTA_NOTIFY_TITLE)) | Out-Null
$text.Item(1).AppendChild($xml.CreateTextNode($env:YUKTA_NOTIFY_BODY)) | Out-Null
[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('Yukta').Show([Windows.UI.Notifications.ToastNotification]::new($xml))`

// toastNotifier uses PowerShell.
type toastNotifier struct {
	run  Runner
	tool string
}

func (n *toastNotifier) Name() string { return n.tool }

func (n *toastNotifier) Notify(ctx context.Context, item Item) error {
	_, err := n.run(ctx,
		[]string{"YUKTA_NOTIFY_TITLE=" + Clean(item.Title), "YUKTA_NOTIFY_BODY=" + CleanBody(item.Body)},
		n.tool, "-NoProfile", "-NonInteractive", "-Command", toastScript)
	return err
}
