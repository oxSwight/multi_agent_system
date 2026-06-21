'use client'

import { useState } from 'react'
import { ChevronDown, AlertTriangle, Clock, Zap } from 'lucide-react'
import type { MidasAgentLog } from '@/types'
import { formatDuration, formatTokens, agentDisplayName, formatCostUsd } from '@/lib/utils'
import { cn } from '@/lib/utils'

interface AgentLogCardProps {
  log: MidasAgentLog
  defaultOpen?: boolean
}

// ── Lightweight syntax highlighter ───────────────────────────────────────────
// Handles JSON objects and markdown-style code fences without external deps.

/** Escapes all special HTML characters. Must be applied before injecting any
 *  user-originated string into dangerouslySetInnerHTML. */
function escapeHtml(str: string): string {
  return str
    .replace(/&/g, '&amp;')   // must be first — prevents double-escaping
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#x27;')
}

function renderHighlightedOutput(raw: string | null): React.ReactNode {
  if (!raw) {
    return <span className="text-slate-600 italic">Нет вывода</span>
  }

  const trimmed = raw.trim()

  // Detect code fence blocks (```lang\n...\n```)
  // FIX: escape the ENTIRE string first, then apply the span wrapper on the
  // already-safe content. The previous code only escaped the code block body,
  // leaving any text OUTSIDE the fence (before/after ```) raw in innerHTML.
  const codeFenceRegex = /^```(\w*)\n([\s\S]*?)```$/m
  if (codeFenceRegex.test(trimmed)) {
    const fullyEscaped = escapeHtml(trimmed)
    const result = fullyEscaped.replace(codeFenceRegex, (_m, lang, code) =>
      `<span class="code-fence">// ${lang || 'code'}</span>\n${code}`
    )
    return (
      <pre
        className="agent-output"
        dangerouslySetInnerHTML={{ __html: result }}
      />
    )
  }

  // Detect JSON
  const isJson = (trimmed.startsWith('{') || trimmed.startsWith('['))
  if (isJson) {
    try {
      const parsed    = JSON.parse(trimmed)
      const formatted = JSON.stringify(parsed, null, 2)
      // HTML-escape BEFORE injecting <span> tags so values can never
      // break out of their span context.
      const highlighted = escapeHtml(formatted)
        // JSON keys
        .replace(/"([^"]+)"(?=\s*:)/g, '<span class="json-key">"$1"</span>')
        // String values
        .replace(/:\s*"([^"]*)"/g, ': <span class="json-string">"$1"</span>')
        // Numbers
        .replace(/:\s*(-?\d+\.?\d*)/g, ': <span class="json-number">$1</span>')
        // Booleans
        .replace(/:\s*(true|false)/g, ': <span class="json-bool">$1</span>')
        // Null
        .replace(/:\s*null/g, ': <span class="json-null">null</span>')

      return (
        <pre
          className="agent-output"
          dangerouslySetInnerHTML={{ __html: highlighted }}
        />
      )
    } catch {
      // Not valid JSON — fall through to plain text
    }
  }

  // Plain text — escape everything, no HTML injection possible
  return (
    <pre
      className="agent-output"
      dangerouslySetInnerHTML={{ __html: escapeHtml(trimmed) }}
    />
  )
}

// ── Accordion card ────────────────────────────────────────────────────────────

export default function AgentLogCard({ log, defaultOpen = false }: AgentLogCardProps) {
  const [open, setOpen] = useState(defaultOpen)

  const totalTokens = log.promptTokens + log.completionTokens

  return (
    <div
      className={cn(
        'rounded-xl border transition-colors',
        log.isError
          ? 'border-red-500/25 bg-red-500/5'
          : 'border-[#1e1e3a] bg-[#111127]',
      )}
    >
      {/* ── Header (always visible) ──────────────────────────────────── */}
      <button
        type="button"
        onClick={() => setOpen(v => !v)}
        className="flex w-full items-center justify-between px-5 py-4 text-left"
      >
        <div className="flex flex-wrap items-center gap-3">
          {/* Agent name */}
          <p className={cn(
            'text-sm font-semibold',
            log.isError ? 'text-red-300' : 'text-slate-200',
          )}>
            {agentDisplayName(log.agentType)}
          </p>

          {/* Error badge */}
          {log.isError && (
            <span className="inline-flex items-center gap-1 rounded-full border border-red-500/25 bg-red-500/10 px-2 py-0.5 text-[10px] font-medium text-red-400">
              <AlertTriangle size={9} />
              Error
            </span>
          )}

          {/* Execution time badge */}
          <span className="inline-flex items-center gap-1 rounded-md border border-[#1e1e3a] bg-[#0d0d1f] px-2 py-0.5 font-mono text-[10px] text-slate-400">
            <Clock size={9} className="shrink-0" />
            {formatDuration(log.executionTimeMs)}
          </span>

          {/* Token badge */}
          {totalTokens > 0 && (
            <span className="inline-flex items-center gap-1 rounded-md border border-[#1e1e3a] bg-[#0d0d1f] px-2 py-0.5 font-mono text-[10px] text-slate-400">
              <Zap size={9} className="shrink-0" />
              {formatTokens(totalTokens)} tokens
            </span>
          )}
        </div>

        {/* Chevron */}
        <ChevronDown
          size={15}
          className={cn(
            'shrink-0 text-slate-600 transition-transform duration-200',
            open && 'rotate-180',
          )}
        />
      </button>

      {/* ── Expandable body ──────────────────────────────────────────── */}
      {open && (
        <div className="border-t border-[#1e1e3a] px-5 pb-5 pt-4">
          {/* Token breakdown */}
          {totalTokens > 0 && (
            <div className="mb-3 flex flex-wrap gap-4 text-xs text-slate-500">
              <span>Prompt: <span className="font-mono text-slate-400">{formatTokens(log.promptTokens)}</span></span>
              <span>Completion: <span className="font-mono text-slate-400">{formatTokens(log.completionTokens)}</span></span>
              {log.modelId && (
                <span>Model: <span className="font-mono text-slate-400">{log.modelId}</span></span>
              )}
              {log.finishReason && (
                <span>Finish: <span className="font-mono text-slate-400">{log.finishReason}</span></span>
              )}
              {log.estimatedCostUsd != null && log.estimatedCostUsd > 0 && (
                <span>Cost: <span className="font-mono text-slate-400">{formatCostUsd(log.estimatedCostUsd)}</span></span>
              )}
            </div>
          )}

          {/* Raw output */}
          <div className="rounded-lg border border-[#1a1a30] bg-[#0a0a18] p-4 overflow-x-auto">
            {renderHighlightedOutput(log.rawOutput)}
          </div>
        </div>
      )}
    </div>
  )
}
