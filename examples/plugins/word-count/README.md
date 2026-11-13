# WORD_COUNT: an example community plugin

Counts the words in a payload field and sends long texts to the `long` port. It shows the whole contract of a
command plugin: a `yukta-plugin.json` manifest, the message envelope on stdin, one output object per line on stdout,
log lines on stderr, and a non-zero exit to fail the message.

Try it without a server:

```bash
echo '{"apiVersion":"yukta/message/v1","config":{"longOver":3},"message":{"id":"1","metadata":{},"payload":{"text":"one two three four"}}}' \
  | python3 main.py
```

Install it on a server (Python 3 on the server's `PATH`):

```bash
YUKTA_PLUGINS_DIR=/opt/yukta/plugins          # this directory is /opt/yukta/plugins/word-count
YUKTA_PLUGINS_ENABLED=WORD_COUNT              # or WORD_COUNT@sha256:<digest>, from yukta-cli plugin validate
```

See [Write a community plugin](../../../docs/guides/extend/write-a-community-plugin.mdx).
