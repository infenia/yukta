# 09 — LLM chat agent with memory

**Use case:** a chat assistant that remembers earlier turns for each user. `SESSION_MEMORY` loads the history, `LLM_CALL` answers, and `SESSION_MEMORY` appends the new turn.

```
CONSTANT_SOURCE ──► SESSION_MEMORY (load) ──► LLM_CALL ──► SESSION_MEMORY (append) ──► CONSOLE_TERMINAL
```

The example runs two turns for the same user (`user-42`): "My name is Ada", then "What is my name?". Turn 2 answers from memory.

In production, replace `CONSTANT_SOURCE` with `api-trigger` once that path completes on your build (see example 02), so each chat message arrives as a request.

**Requirements:** an OpenAI-compatible endpoint. Defaults to a local Ollama.

```bash
ollama pull llama3.2 && ollama serve          # local default
./examples/09-llm-chat-agent/run.sh

# or any OpenAI-compatible service
LLM_BASE_URL=https://api.example.com/v1 LLM_MODEL=my-model LLM_API_KEY=... ./examples/09-llm-chat-agent/run.sh
```

If no endpoint answers, `run.sh` prints `SKIP` and exits with code 3.

**Expected:** turn 1 answers, turn 2 answers `Your name is Ada`. Memory is in-process only; a server restart clears it.
