/**
 * Claude Code GUI — Node.js Bridge
 *
 * Spawned by the IntelliJ plugin as a child process.
 * Communication (bidirectional):
 *   stdin  → Line 1: JSON command. Subsequent lines: permission responses.
 *   stdout → Tagged lines: [TAG]payload
 *   stderr → Debug / SDK internal logs (forwarded to plugin log)
 *
 * Permission protocol:
 *   Bridge emits:  [PERMISSION_REQUEST]{"toolName":"...","input":{...}}
 *   Plugin writes: {"allow":true} or {"allow":false,"message":"reason"} + newline
 */

import { query } from '@anthropic-ai/claude-code';
import { createInterface } from 'readline';

// ── helpers ──────────────────────────────────────────────────────────────────

const DEBUG = process.env.CLAUDE_BRIDGE_DEBUG === '1';

function emit(tag, payload) {
  const data = typeof payload === 'string' ? payload : JSON.stringify(payload);
  if (DEBUG) {
    const preview = data.length > 80 ? data.substring(0, 80) + '...' : data;
    process.stderr.write(`[DEBUG] ${tag} len=${data.length} "${preview}"\n`);
  }
  process.stdout.write(`[${tag}]${data}\n`);
}

/**
 * Emit a streaming delta with JSON-encoded text.
 * JSON encoding preserves newlines and special characters in the line-based protocol.
 */
function emitDelta(tag, text) {
  if (!text) return;
  if (DEBUG) {
    const preview = text.length > 80 ? text.substring(0, 80) + '...' : text;
    process.stderr.write(`[DEBUG] ${tag} len=${text.length} "${preview}"\n`);
  }
  process.stdout.write(`[${tag}]${JSON.stringify(text)}\n`);
}

// Line-based stdin reader for bidirectional communication
const rl = createInterface({ input: process.stdin, terminal: false });
const lineQueue = [];
let lineResolve = null;

rl.on('line', (line) => {
  if (lineResolve) {
    const resolve = lineResolve;
    lineResolve = null;
    resolve(line);
  } else {
    lineQueue.push(line);
  }
});

rl.on('close', () => {
  if (lineResolve) {
    lineResolve(null);
    lineResolve = null;
  }
});

function readLine() {
  if (lineQueue.length > 0) {
    return Promise.resolve(lineQueue.shift());
  }
  return new Promise((resolve) => {
    lineResolve = resolve;
    // Safety timeout for the first line (command)
    setTimeout(() => {
      if (lineResolve === resolve) {
        lineResolve = null;
        resolve(null);
      }
    }, 10_000);
  });
}

function readLineNoTimeout() {
  if (lineQueue.length > 0) {
    return Promise.resolve(lineQueue.shift());
  }
  return new Promise((resolve) => {
    lineResolve = resolve;
  });
}

// ── main ─────────────────────────────────────────────────────────────────────

async function main() {
  // Read first line as JSON command
  const firstLine = await readLine();
  if (!firstLine) {
    emit('ERROR', 'No input received on stdin');
    process.exit(1);
  }

  let input;
  try {
    input = JSON.parse(firstLine);
  } catch (e) {
    emit('ERROR', `Failed to parse stdin JSON: ${e.message}`);
    process.exit(1);
  }

  const {
    command = 'send',
    message = '',
    sessionId,
    cwd = process.cwd(),
    permissionMode = 'default',
    model,
    maxTurns = 100,
    streaming = true,
    systemPrompt,
    claudePath,
  } = input;

  if (command !== 'send') {
    emit('ERROR', `Unknown command: ${command}`);
    process.exit(1);
  }

  if (!message) {
    emit('ERROR', 'Empty message');
    process.exit(1);
  }

  try {
    emit('STREAM_START', '');

    const options = {
      cwd,
      maxTurns,
      permissionMode,
      includePartialMessages: streaming !== false,
    };

    if (claudePath) options.pathToClaudeCodeExecutable = claudePath;
    if (model) options.model = model;
    if (sessionId) options.resume = sessionId;
    if (systemPrompt) options.appendSystemPrompt = systemPrompt;

    // Permission callback — emit request to plugin, wait for response on stdin
    options.canUseTool = async (toolName, toolInput, callbackOptions) => {
      emit('PERMISSION_REQUEST', { toolName, input: toolInput });

      // Wait for the plugin to respond via stdin
      const responseLine = await readLineNoTimeout();
      if (!responseLine) {
        return { behavior: 'deny', message: 'No permission response received' };
      }

      try {
        const response = JSON.parse(responseLine);
        if (response.allow) {
          return {
            behavior: 'allow',
            updatedInput: response.updatedInput || toolInput,
          };
        } else {
          return {
            behavior: 'deny',
            message: response.message || 'Permission denied by user',
          };
        }
      } catch (e) {
        return { behavior: 'deny', message: `Failed to parse permission response: ${e.message}` };
      }
    };

    const result = query({ prompt: message, options });

    // ── Streaming state ──────────────────────────────────────────────────
    // Track whether we've received any stream_event (raw SSE deltas).
    // When stream_events are available, assistant messages are redundant
    // for text — we only use them for tool_use blocks and state syncing.
    let hasStreamEvents = false;
    let lastAssistantContent = '';
    let lastThinkingContent = '';

    for await (const msg of result) {
      switch (msg.type) {
        case 'system': {
          if (msg.session_id) {
            emit('SESSION_ID', msg.session_id);
          }
          break;
        }

        case 'stream_event': {
          hasStreamEvents = true;
          const evt = msg.event;
          if (!evt) break;

          if (evt.type === 'content_block_delta') {
            const delta = evt.delta;
            if (delta?.type === 'text_delta' && delta.text) {
              emitDelta('CONTENT_DELTA', delta.text);
              lastAssistantContent += delta.text;
            } else if (delta?.type === 'thinking_delta' && delta.thinking) {
              emitDelta('THINKING_DELTA', delta.thinking);
              lastThinkingContent += delta.thinking;
            }
          }
          // Don't emit [MESSAGE] for stream_events — avoid polluting the parser
          break;
        }

        case 'assistant': {
          const content = msg.message?.content;
          if (!content) break;

          if (Array.isArray(content)) {
            for (const block of content) {
              if (block.type === 'text') {
                const currentText = block.text ?? '';
                if (hasStreamEvents) {
                  // Text already output via stream_events; just sync state
                  if (currentText.length > lastAssistantContent.length) {
                    lastAssistantContent = currentText;
                  }
                } else {
                  // No stream_events — fallback: compute diff from assistant message
                  if (currentText.length > lastAssistantContent.length) {
                    const delta = currentText.substring(lastAssistantContent.length);
                    emitDelta('CONTENT_DELTA', delta);
                    lastAssistantContent = currentText;
                  }
                }
              } else if (block.type === 'thinking') {
                const thinkingText = block.thinking ?? '';
                if (hasStreamEvents) {
                  if (thinkingText.length > lastThinkingContent.length) {
                    lastThinkingContent = thinkingText;
                  }
                } else {
                  if (thinkingText.length > lastThinkingContent.length) {
                    const delta = thinkingText.substring(lastThinkingContent.length);
                    emitDelta('THINKING_DELTA', delta);
                    lastThinkingContent = thinkingText;
                  }
                }
              } else if (block.type === 'tool_use') {
                emit('TOOL_USE', {
                  id: block.id,
                  name: block.name,
                  input: block.input,
                });
              } else if (block.type === 'tool_result') {
                emit('TOOL_RESULT', {
                  id: block.tool_use_id,
                  content: block.content,
                  isError: block.is_error ?? false,
                });
              }
            }
          } else if (typeof content === 'string') {
            // String content (rare)
            if (hasStreamEvents) {
              if (content.length > lastAssistantContent.length) {
                lastAssistantContent = content;
              }
            } else {
              if (content.length > lastAssistantContent.length) {
                const delta = content.substring(lastAssistantContent.length);
                emitDelta('CONTENT_DELTA', delta);
                lastAssistantContent = content;
              }
            }
          }

          // Usage info
          const usage = msg.message?.usage;
          if (usage) {
            emit('USAGE', {
              inputTokens: usage.input_tokens ?? 0,
              outputTokens: usage.output_tokens ?? 0,
              cacheRead: usage.cache_read_input_tokens ?? 0,
            });
          }

          emit('MESSAGE_END', '');
          break;
        }

        case 'result': {
          if (msg.is_error) {
            emit('ERROR', msg.error ?? 'Unknown SDK error');
          }
          if (msg.session_id) {
            emit('SESSION_ID', msg.session_id);
          }
          if (msg.usage) {
            emit('USAGE', {
              inputTokens: msg.usage?.input_tokens ?? 0,
              outputTokens: msg.usage?.output_tokens ?? 0,
              cacheRead: msg.usage?.cache_read_input_tokens ?? 0,
            });
          }
          break;
        }

        // 'user' messages (tool results from SDK) — we don't need to forward these
        default:
          break;
      }
    }

    emit('STREAM_END', '');
  } catch (e) {
    emit('ERROR', `SDK error: ${e.message ?? e}`);
    process.exit(1);
  }
}

// ── process-level error handlers ─────────────────────────────────────────────

process.on('uncaughtException', (err) => {
  emit('ERROR', `Uncaught: ${err.message}`);
  process.exit(1);
});

process.on('unhandledRejection', (reason) => {
  emit('ERROR', `Unhandled rejection: ${reason}`);
  process.exit(1);
});

main().then(() => process.exit(0));
