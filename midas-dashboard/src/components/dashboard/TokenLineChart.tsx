'use client'

import {
  LineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Legend,
  ResponsiveContainer,
} from 'recharts'
import type { RunTokenUsage } from '@/types'

interface TokenLineChartProps {
  data: RunTokenUsage[]
}

// ── Custom tooltip ────────────────────────────────────────────────────────────

function CustomTooltip({ active, payload, label }: any) {
  if (!active || !payload?.length) return null
  return (
    <div className="rounded-lg border border-[#1e1e3a] bg-[#0d0d1f] px-4 py-3 text-xs shadow-xl">
      <p className="mb-2 font-semibold text-slate-300">Run #{label}</p>
      {payload.map((entry: any) => (
        <div key={entry.dataKey} className="flex items-center gap-2">
          <span
            className="inline-block h-2 w-2 rounded-full"
            style={{ background: entry.color }}
          />
          <span className="text-slate-400">{entry.name}:</span>
          <span className="font-mono font-medium text-slate-200">
            {(entry.value as number).toLocaleString('ru-RU')}
          </span>
        </div>
      ))}
    </div>
  )
}

// ── Chart ─────────────────────────────────────────────────────────────────────

export default function TokenLineChart({ data }: TokenLineChartProps) {
  return (
    <div className="rounded-xl border border-[#1e1e3a] bg-[#111127] p-5">
      <p className="mb-1 text-xs font-semibold uppercase tracking-widest text-slate-500">
        Расход токенов
      </p>
      <p className="mb-5 text-sm text-slate-300">По последним {data.length} запускам</p>

      <ResponsiveContainer width="100%" height={220}>
        <LineChart data={data} margin={{ top: 4, right: 4, bottom: 0, left: -8 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="#1e1e3a" vertical={false} />
          <XAxis
            dataKey="runIndex"
            tickFormatter={(v) => `#${v}`}
            tick={{ fill: '#64748b', fontSize: 11 }}
            axisLine={false}
            tickLine={false}
          />
          <YAxis
            tick={{ fill: '#64748b', fontSize: 11 }}
            axisLine={false}
            tickLine={false}
            tickFormatter={(v) => `${(v / 1000).toFixed(0)}k`}
          />
          <Tooltip content={<CustomTooltip />} />
          <Legend
            wrapperStyle={{ fontSize: '11px', color: '#94a3b8', paddingTop: '12px' }}
            formatter={(value) => (
              <span style={{ color: '#94a3b8' }}>{value}</span>
            )}
          />
          <Line
            type="monotone"
            dataKey="promptTokens"
            name="Prompt tokens"
            stroke="#6366f1"
            strokeWidth={2}
            dot={{ fill: '#6366f1', r: 3, strokeWidth: 0 }}
            activeDot={{ r: 5, fill: '#818cf8' }}
          />
          <Line
            type="monotone"
            dataKey="completionTokens"
            name="Completion tokens"
            stroke="#22d3ee"
            strokeWidth={2}
            dot={{ fill: '#22d3ee', r: 3, strokeWidth: 0 }}
            activeDot={{ r: 5, fill: '#67e8f9' }}
          />
        </LineChart>
      </ResponsiveContainer>
    </div>
  )
}
