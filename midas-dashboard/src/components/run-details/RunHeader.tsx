import Link from 'next/link'
import { ArrowLeft, Archive, Hash } from 'lucide-react'
import type { MidasRun } from '@/types'
import { formatDateTime, formatTokens, formatDuration, pipelineDuration, formatCostUsd } from '@/lib/utils'
import StatusBadge from '@/components/dashboard/StatusBadge'

interface RunHeaderProps {
  run: MidasRun
}

export default function RunHeader({ run }: RunHeaderProps) {
  const totalTokens = run.totalPromptTokens + run.totalCompletionTokens
  const duration    = pipelineDuration(run)

  return (
    <div className="rounded-xl border border-[#1e1e3a] bg-[#111127] p-6">

      {/* ── Back link ───────────────────────────────────────────────── */}
      <Link
        href="/"
        className="mb-5 inline-flex items-center gap-1.5 text-xs text-slate-500 transition-colors hover:text-indigo-400"
      >
        <ArrowLeft size={12} />
        Назад к дашборду
      </Link>

      {/* ── Run ID + Status row ──────────────────────────────────────── */}
      <div className="flex flex-wrap items-start gap-3">
        <div className="flex items-center gap-2 text-slate-500">
          <Hash size={14} />
          <span className="font-mono text-sm text-slate-400">{run.id}</span>
        </div>
        <StatusBadge status={run.status} />
        {run.needsRefactoring && (
          <span className="inline-flex items-center rounded-full border border-orange-500/25 bg-orange-500/10 px-2.5 py-0.5 text-xs font-medium text-orange-400">
            Требует рефакторинга
          </span>
        )}
      </div>

      {/* ── Idea ────────────────────────────────────────────────────── */}
      <p className="mt-4 text-lg font-semibold leading-snug text-slate-100">
        {run.rawUserIdea}
      </p>

      {/* ── Meta row ────────────────────────────────────────────────── */}
      <div className="mt-5 flex flex-wrap gap-5 text-xs text-slate-500">
        <span>
          Создан: <span className="text-slate-400">{formatDateTime(run.createdAt)}</span>
        </span>
        {(run.status === 'COMPLETED' || run.status === 'ERROR') && (
          <span>
            Длительность:{' '}
            <span className="font-mono text-slate-400">{formatDuration(duration)}</span>
          </span>
        )}
        {totalTokens > 0 && (
          <span>
            Токены:{' '}
            <span className="font-mono text-slate-400">{formatTokens(totalTokens)}</span>
            <span className="ml-1 text-slate-600">
              ({formatTokens(run.totalPromptTokens)} prompt + {formatTokens(run.totalCompletionTokens)} completion)
            </span>
            {run.estimatedCostUsd != null && run.estimatedCostUsd > 0 && (
              <span className="ml-2 text-slate-600">
                ≈ <span className="font-mono text-slate-400">{formatCostUsd(run.estimatedCostUsd)}</span>
              </span>
            )}
          </span>
        )}
        {run.chatId && (
          <span>
            Telegram chat ID: <span className="font-mono text-slate-400">{run.chatId}</span>
          </span>
        )}
      </div>

      {/* ── Artifact path ───────────────────────────────────────────── */}
      {run.artifactPath && (
        <div className="mt-4 flex items-center gap-2 rounded-lg border border-emerald-500/20 bg-emerald-500/5 px-4 py-3">
          <Archive size={14} className="shrink-0 text-emerald-400" />
          <div>
            <p className="text-[10px] font-semibold uppercase tracking-widest text-emerald-600">
              Артефакт
            </p>
            <p className="mt-0.5 break-all font-mono text-xs text-emerald-400">
              {run.artifactPath}
            </p>
          </div>
        </div>
      )}
    </div>
  )
}
