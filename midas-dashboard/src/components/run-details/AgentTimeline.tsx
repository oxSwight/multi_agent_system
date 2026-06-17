import {
  Search,
  Layers,
  Link2,
  Code2,
  TestTube2,
  Shield,
  CheckCircle2,
  XCircle,
  Circle,
} from 'lucide-react'
import type { MidasAgentLog, PipelineStatus } from '@/types'
import { formatDuration, agentDisplayName } from '@/lib/utils'

interface AgentTimelineProps {
  logs: MidasAgentLog[]
  pipelineStatus: PipelineStatus
}

// ── Stage definitions ─────────────────────────────────────────────────────────

const STAGES = [
  { agentType: 'SystemAnalystAgent',       label: 'System Analyst',    Icon: Search,    pipelineState: 'SYSTEM_ANALYSIS'     },
  { agentType: 'SoftwareArchitectAgent',   label: 'Software Architect', Icon: Layers,    pipelineState: 'ARCHITECTURE_DESIGN' },
  { agentType: 'IntegrationEngineerAgent', label: 'Integration Engr.',  Icon: Link2,     pipelineState: 'INTEGRATION_STRATEGY'},
  { agentType: 'ImplementationEngineerAgent', label: 'Implementation Engr.', Icon: Code2,  pipelineState: 'CODE_GENERATION'     },
  { agentType: 'QaAutomationAgent',        label: 'QA Automation',      Icon: TestTube2, pipelineState: 'TEST_GENERATION'     },
  { agentType: 'SecOpsAgent',              label: 'SecOps Engineer',    Icon: Shield,    pipelineState: 'SECOPS_AUDIT'        },
] as const

// ── Spinner SVG (no animate-spin class to avoid hydration mismatch) ────────────

function Spinner() {
  return (
    <svg
      className="spin-slow"
      width="18"
      height="18"
      viewBox="0 0 18 18"
      fill="none"
    >
      <circle cx="9" cy="9" r="7" stroke="#4f46e5" strokeWidth="2" strokeOpacity="0.3" />
      <path
        d="M9 2a7 7 0 0 1 7 7"
        stroke="#818cf8"
        strokeWidth="2"
        strokeLinecap="round"
      />
    </svg>
  )
}

// ── Timeline ──────────────────────────────────────────────────────────────────

export default function AgentTimeline({ logs, pipelineStatus }: AgentTimelineProps) {
  // Build a quick lookup by agent type
  const logMap = new Map(logs.map(l => [l.agentType, l]))

  const runningStates = [
    'SYSTEM_ANALYSIS', 'ARCHITECTURE_DESIGN', 'INTEGRATION_STRATEGY',
    'CODE_GENERATION', 'TEST_GENERATION', 'SECOPS_AUDIT', 'STARTED',
  ]

  return (
    <div className="rounded-xl border border-[#1e1e3a] bg-[#111127] p-5">
      <p className="mb-1 text-xs font-semibold uppercase tracking-widest text-slate-500">
        Timeline
      </p>
      <p className="mb-6 text-sm text-slate-300">Этапы выполнения пайплайна</p>

      <ol className="relative ml-2">
        {STAGES.map((stage, idx) => {
          const log       = logMap.get(stage.agentType)
          const completed = !!log && !log.isError
          const failed    = !!log && log.isError
          const isCurrent = !log && runningStates.includes(pipelineStatus) &&
                            pipelineStatus === stage.pipelineState
          const isLast    = idx === STAGES.length - 1

          const { Icon } = stage

          return (
            <li key={stage.agentType} className="flex gap-4">
              {/* ── Connector column ──────────────────────────────── */}
              <div className="flex flex-col items-center">
                {/* Icon circle */}
                <div
                  className={`
                    relative z-10 flex h-9 w-9 shrink-0 items-center justify-center rounded-full
                    ${completed ? 'bg-emerald-500/15 ring-1 ring-emerald-500/40' :
                      failed    ? 'bg-red-500/15 ring-1 ring-red-500/40' :
                      isCurrent ? 'bg-indigo-500/15 ring-1 ring-indigo-500/40' :
                                  'bg-[#1e1e3a] ring-1 ring-[#2d2d5a]'}
                  `}
                >
                  {isCurrent ? (
                    <Spinner />
                  ) : completed ? (
                    <CheckCircle2 size={17} className="text-emerald-400" />
                  ) : failed ? (
                    <XCircle size={17} className="text-red-400" />
                  ) : (
                    <Icon size={15} className="text-slate-600" />
                  )}
                </div>

                {/* Vertical connector line */}
                {!isLast && (
                  <div
                    className={`w-px flex-1 my-1 ${completed ? 'bg-emerald-500/20' : 'bg-[#1e1e3a]'}`}
                    style={{ minHeight: '24px' }}
                  />
                )}
              </div>

              {/* ── Stage content ─────────────────────────────────── */}
              <div className={`pb-6 ${isLast ? 'pb-0' : ''}`}>
                <p
                  className={`mt-1.5 text-sm font-medium ${
                    completed ? 'text-slate-200' :
                    failed    ? 'text-red-400'   :
                    isCurrent ? 'text-indigo-300' :
                                'text-slate-600'
                  }`}
                >
                  {stage.label}
                </p>

                {/* Badges */}
                {log && (
                  <div className="mt-1.5 flex flex-wrap gap-2">
                    <span className="inline-flex items-center gap-1 rounded-md border border-[#1e1e3a] bg-[#0d0d1f] px-2 py-0.5 font-mono text-[10px] text-slate-400">
                      {formatDuration(log.executionTimeMs)}
                    </span>
                    {(log.promptTokens + log.completionTokens) > 0 && (
                      <span className="inline-flex items-center gap-1 rounded-md border border-[#1e1e3a] bg-[#0d0d1f] px-2 py-0.5 font-mono text-[10px] text-slate-400">
                        {(log.promptTokens + log.completionTokens).toLocaleString('ru-RU')} tk
                      </span>
                    )}
                    {log.isError && (
                      <span className="rounded-md border border-red-500/20 bg-red-500/10 px-2 py-0.5 text-[10px] font-medium text-red-400">
                        Error
                      </span>
                    )}
                  </div>
                )}

                {isCurrent && (
                  <p className="mt-1 text-xs text-indigo-400/70">Выполняется...</p>
                )}

                {!log && !isCurrent && (
                  <p className="mt-1 text-xs text-slate-700">Ожидание</p>
                )}
              </div>
            </li>
          )
        })}
      </ol>
    </div>
  )
}
