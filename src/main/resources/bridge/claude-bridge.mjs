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

  if (command === 'getSlashCommands') {
    const defaultCommands = [
      { name: '/help', description: 'Get help with using Claude Code' },
      { name: '/clear', description: 'Clear conversation history' },
      { name: '/compact', description: 'Compact conversation context' },
      { name: '/init', description: 'Initialize a new project' },
      { name: '/model', description: 'Change the current model' },
      { name: '/review', description: 'Review recent changes' },
    ];

    try {
      // AsyncStream — manually controlled async iterator.
      // SDK calls next() internally and waits; done() resolves the pending read,
      // which allows supportedCommands() to return.
      const inputStream = {
        queue: [],
        readResolve: undefined,
        isDone: false,
        started: false,
        [Symbol.asyncIterator]() {
          this.started = true;
          return this;
        },
        async next() {
          if (this.queue.length > 0) return { done: false, value: this.queue.shift() };
          if (this.isDone) return { done: true, value: undefined };
          return new Promise((resolve) => { this.readResolve = resolve; });
        },
        done() {
          this.isDone = true;
          if (this.readResolve) {
            const resolve = this.readResolve;
            this.readResolve = undefined;
            resolve({ done: true, value: undefined });
          }
        },
        async return() {
          this.isDone = true;
          return { done: true, value: undefined };
        },
      };

      // Clean env to avoid "cannot be launched inside another Claude Code session" error
      const cleanEnv = { ...process.env };
      for (const key of Object.keys(cleanEnv)) {
        if (key.startsWith('CLAUDE') || key === 'CLAUDECODE') delete cleanEnv[key];
      }

      const opts = {
        cwd,
        executable: process.execPath,
        env: cleanEnv,
        permissionMode: 'default',
        maxTurns: 0,
        canUseTool: async () => ({ behavior: 'deny', message: 'Config loading only' }),
      };
      if (claudePath) opts.pathToClaudeCodeExecutable = claudePath;

      const result = query({ prompt: inputStream, options: opts });

      // Signal that input is complete — unblocks SDK internal read
      inputStream.done();

      const timeout = (ms) => new Promise(r => setTimeout(() => r(null), ms));
      const commands = await Promise.race([
        result.supportedCommands?.() || Promise.resolve([]),
        timeout(15_000),
      ]) || defaultCommands;

      // Cleanup
      try { await Promise.race([result.return?.(), timeout(3_000)]); } catch (_) {}

      emit('SLASH_COMMANDS', commands.length > 0 ? commands : defaultCommands);
      process.exit(0);
    } catch (e) {
      process.stderr.write(`[BRIDGE] getSlashCommands error: ${e.message}\n`);
      emit('SLASH_COMMANDS', defaultCommands);
      process.exit(0);
    }
  }

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

    // Clean env to avoid "cannot be launched inside another Claude Code session" error
    const sendEnv = { ...process.env };
    for (const key of Object.keys(sendEnv)) {
      if (key.startsWith('CLAUDE') || key === 'CLAUDECODE') delete sendEnv[key];
    }

    const options = {
      cwd,
      executable: process.execPath,
      env: sendEnv,
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

          // Debug: log all stream_event types to stderr
          process.stderr.write(`[BRIDGE_SE] type=${evt.type}\n`);

          // Detect plan mode tools from raw stream events
          // (SDK handles these internally and doesn't call canUseTool)
          if (evt.type === 'content_block_start') {
            const block = evt.content_block;
            process.stderr.write(`[BRIDGE_CBS] block_type=${block?.type} name=${block?.name}\n`);
            if (block?.type === 'tool_use') {
              if (block.name === 'EnterPlanMode') {
                emit('PLAN_MODE', 'enter');
              } else if (block.name === 'ExitPlanMode') {
                emit('PLAN_MODE', 'exit');
              }
            }
          }

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

          // Debug: log all content block types
          if (Array.isArray(content)) {
            const types = content.map(b => `${b.type}${b.name ? ':' + b.name : ''}`).join(', ');
            process.stderr.write(`[BRIDGE_AST] blocks: [${types}]\n`);
          }

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
              cacheCreation: usage.cache_creation_input_tokens ?? 0,
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
          // NOTE: Do NOT emit USAGE from result — result.usage is cumulative across
          // all API calls in the agentic loop, not the actual context window usage.
          // Context window usage comes from the last assistant message's usage.
          break;
        }

        case 'user': {
          // Tool results from the SDK (bash output, file read results, etc.)
          const userContent = msg.message?.content;
          if (!userContent || !Array.isArray(userContent)) break;

          for (const block of userContent) {
            if (block.type === 'tool_result') {
              const resultText = typeof block.content === 'string'
                ? block.content
                : Array.isArray(block.content)
                  ? block.content.filter(b => b.type === 'text').map(b => b.text).join('\n')
                  : '';
              emit('TOOL_RESULT', {
                id: block.tool_use_id,
                content: resultText,
                isError: block.is_error ?? false,
              });
            }
          }
          break;
        }

        default:
          process.stderr.write(`[BRIDGE_OTHER] type=${msg.type}\n`);
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
