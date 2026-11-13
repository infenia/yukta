# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
"""A Yukta community plugin: the message envelope v1 on stdin, one output object on stdout."""
import json
import sys

envelope = json.load(sys.stdin)
if envelope.get("apiVersion") != "yukta/message/v1":
    sys.exit(f"unsupported envelope {envelope.get('apiVersion')}")

config = envelope["config"]
payload = envelope["message"]["payload"]
field = config.get("field", "text")
text = payload.get(field, "") if isinstance(payload, dict) else str(payload)
words = len(str(text).split())
print(f"counted {words} words", file=sys.stderr)  # stderr goes to the node's log

port = "long" if words > config.get("longOver", 100) else "default"
print(json.dumps({"payload": {**payload, "words": words} if isinstance(payload, dict) else {"words": words},
                  "port": port}))
