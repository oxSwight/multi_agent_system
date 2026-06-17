import { Activity, Zap, Clock, Trophy } from 'lucide-react'
import type { DashboardOverview } from '@/types'
import { formatTokens, formatDuration, agentDisplayName } from '@/lib/utils'

interface MetricCardsProps {
  overview: DashboardOverview
}

// ── Single metric card ────────────────────────────────────────────────────────

interface CardProps {
  label: string
  value: React.ReactNode
  sub?: string
  Icon: React.ElementType
  accent: string   // Tailwind color class for the icon ring
}

function MetricCard({ label, value, sub, Icon, accent }: CardProps) {
  return (
    <div className="flex flex-col justify-between rounded-xl border border-[#1e1e3a] bg-[#111127] p-5">
      <div className="flex items-start justify-between">
        <p className="text-xs font-medium uppercase tracking-widest text-slate-500">{label}</p>
        <div className={`flex h-8 w-8 shrink-0 items-center justify-center rounded-lg ${accent}`}>
          <Icon size={15} />
        </div>
      </div>

      <div className="mt-4">
        <p className="text-2xl font-bold text-slate-100 tabular-nums">{value}</p>
        {sub && (
          <p className="mt-1 text-xs text-slate-500">{sub}</p>
        )}
      </div>
    </div>
  )
}

// ── Four-card grid ────────────────────────────────────────────────────────────

export default function MetricCards({ overview }: MetricCardsProps) {
  const completionRate =
    overview.totalRuns > 0
      ? Math.round((overview.completedRuns / overview.totalRuns) * 100)
      : 0

  return (
    <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">

      <MetricCard
        label="Всего запусков"
        value={overview.totalRuns}
        sub={`${overview.completedRuns} завершено · ${completionRate}% успех`}
        Icon={Activity}
        accent="bg-indigo-600/15 text-indigo-400 ring-1 ring-indigo-500/30"
      />

      <MetricCard
        label="Расход токенов"
        value={formatTokens(overview.totalPromptTokens + overview.totalCompletionTokens)}
        sub={`${formatTokens(overview.totalPromptTokens)} prompt · ${formatTokens(overview.totalCompletionTokens)} completion`}
        Icon={Zap}
        accent="bg-cyan-600/15 text-cyan-400 ring-1 ring-cyan-500/30"
      />

      <MetricCard
        label="Среднее время"
        value={formatDuration(overview.avgPipelineTimeMs)}
        sub="на полный pipeline run"
        Icon={Clock}
        accent="bg-violet-600/15 text-violet-400 ring-1 ring-violet-500/30"
      />

      <MetricCard
        label="Дорогой агент"
        value={
          overview.mostExpensiveAgent
            ? agentDisplayName(overview.mostExpensiveAgent.agentType)
            : '—'
        }
        sub={
          overview.mostExpensiveAgent
            ? `ср. ${formatDuration(overview.mostExpensiveAgent.avgExecutionTimeMs)}`
            : undefined
        }
        Icon={Trophy}
        accent="bg-amber-600/15 text-amber-400 ring-1 ring-amber-500/30"
      />

    </div>
  )
}
