'use client'

import Link from 'next/link'
import { useState, useMemo, useCallback } from 'react'
import { ArrowUpDown, ChevronRight } from 'lucide-react'
import type { MidasRun } from '@/types'
import {
  shortId, truncate, formatDateTime, formatTokens, formatDuration,
  pipelineDuration,
} from '@/lib/utils'
import StatusBadge from './StatusBadge'

interface RunsTableProps {
  runs: MidasRun[]
}

type SortKey = 'createdAt' | 'status' | 'tokens'
type SortDir = 'asc' | 'desc'

export default function RunsTable({ runs }: RunsTableProps) {
  const [sortKey, setSortKey]   = useState<SortKey>('createdAt')
  const [sortDir, setSortDir]   = useState<SortDir>('desc')

  const toggleSort = useCallback((key: SortKey) => {
    if (key === sortKey) setSortDir(d => d === 'asc' ? 'desc' : 'asc')
    else { setSortKey(key); setSortDir('desc') }
  }, [sortKey])

  // Memoize the sorted array so it is only recomputed when runs, sortKey, or
  // sortDir actually change — not on every unrelated parent re-render.
  const sorted = useMemo(() =>
    [...runs].sort((a, b) => {
      let diff = 0
      if (sortKey === 'createdAt') diff = new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime()
      if (sortKey === 'tokens')    diff = (a.totalPromptTokens + a.totalCompletionTokens) - (b.totalPromptTokens + b.totalCompletionTokens)
      if (sortKey === 'status')    diff = a.status.localeCompare(b.status)
      return sortDir === 'asc' ? diff : -diff
    }),
    [runs, sortKey, sortDir]
  )

  const SortBtn = ({ col }: { col: SortKey }) => (
    <button
      onClick={() => toggleSort(col)}
      aria-label={`Sort by ${col}`}
      className={`ml-1 inline-flex items-center transition-colors ${
        col === sortKey ? 'text-indigo-400' : 'text-slate-600 hover:text-slate-300'
      }`}
    >
      <ArrowUpDown size={12} />
    </button>
  )

  return (
    <div className="rounded-xl border border-[#1e1e3a] bg-[#111127] overflow-hidden">

      {/* ── Header ──────────────────────────────────────────────────── */}
      <div className="flex items-center justify-between px-5 py-4 border-b border-[#1e1e3a]">
        <div>
          <p className="text-xs font-semibold uppercase tracking-widest text-slate-500">История запусков</p>
          <p className="mt-0.5 text-sm text-slate-300">{runs.length} pipeline runs</p>
        </div>
      </div>

      {/* ── Table ───────────────────────────────────────────────────── */}
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-[#1e1e3a]">
              <th className="px-5 py-3 text-left text-[10px] font-semibold uppercase tracking-widest text-slate-600">
                ID
              </th>
              <th className="px-4 py-3 text-left text-[10px] font-semibold uppercase tracking-widest text-slate-600">
                Статус <SortBtn col="status" />
              </th>
              <th className="px-4 py-3 text-left text-[10px] font-semibold uppercase tracking-widest text-slate-600">
                Техническое задание
              </th>
              <th className="px-4 py-3 text-right text-[10px] font-semibold uppercase tracking-widest text-slate-600">
                Время
              </th>
              <th className="px-4 py-3 text-right text-[10px] font-semibold uppercase tracking-widest text-slate-600">
                Токены <SortBtn col="tokens" />
              </th>
              <th className="px-4 py-3 text-right text-[10px] font-semibold uppercase tracking-widest text-slate-600">
                Создан <SortBtn col="createdAt" />
              </th>
              <th className="w-8 px-2 py-3" />
            </tr>
          </thead>

          <tbody>
            {sorted.map((run, idx) => {
              const totalTokens = run.totalPromptTokens + run.totalCompletionTokens
              const duration    = pipelineDuration(run)
              const isLast      = idx === sorted.length - 1

              return (
                <tr
                  key={run.id}
                  className={`group transition-colors hover:bg-white/[0.025] ${!isLast ? 'border-b border-[#1e1e3a]' : ''}`}
                >
                  {/* ID */}
                  <td className="px-5 py-3.5">
                    <span className="font-mono text-xs text-slate-500">
                      {shortId(run.id)}
                    </span>
                  </td>

                  {/* Status */}
                  <td className="px-4 py-3.5">
                    <StatusBadge status={run.status} size="sm" />
                  </td>

                  {/* Idea */}
                  <td className="max-w-xs px-4 py-3.5">
                    <p className="truncate text-slate-300" title={run.rawUserIdea}>
                      {truncate(run.rawUserIdea, 70)}
                    </p>
                  </td>

                  {/* Duration */}
                  <td className="px-4 py-3.5 text-right">
                    <span className="font-mono text-xs text-slate-400">
                      {run.status === 'COMPLETED' || run.status === 'ERROR'
                        ? formatDuration(duration)
                        : '—'}
                    </span>
                  </td>

                  {/* Tokens */}
                  <td className="px-4 py-3.5 text-right">
                    <span className="font-mono text-xs text-slate-400">
                      {totalTokens > 0 ? formatTokens(totalTokens) : '—'}
                    </span>
                  </td>

                  {/* Created */}
                  <td className="px-4 py-3.5 text-right">
                    <span className="text-xs text-slate-500">
                      {formatDateTime(run.createdAt)}
                    </span>
                  </td>

                  {/* Link */}
                  <td className="px-2 py-3.5">
                    <Link
                      href={`/runs/${run.id}`}
                      className="flex items-center justify-center text-slate-700 transition-colors group-hover:text-indigo-400"
                    >
                      <ChevronRight size={15} />
                    </Link>
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    </div>
  )
}
