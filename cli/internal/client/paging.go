// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

package client

import (
	"encoding/json"
	"fmt"
	"net/url"
)

// nextCursorHeader announces a further page of a list endpoint.
const nextCursorHeader = "X-Next-Cursor"

// maxPages stops a server that keeps announcing a next page.
const maxPages = 10000

// getPages GETs a list endpoint and follows X-Next-Cursor until the last page, so a list is never
// silently cut at the server's page size. It returns the items of the "data" arrays, in order.
func (c *Client) getPages(path string) ([]json.RawMessage, error) {
	var items []json.RawMessage
	cursor := ""
	for page := 0; page < maxPages; page++ {
		pagePath := path
		if cursor != "" {
			pagePath += "?cursor=" + url.QueryEscape(cursor)
		}
		req, err := c.newRequest("GET", pagePath)
		if err != nil {
			return nil, err
		}
		body, header, err := c.doRequestHeader(req)
		if err != nil {
			return nil, err
		}
		var response struct {
			Data []json.RawMessage `json:"data"`
		}
		if err := json.Unmarshal(body, &response); err != nil {
			return nil, fmt.Errorf("failed to unmarshal response: %w", err)
		}
		items = append(items, response.Data...)
		next := header.Get(nextCursorHeader)
		if next == "" {
			return items, nil
		}
		if next == cursor {
			return nil, fmt.Errorf("%s: server repeats cursor %q", path, next)
		}
		cursor = next
	}
	return nil, fmt.Errorf("%s: more than %d pages", path, maxPages)
}

// getAll is getPages decoded into a slice of T.
func getAll[T any](c *Client, path string) ([]T, error) {
	raw, err := c.getPages(path)
	if err != nil {
		return nil, err
	}
	out := make([]T, 0, len(raw))
	for _, r := range raw {
		var v T
		if err := json.Unmarshal(r, &v); err != nil {
			return nil, fmt.Errorf("failed to unmarshal response: %w", err)
		}
		out = append(out, v)
	}
	return out, nil
}
