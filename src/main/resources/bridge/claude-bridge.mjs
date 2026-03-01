/**
 * Ultimate Claude GUI — Node.js Bridge
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

import { query, listSessions, getSessionMessages } from '@anthropic-ai/claude-agent-sdk';
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

// ── abort / control support ───────────────────────────────────────────────────

const abortController = new AbortController();
let activeQuery = null; // Reference to the Query object for runtime control

/**
 * Listen for control commands on stdin (separate from the line-based readLine queue).
 * Supported commands:
 *   {"command":"abort"}             — signal AbortController to gracefully stop
 *   {"command":"stopTask","taskId":"..."} — stop a specific background task
 */
function setupAbortListener() {
  // Override the line handler to intercept control commands before they enter the queue
  const originalOnLine = rl.listeners('line').slice();
  rl.removeAllListeners('line');

  rl.on('line', (line) => {
    // Try to detect control commands
    try {
      const parsed = JSON.parse(line);
      if (parsed.command === 'abort') {
        process.stderr.write('[BRIDGE] Received abort command\n');
        abortController.abort();
        return; // Don't enqueue this line
      }
      if (parsed.command === 'stopTask' && parsed.taskId) {
        process.stderr.write(`[BRIDGE] Received stopTask command for taskId=${parsed.taskId}\n`);
        if (activeQuery && typeof activeQuery.stopTask === 'function') {
          activeQuery.stopTask(parsed.taskId).catch(e => {
            process.stderr.write(`[BRIDGE] stopTask failed: ${e.message}\n`);
          });
        } else {
          process.stderr.write('[BRIDGE] No active query or stopTask not available\n');
        }
        return; // Don't enqueue this line
      }
      if (parsed.command === 'rewindFiles' && parsed.userMessageId) {
        process.stderr.write(`[BRIDGE] Received rewindFiles command for userMessageId=${parsed.userMessageId}\n`);
        if (activeQuery && typeof activeQuery.rewindFiles === 'function') {
          activeQuery.rewindFiles(parsed.userMessageId).then(() => {
            emit('REWIND_RESULT', { success: true, userMessageId: parsed.userMessageId });
          }).catch(e => {
            process.stderr.write(`[BRIDGE] rewindFiles failed: ${e.message}\n`);
            emit('REWIND_RESULT', { success: false, error: e.message });
          });
        } else {
          process.stderr.write('[BRIDGE] No active query or rewindFiles not available\n');
          emit('REWIND_RESULT', { success: false, error: 'Not available' });
        }
        return;
      }
      if (parsed.command === 'setModel' && parsed.model) {
        process.stderr.write(`[BRIDGE] Received setModel command: ${parsed.model}\n`);
        if (activeQuery && typeof activeQuery.setModel === 'function') {
          activeQuery.setModel(parsed.model).catch(e => {
            process.stderr.write(`[BRIDGE] setModel failed: ${e.message}\n`);
          });
        }
        return;
      }
      if (parsed.command === 'setPermissionMode' && parsed.mode) {
        process.stderr.write(`[BRIDGE] Received setPermissionMode command: ${parsed.mode}\n`);
        if (activeQuery && typeof activeQuery.setPermissionMode === 'function') {
          activeQuery.setPermissionMode(parsed.mode).catch(e => {
            process.stderr.write(`[BRIDGE] setPermissionMode failed: ${e.message}\n`);
          });
        }
        return;
      }
    } catch (_) {
      // Not JSON or not a control command — fall through
    }

    // Forward to original handler (lineQueue logic)
    if (lineResolve) {
      const resolve = lineResolve;
      lineResolve = null;
      resolve(line);
    } else {
      lineQueue.push(line);
    }
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
    effort,
    maxBudgetUsd,
    betaContext1m = false,
    continueSession = false,
    allowedTools,
    disallowedTools,
    outputFormat,
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
        executable: 'node',
        env: cleanEnv,
        permissionMode: 'default',
        maxTurns: 1,
        canUseTool: async () => ({ behavior: 'deny', message: 'Config loading only' }),
      };
      // SDK uses its built-in cli.js — avoids Windows .cmd spawn issues

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

  // ── listSessions command ────────────────────────────────────────────────────
  if (command === 'listSessions') {
    try {
      const dir = input.dir || undefined;
      const limit = input.limit || undefined;
      const sessions = await listSessions({ dir, limit });
      emit('SESSIONS', sessions);
      process.exit(0);
    } catch (e) {
      process.stderr.write(`[BRIDGE] listSessions error: ${e.message}\n`);
      emit('ERROR', `listSessions failed: ${e.message}`);
      process.exit(1);
    }
  }

  // ── getSessionMessages command ──────────────────────────────────────────────
  if (command === 'getSessionMessages') {
    try {
      const sessionId = input.sessionId;
      if (!sessionId) {
        emit('ERROR', 'Missing sessionId for getSessionMessages');
        process.exit(1);
      }
      const dir = input.dir || undefined;
      const limit = input.limit || undefined;
      const messages = await getSessionMessages(sessionId, { dir, limit });
      emit('SESSION_MESSAGES', messages);
      process.exit(0);
    } catch (e) {
      process.stderr.write(`[BRIDGE] getSessionMessages error: ${e.message}\n`);
      emit('ERROR', `getSessionMessages failed: ${e.message}`);
      process.exit(1);
    }
  }

  if (command !== 'send') {
    emit('ERROR', `Unknown command: ${command}`);
    process.exit(1);
  }

  // After reading the initial command, set up abort listener for subsequent stdin lines
  setupAbortListener();

  if (!message) {
    emit('ERROR', 'Empty message');
    process.exit(1);
  }

  // Collect SDK internal stderr for better error diagnostics
  const sdkStderrLines = [];

  try {
    emit('STREAM_START', '');

    // Clean env to avoid "cannot be launched inside another Claude Code session" error
    const sendEnv = { ...process.env };
    for (const key of Object.keys(sendEnv)) {
      if (key.startsWith('CLAUDE') || key === 'CLAUDECODE') delete sendEnv[key];
    }
    const options = {
      cwd,
      executable: 'node',
      env: sendEnv,
      maxTurns,
      permissionMode,
      includePartialMessages: streaming !== false,
      settingSources: ['project', 'user', 'local'],
      thinking: { type: 'adaptive' },
      promptSuggestions: true,
      abortSignal: abortController.signal,
      stderr: (msg) => {
        const line = msg.trimEnd();
        if (line) {
          sdkStderrLines.push(line);
          process.stderr.write(`[SDK] ${line}\n`);
        }
      },
    };

    // SDK uses its built-in cli.js — avoids Windows .cmd spawn issues
    if (model) options.model = model;
    if (sessionId) options.resume = sessionId;
    if (continueSession) options.continue = true;
    if (systemPrompt) options.systemPrompt = { type: 'preset', preset: 'claude_code', append: systemPrompt };
    if (effort) options.effort = effort;
    if (maxBudgetUsd) options.maxBudgetUsd = parseFloat(maxBudgetUsd);
    if (betaContext1m) options.betas = ['context-1m-2025-08-07'];
    const parseToolList = (value) => {
      if (!value) return [];
      if (Array.isArray(value)) {
        return value.map(v => `${v}`.trim()).filter(Boolean);
      }
      if (typeof value === 'string') {
        return value.split(',').map(s => s.trim()).filter(Boolean);
      }
      return [];
    };
    const allowed = parseToolList(allowedTools);
    if (allowed.length > 0) options.allowedTools = allowed;
    const disallowed = parseToolList(disallowedTools);
    if (disallowed.length > 0) options.disallowedTools = disallowed;
    if (outputFormat && typeof outputFormat === 'object') options.outputFormat = outputFormat;
    if (input.enableFileCheckpointing) options.enableFileCheckpointing = true;
    if (input.mcpServers) options.mcpServers = input.mcpServers;
    if (input.fallbackModel) options.fallbackModel = input.fallbackModel;
    if (input.additionalDirectories) options.additionalDirectories = input.additionalDirectories;
    if (input.agents) options.agents = input.agents;
    if (input.hooks) options.hooks = input.hooks;
    if (input.persistSession === false) options.persistSession = false;
    if (input.forkSession) options.forkSession = true;
    if (input.sandbox) options.sandbox = input.sandbox;
    if (input.plugins) options.plugins = input.plugins;

    // Permission callback — emit request to plugin, wait for response on stdin
    options.canUseTool = async (toolName, toolInput, callbackOptions) => {
      process.stderr.write(`[BRIDGE_CAN_USE_TOOL] toolName=${toolName} inputKeys=${Object.keys(toolInput || {}).join(',')}\n`);
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

    // Elicitation callback — MCP servers can request user input
    options.onElicitation = async (elicitationRequest) => {
      process.stderr.write(`[BRIDGE_ELICITATION] type=${elicitationRequest.type}\n`);
      emit('ELICITATION_REQUEST', {
        type: elicitationRequest.type ?? 'text',
        title: elicitationRequest.title ?? '',
        description: elicitationRequest.description ?? '',
        schema: elicitationRequest.schema ?? null,
      });

      // Wait for plugin response via stdin
      const responseLine = await readLineNoTimeout();
      if (!responseLine) {
        return { behavior: 'deny', message: 'No elicitation response received' };
      }

      try {
        const response = JSON.parse(responseLine);
        if (response.allow) {
          return { behavior: 'allow', value: response.value };
        } else {
          return { behavior: 'deny', message: response.message || 'Elicitation denied by user' };
        }
      } catch (e) {
        return { behavior: 'deny', message: `Failed to parse elicitation response: ${e.message}` };
      }
    };

    const result = query({ prompt: message, options });
    activeQuery = result; // Store reference for stopTask / runtime control

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
          const subtype = msg.subtype;
          if (subtype) {
            process.stderr.write(`[BRIDGE_SYSTEM] subtype=${subtype}\n`);
          }

          if (subtype === 'init') {
            emit('INIT', {
              tools: msg.tools ?? [],
              model: msg.model ?? '',
              mcpServers: (msg.mcp_servers ?? []).map(s => ({ name: s.name, status: s.status })),
              agents: msg.agents ?? [],
              skills: msg.skills ?? [],
              plugins: (msg.plugins ?? []).map(p => ({ name: p.name, path: p.path })),
              slashCommands: msg.slash_commands ?? [],
              claudeCodeVersion: msg.claude_code_version ?? '',
              apiKeySource: msg.apiKeySource ?? '',
              permissionMode: msg.permissionMode ?? '',
              fastModeState: msg.fast_mode_state ?? null,
              cwd: msg.cwd ?? '',
              betas: msg.betas ?? [],
            });
          } else if (subtype === 'compact_boundary') {
            emit('COMPACT_BOUNDARY', {
              trigger: msg.compact_metadata?.trigger ?? 'manual',
              preTokens: msg.compact_metadata?.pre_tokens ?? 0,
            });
          } else if (subtype === 'status') {
            emit('SDK_STATUS', {
              status: msg.status ?? null,
              permissionMode: msg.permissionMode ?? null,
            });
          } else if (subtype === 'task_started') {
            emit('TASK_STARTED', {
              taskId: msg.task_id,
              toolUseId: msg.tool_use_id ?? null,
              description: msg.description ?? '',
              taskType: msg.task_type ?? null,
            });
          } else if (subtype === 'task_progress') {
            emit('TASK_PROGRESS', {
              taskId: msg.task_id,
              toolUseId: msg.tool_use_id ?? null,
              description: msg.description ?? '',
              usage: {
                totalTokens: msg.usage?.total_tokens ?? 0,
                toolUses: msg.usage?.tool_uses ?? 0,
                durationMs: msg.usage?.duration_ms ?? 0,
              },
              lastToolName: msg.last_tool_name ?? null,
            });
          } else if (subtype === 'task_notification') {
            emit('TASK_NOTIFICATION', {
              taskId: msg.task_id,
              toolUseId: msg.tool_use_id ?? null,
              status: msg.status ?? 'completed',
              outputFile: msg.output_file ?? '',
              summary: msg.summary ?? '',
              usage: {
                totalTokens: msg.usage?.total_tokens ?? 0,
                toolUses: msg.usage?.tool_uses ?? 0,
                durationMs: msg.usage?.duration_ms ?? 0,
              },
            });
          } else if (subtype === 'hook_started' || subtype === 'hook_progress' || subtype === 'hook_response') {
            emit('HOOK_EVENT', {
              hookName: msg.hook_name ?? '',
              hookEvent: subtype,
              toolName: msg.tool_name ?? null,
              progress: msg.progress ?? msg.response ?? null,
            });
          } else if (subtype === 'files_persisted') {
            emit('FILES_PERSISTED', {
              files: msg.files ?? [],
            });
          } else if (subtype === 'local_command_output' && msg.content) {
            // Local slash command output is assistant-style text; surface it.
            emitDelta('CONTENT_DELTA', msg.content);
          }
          break;
        }

        case 'tool_progress': {
          emit('TOOL_PROGRESS', {
            toolUseId: msg.tool_use_id ?? '',
            toolName: msg.tool_name ?? '',
            parentToolUseId: msg.parent_tool_use_id ?? null,
            elapsedTimeSeconds: msg.elapsed_time_seconds ?? 0,
            taskId: msg.task_id ?? null,
          });
          break;
        }

        case 'tool_use_summary': {
          emit('TOOL_USE_SUMMARY', {
            summary: msg.summary ?? '',
            precedingToolUseIds: msg.preceding_tool_use_ids ?? [],
          });
          break;
        }

        case 'rate_limit_event': {
          emit('RATE_LIMIT', msg.rate_limit_info ?? {});
          break;
        }

        case 'auth_status': {
          emit('AUTH_STATUS', {
            isAuthenticating: !!msg.isAuthenticating,
            output: Array.isArray(msg.output) ? msg.output : [],
            error: msg.error ?? null,
          });
          break;
        }

        case 'prompt_suggestion': {
          emit('PROMPT_SUGGESTION', {
            suggestion: msg.suggestion ?? '',
          });
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

          // Debug: log all content block types and preview text
          if (Array.isArray(content)) {
            const types = content.map(b => `${b.type}${b.name ? ':' + b.name : ''}`).join(', ');
            process.stderr.write(`[BRIDGE_AST] blocks: [${types}]\n`);
            // Log text preview to diagnose short/error responses
            for (const b of content) {
              if (b.type === 'text' && b.text) {
                const preview = b.text.length > 200 ? b.text.substring(0, 200) + '...' : b.text;
                process.stderr.write(`[BRIDGE_AST_TEXT] len=${b.text.length} "${preview}"\n`);
              }
            }
          }
          // Log stop_reason and model from message
          const stopReason = msg.message?.stop_reason;
          const msgModel = msg.message?.model;
          if (stopReason || msgModel) {
            process.stderr.write(`[BRIDGE_AST_META] stop_reason=${stopReason} model=${msgModel}\n`);
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
                // Always emit delta if we have new thinking content,
                // even when hasStreamEvents — SDK may not send thinking_delta
                // stream events but still include thinking in assistant messages
                if (thinkingText.length > lastThinkingContent.length) {
                  const delta = thinkingText.substring(lastThinkingContent.length);
                  emitDelta('THINKING_DELTA', delta);
                  lastThinkingContent = thinkingText;
                }
              } else if (block.type === 'tool_use') {
                emit('TOOL_USE', {
                  id: block.id,
                  name: block.name,
                  input: block.input,
                });
              } else if (block.type === 'tool_result') {
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
          const subtype = msg.subtype ?? '';
          process.stderr.write(`[BRIDGE_RESULT] is_error=${msg.is_error} subtype=${subtype} session_id=${msg.session_id} errors=${msg.errors}\n`);
          if (msg.is_error) {
            let errText;
            if (subtype === 'error_max_budget_usd') {
              errText = `Budget limit reached ($${maxBudgetUsd || '?'})`;
            } else if (subtype === 'error_max_turns') {
              errText = `Max turns limit reached (${maxTurns})`;
            } else {
              errText = Array.isArray(msg.errors) && msg.errors.length > 0
                ? msg.errors.join(' | ')
                : 'Unknown SDK error';
            }
            emit('ERROR', errText);
          }
          // Emit total cost info from result
          if (msg.cost_usd != null) {
            emit('COST', { costUsd: msg.cost_usd, durationMs: msg.duration_ms ?? 0 });
          }
          if (msg.session_id) {
            emit('SESSION_ID', msg.session_id);
          }
          // Emit rich result metadata (L14, L15)
          const resultMeta = {};
          if (msg.fast_mode_state) resultMeta.fastModeState = msg.fast_mode_state;
          if (msg.model_usage) {
            // Normalize model_usage: SDK may return Map or plain object
            const usage = msg.model_usage;
            const normalized = {};
            if (usage && typeof usage === 'object' && !Array.isArray(usage)) {
              // Could be a Map or plain object
              const entries = usage instanceof Map ? usage.entries() : Object.entries(usage);
              for (const [model, data] of entries) {
                if (data && typeof data === 'object' && !Array.isArray(data)) {
                  normalized[model] = {
                    input_tokens: data.input_tokens ?? 0,
                    output_tokens: data.output_tokens ?? 0,
                  };
                }
              }
            }
            resultMeta.modelUsage = normalized;
            process.stderr.write(`[BRIDGE_MODEL_USAGE] raw=${JSON.stringify(msg.model_usage)} normalized=${JSON.stringify(normalized)}\n`);
          }
          if (typeof msg.permission_denials === 'number') resultMeta.permissionDenials = msg.permission_denials;
          if (Object.keys(resultMeta).length > 0) {
            emit('RESULT_META', resultMeta);
          }
          // NOTE: Do NOT emit USAGE from result — result.usage is cumulative across
          // all API calls in the agentic loop, not the actual context window usage.
          // Context window usage comes from the last assistant message's usage.
          break;
        }

        case 'user': {
          if (msg.message?.id) {
            emit('USER_MESSAGE_ID', msg.message.id);
          }
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
    // Graceful abort — not an error
    if (e.name === 'AbortError' || abortController.signal.aborted) {
      process.stderr.write('[BRIDGE] Query aborted gracefully\n');
      emit('STREAM_END', '');
      process.exit(0);
      return;
    }

    let errMsg = `SDK error: ${e.message ?? e}`;
    // Extract meaningful error lines from SDK stderr (single-line safe for protocol)
    const meaningful = sdkStderrLines.filter(l =>
      /TypeError:|SyntaxError:|ReferenceError:|RangeError:|Cannot find|ENOENT|EACCES|EPERM|ERR_/i.test(l)
    );
    if (meaningful.length > 0) {
      errMsg += ' | ' + meaningful.slice(-3).map(l => l.replace(/\n/g, ' ')).join(' | ');
    }
    // Write to stderr first (always read by plugin) so error isn't lost
    process.stderr.write(`[BRIDGE_ERROR] ${errMsg}\n`);
    if (e.stack) process.stderr.write(`[BRIDGE_STACK] ${e.stack.replace(/\n/g, ' | ')}\n`);
    emit('ERROR', errMsg);
    process.exit(1);
  }
}

// ── process-level error handlers ─────────────────────────────────────────────

process.on('uncaughtException', (err) => {
  process.stderr.write(`[BRIDGE_UNCAUGHT] ${err.message}\n`);
  if (err.stack) process.stderr.write(`[BRIDGE_STACK] ${err.stack.replace(/\n/g, ' | ')}\n`);
  emit('ERROR', `Uncaught: ${err.message}`);
  process.exit(1);
});

process.on('unhandledRejection', (reason) => {
  process.stderr.write(`[BRIDGE_UNHANDLED] ${reason}\n`);
  emit('ERROR', `Unhandled rejection: ${reason}`);
  process.exit(1);
});

main().then(() => process.exit(0));
