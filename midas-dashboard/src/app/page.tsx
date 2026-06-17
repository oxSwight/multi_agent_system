import { RefreshCw } from 'lucide-react'
import {
  fetchOverview,
  fetchRuns,
  fetchPerformanceStats,
  fetchTokenUsage,
} from '@/services/api'
import MetricCards       from '@/components/dashboard/MetricCards'
import TokenLineChart    from '@/components/dashboard/TokenLineChart'
import AgentTimePieChart from '@/components/dashboard/AgentTimePieChart'
import RunsTable         from '@/components/dashboard/RunsTable'

// Next.js Server Component — data fetching happens on the server.
// Revalidate every 10 seconds so the page stays fresh without manual refresh.
export const revalidate = 10

export default async function DashboardPage() {
  const [overview, page, perfStats, tokenUsage] = await Promise.all([
    fetchOverview(),
    fetchRuns(),
    fetchPerformanceStats(),
    fetchTokenUsage(),
  ])

  return (
    <div className="page-enter min-h-full px-6 py-8 md:px-8 space-y-8">

      {/* ── Page header ───────────────────────────────────────────────── */}
      <div className="flex items-start justify-between">
        <div>
          <h1 className="text-xl font-bold text-slate-100">Pipeline Overview</h1>
          <p className="mt-1 text-sm text-slate-500">
            Общий мониторинг агентных запусков MIDAS D3
          </p>
        </div>

        {/* Live indicator */}
        <div className="flex items-center gap-2 rounded-lg border border-[#1e1e3a] bg-[#111127] px-3 py-2">
          <span className="relative flex h-2 w-2">
            <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-emerald-400 opacity-75" />
            <span className="relative inline-flex h-2 w-2 rounded-full bg-emerald-500" />
          </span>
          <span className="text-xs text-slate-400">
            {overview.runningRuns > 0
              ? `${overview.runningRuns} активных запуска`
              : 'Нет активных запусков'}
          </span>
          <RefreshCw size={11} className="text-slate-600" />
        </div>
      </div>

      {/* ── Metric cards ───────────────────────────────────────────────── */}
      <MetricCards overview={overview} />

      {/* ── Charts row ─────────────────────────────────────────────────── */}
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-5">
        {/* Token line chart takes up more width */}
        <div className="lg:col-span-3">
          <TokenLineChart data={tokenUsage} />
        </div>
        {/* Pie chart */}
        <div className="lg:col-span-2">
          <AgentTimePieChart stats={perfStats} />
        </div>
      </div>

      {/* ── Runs table ─────────────────────────────────────────────────── */}
      <RunsTable runs={page.content} />

    </div>
  )
}
