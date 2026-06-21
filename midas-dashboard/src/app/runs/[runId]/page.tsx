import { notFound } from 'next/navigation'
import { fetchRunDetail } from '@/services/api'
import RunHeader     from '@/components/run-details/RunHeader'
import AgentTimeline from '@/components/run-details/AgentTimeline'
import AgentLogCard  from '@/components/run-details/AgentLogCard'
import { agentTypeToStageIndex, agentDisplayName, formatTokens, formatCostUsd } from '@/lib/utils'

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

          {sortedLogs.some(l => l.promptTokens + l.completionTokens > 0) && (
            <div className="overflow-x-auto rounded-xl border border-[#1e1e3a] bg-[#111127]">
              <table className="w-full min-w-[520px] text-left text-xs">
                <thead>
                  <tr className="border-b border-[#1e1e3a] text-slate-500">
                    <th className="px-4 py-3 font-medium">Агент</th>
                    <th className="px-4 py-3 font-medium">Prompt</th>
                    <th className="px-4 py-3 font-medium">Completion</th>
                    <th className="px-4 py-3 font-medium">Итого</th>
                    {run.estimatedCostUsd != null && (
                      <th className="px-4 py-3 font-medium">≈ USD</th>
                    )}
                  </tr>
                </thead>
                <tbody>
                  {sortedLogs.filter(l => l.promptTokens + l.completionTokens > 0).map(log => (
                    <tr key={log.id ?? log.agentType} className="border-b border-[#1e1e3a]/60 last:border-0">
                      <td className="px-4 py-2.5 text-slate-300">{agentDisplayName(log.agentType)}</td>
                      <td className="px-4 py-2.5 font-mono text-slate-400">{formatTokens(log.promptTokens)}</td>
                      <td className="px-4 py-2.5 font-mono text-slate-400">{formatTokens(log.completionTokens)}</td>
                      <td className="px-4 py-2.5 font-mono text-slate-300">
                        {formatTokens(log.promptTokens + log.completionTokens)}
                      </td>
                      {run.estimatedCostUsd != null && (
                        <td className="px-4 py-2.5 font-mono text-slate-400">
                          {log.estimatedCostUsd != null ? formatCostUsd(log.estimatedCostUsd) : '—'}
                        </td>
                      )}
                    </tr>
                  ))}
                  <tr className="bg-[#0d0d1f]/50 font-medium">
                    <td className="px-4 py-2.5 text-slate-300">Итого по run</td>
                    <td className="px-4 py-2.5 font-mono text-slate-300">{formatTokens(run.totalPromptTokens)}</td>
                    <td className="px-4 py-2.5 font-mono text-slate-300">{formatTokens(run.totalCompletionTokens)}</td>
                    <td className="px-4 py-2.5 font-mono text-slate-200">
                      {formatTokens(run.totalPromptTokens + run.totalCompletionTokens)}
                    </td>
                    {run.estimatedCostUsd != null && (
                      <td className="px-4 py-2.5 font-mono text-slate-200">
                        {run.estimatedCostUsd > 0 ? formatCostUsd(run.estimatedCostUsd) : '—'}
                      </td>
                    )}
                  </tr>
                </tbody>
              </table>
            </div>
          )}

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
