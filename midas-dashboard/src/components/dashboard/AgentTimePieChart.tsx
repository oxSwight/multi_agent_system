'use client'

import { useMemo } from 'react'
import {
  PieChart,
  Pie,
  Cell,
  Tooltip,
  Legend,
  ResponsiveContainer,
} from 'recharts'
import type { AgentPerformanceStat } from '@/types'
import { agentDisplayName, formatDuration } from '@/lib/utils'

interface AgentTimePieChartProps {
  stats: AgentPerformanceStat[]
}

// Agent colors — deliberately distinct, non-rainbow (mostly cool tones)
const COLORS = ['#6366f1', '#22d3ee', '#818cf8', '#34d399', '#f472b6', '#fb923c']

// ── Custom tooltip ─────────────────────────────────────────────────────────────

function CustomTooltip({ active, payload }: any) {
  if (!active || !payload?.length) return null
  const d = payload[0].payload
  return (
    <div className="rounded-lg border border-[#1e1e3a] bg-[#0d0d1f] px-4 py-3 text-xs shadow-xl">
      <p className="mb-1.5 font-semibold text-slate-200">{d.name}</p>
      <div className="space-y-1 text-slate-400">
        <p>Ср. время: <span className="font-mono text-slate-200">{formatDuration(d.avgMs)}</span></p>
        <p>Макс. время: <span className="font-mono text-slate-200">{formatDuration(d.maxMs)}</span></p>
        <p>Запусков: <span className="font-mono text-slate-200">{d.invocations}</span></p>
      </div>
    </div>
  )
}

// ── Custom legend ──────────────────────────────────────────────────────────────

function CustomLegend({ payload }: any) {
  return (
    <ul className="mt-3 space-y-1.5">
      {payload?.map((entry: any, i: number) => (
        <li key={i} className="flex items-center gap-2 text-xs text-slate-400">
          <span
            className="inline-block h-2 w-2 shrink-0 rounded-sm"
            style={{ background: entry.color }}
          />
          <span className="truncate">{entry.value}</span>
        </li>
      ))}
    </ul>
  )
}

// ── Chart ─────────────────────────────────────────────────────────────────────

export default function AgentTimePieChart({ stats }: AgentTimePieChartProps) {
  // Memoize to avoid remapping the stats array on every parent render cycle.
  const data = useMemo(() =>
    stats.map((s, i) => ({
      name:        agentDisplayName(s.agentType),
      value:       s.avgExecutionTimeMs,
      avgMs:       s.avgExecutionTimeMs,
      maxMs:       s.maxExecutionTimeMs,
      invocations: s.totalInvocations,
      color:       COLORS[i % COLORS.length],
    })),
    [stats]
  )

  return (
    <div className="rounded-xl border border-[#1e1e3a] bg-[#111127] p-5">
      <p className="mb-1 text-xs font-semibold uppercase tracking-widest text-slate-500">
        Распределение времени
      </p>
      <p className="mb-5 text-sm text-slate-300">Среднее время по агентам</p>

      <ResponsiveContainer width="100%" height={220}>
        <PieChart>
          <Pie
            data={data}
            cx="50%"
            cy="45%"
            innerRadius={55}
            outerRadius={88}
            paddingAngle={3}
            dataKey="value"
            strokeWidth={0}
          >
            {data.map((entry, i) => (
              <Cell key={i} fill={entry.color} opacity={0.9} />
            ))}
          </Pie>
          <Tooltip content={<CustomTooltip />} />
          <Legend
            content={<CustomLegend />}
            layout="vertical"
            align="right"
            verticalAlign="middle"
          />
        </PieChart>
      </ResponsiveContainer>
    </div>
  )
}
