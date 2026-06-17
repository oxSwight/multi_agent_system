import { notFound } from 'next/navigation'
import { fetchRunDetail } from '@/services/api'
import RunHeader     from '@/components/run-details/RunHeader'
import AgentTimeline from '@/components/run-details/AgentTimeline'
import AgentLogCard  from '@/components/run-details/AgentLogCard'
import { agentTypeToStageIndex } from '@/lib/utils'

interface Props {
  params: { runId: string }
}

export const revalidate = 5

export default async function RunDetailPage({ params }: Props) {
  const detail = await fetchRunDetail(params.runId)
  if (!detail) notFound()

  const { run, agentLogs } = detail

  // Sort logs by agent pipeline order, then by creation time as tiebreaker.
  const sortedLogs = [...agentLogs].sort((a, b) => {
    const stageDiff = agentTypeToStageIndex(a.agentType) - agentTypeToStageIndex(b.agentType)
    if (stageDiff !== 0) return stageDiff
    return new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime()
  })

  return (
    <div className="page-enter min-h-full px-6 py-8 md:px-8 space-y-6">

      {/* ── Run summary header ─────────────────────────────────────────── */}
      <RunHeader run={run} />

      {/* ── Main two-column layout ─────────────────────────────────────── */}
      <div className="grid grid-cols-1 gap-6 xl:grid-cols-3">

        {/* Left: timeline (narrow) */}
        <div className="xl:col-span-1">
          <AgentTimeline logs={agentLogs} pipelineStatus={run.status} />
        </div>

        {/* Right: agent log cards (wide) */}
        <div className="xl:col-span-2 space-y-3">
          <div className="mb-4">
            <p className="text-xs font-semibold uppercase tracking-widest text-slate-500">
              Детализация агентов
            </p>
            <p className="mt-0.5 text-sm text-slate-400">
              {sortedLogs.length} лог{sortedLogs.length !== 1 ? 'а' : ''}
            </p>
          </div>

          {sortedLogs.length === 0 ? (
            <div className="rounded-xl border border-[#1e1e3a] bg-[#111127] px-6 py-10 text-center">
              <p className="text-sm text-slate-600">Логи пока не созданы.</p>
            </div>
          ) : (
            sortedLogs.map((log, idx) => (
              <AgentLogCard
                key={log.id}
                log={log}
                defaultOpen={idx === 0}
              />
            ))
          )}
        </div>

      </div>
    </div>
  )
}
