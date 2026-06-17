'use client'

import ReactMarkdown       from 'react-markdown'
import remarkGfm           from 'remark-gfm'
import rehypeHighlight     from 'rehype-highlight'
import rehypeSanitize, { defaultSchema } from 'rehype-sanitize'
import { useState, useMemo } from 'react'
import { ChevronDown, ChevronUp, GitCommitHorizontal, Calendar, Hash } from 'lucide-react'
import type { EvolutionLogItem } from '@/types'
import { formatDateTime, shortId } from '@/lib/utils'

// Import a dark highlight.js theme (GitHub dark variant bundled with the package)
import 'highlight.js/styles/github-dark.css'

// rehype-sanitize schema: extends the safe default to preserve hljs-* class
// names added by rehype-highlight, and block javascript: / vbscript: links.
const sanitizeSchema = {
  ...defaultSchema,
  attributes: {
    ...defaultSchema.attributes,
    // Allow any class on <code> and <span> so syntax-highlight spans survive.
    code: [...(defaultSchema.attributes?.code ?? []), ['className', /^language-/]],
    span: [...(defaultSchema.attributes?.span ?? []), ['className', /^hljs-/]],
  },
}

// ── Single timeline entry card ────────────────────────────────────────────────

interface EntryCardProps {
  entry: EvolutionLogItem
  index:  number
  isLast: boolean
}

function EntryCard({ entry, index, isLast }: EntryCardProps) {
  const [expanded, setExpanded] = useState(index === 0)

  return (
    <div className="relative flex gap-4">
      {/* Vertical connector line */}
      {!isLast && (
        <div
          className="absolute left-[19px] top-10 bottom-0 w-px bg-gradient-to-b from-indigo-500/40 to-transparent"
          aria-hidden
        />
      )}

      {/* Timeline dot */}
      <div className="relative z-10 mt-1 flex h-10 w-10 shrink-0 items-center justify-center rounded-full border border-indigo-500/40 bg-indigo-600/15">
        <GitCommitHorizontal size={16} className="text-indigo-400" />
      </div>

      {/* Card */}
      <div className="mb-6 flex-1 rounded-xl border border-[#1e1e3a] bg-[#111127] overflow-hidden">

        {/* Header */}
        <button
          onClick={() => setExpanded(v => !v)}
          className="w-full flex items-center justify-between gap-3 px-5 py-4 hover:bg-white/[0.02] transition-colors text-left"
        >
          <div className="flex flex-wrap items-center gap-3">
            {/* Date badge */}
            <span className="inline-flex items-center gap-1.5 rounded-md bg-slate-700/40 px-2.5 py-1 text-xs text-slate-400 ring-1 ring-slate-600/30">
              <Calendar size={11} />
              {formatDateTime(entry.createdAt)}
            </span>

            {/* Run ID badge */}
            <span className="inline-flex items-center gap-1.5 rounded-md bg-indigo-600/15 px-2.5 py-1 text-xs font-mono text-indigo-300 ring-1 ring-indigo-500/20">
              <Hash size={11} />
              run:{shortId(entry.runId)}
            </span>

            {/* Report score chip — parsed from the first heading if available */}
            <ScoreBadge report={entry.refactoringReport} />
          </div>

          <span className="shrink-0 text-slate-500 hover:text-slate-300 transition-colors">
            {expanded ? <ChevronUp size={16} /> : <ChevronDown size={16} />}
          </span>
        </button>

        {/* Markdown body */}
        {expanded && (
          <div className="border-t border-[#1e1e3a] px-5 py-5">
            <div className="prose prose-invert prose-sm max-w-none
              prose-headings:text-slate-100 prose-headings:font-semibold
              prose-h1:text-base prose-h2:text-sm prose-h3:text-xs
              prose-p:text-slate-300 prose-p:leading-relaxed
              prose-a:text-indigo-400 prose-a:no-underline hover:prose-a:underline
              prose-strong:text-slate-200
              prose-code:text-indigo-300 prose-code:bg-indigo-950/60 prose-code:rounded prose-code:px-1.5 prose-code:py-0.5 prose-code:text-xs prose-code:font-mono prose-code:before:content-none prose-code:after:content-none
              prose-pre:bg-[#0d1117] prose-pre:border prose-pre:border-[#1e1e3a] prose-pre:rounded-xl prose-pre:text-xs prose-pre:my-3 prose-pre:p-0
              prose-blockquote:border-l-indigo-500 prose-blockquote:text-slate-400
              prose-table:text-xs prose-th:text-slate-400 prose-td:text-slate-300
              prose-hr:border-[#1e1e3a]
              prose-li:text-slate-300 prose-li:marker:text-indigo-500">
              <ReactMarkdown
                remarkPlugins={[remarkGfm]}
                rehypePlugins={[
                  rehypeHighlight,
                  [rehypeSanitize, sanitizeSchema],
                ]}
              >
                {entry.refactoringReport}
              </ReactMarkdown>
            </div>
          </div>
        )}

      </div>
    </div>
  )
}

// ── Score badge — extracts rating from report text ────────────────────────────

function ScoreBadge({ report }: { report: string }) {
  const { match, isPass } = useMemo(() => {
    const passMatch = report.match(/✅\s+([\w\s]+)\s+\((\d+(?:\.\d+)?\s*\/\s*10)\)/i)
    const warnMatch = report.match(/⚠️\s+([\w\s]+)\s+\((\d+(?:\.\d+)?\s*\/\s*10)\)/i)
    return { match: passMatch ?? warnMatch, isPass: !!passMatch }
  }, [report])

  if (!match) return null

  const score = match[2]

  return (
    <span className={`inline-flex items-center gap-1 rounded-md px-2 py-0.5 text-xs font-medium ring-1 ${
      isPass
        ? 'bg-emerald-500/10 text-emerald-400 ring-emerald-500/20'
        : 'bg-amber-500/10 text-amber-400 ring-amber-500/20'
    }`}>
      {isPass ? '✅' : '⚠️'} {score}
    </span>
  )
}

// ── Empty state ───────────────────────────────────────────────────────────────

function EmptyState() {
  return (
    <div className="flex flex-col items-center gap-4 rounded-2xl border border-dashed border-[#1e1e3a] bg-[#0d0d1f] px-8 py-16 text-center">
      <div className="flex h-16 w-16 items-center justify-center rounded-full bg-indigo-600/10 ring-1 ring-indigo-500/20">
        <GitCommitHorizontal size={28} className="text-indigo-500" />
      </div>
      <div>
        <p className="text-slate-300 font-medium mb-1">История эволюции пуста</p>
        <p className="text-slate-500 text-sm leading-relaxed max-w-xs">
          Цикл эволюционного анализа ещё не запускался. Завершите хотя бы один запуск с флагом
          <code className="mx-1 rounded bg-slate-800 px-1.5 py-0.5 text-xs text-slate-300 font-mono">needsRefactoring=true</code>
          — и отчёт появится здесь автоматически.
        </p>
      </div>
    </div>
  )
}

// ── Main exported component ───────────────────────────────────────────────────

interface Props {
  entries: EvolutionLogItem[]
}

export default function EvolutionTimeline({ entries }: Props) {
  if (entries.length === 0) return <EmptyState />

  return (
    <div className="relative">
      {entries.map((entry, i) => (
        <EntryCard
          key={entry.id}
          entry={entry}
          index={i}
          isLast={i === entries.length - 1}
        />
      ))}
    </div>
  )
}
