// ── Pipeline run status values (mirrors MidasState Java enum) ────────────────

export type PipelineStatus =
  | 'STARTED'
  | 'SYSTEM_ANALYSIS'
  | 'ARCHITECTURE_DESIGN'
  | 'INTEGRATION_STRATEGY'
  | 'CODE_GENERATION'
  | 'TEST_GENERATION'
  | 'SECOPS_AUDIT'
  | 'WAITING_FOR_USER_INPUT'
  | 'COMPLETED'
  | 'ERROR'

// ── Core domain types ─────────────────────────────────────────────────────────

export interface MidasRun {
  id: string
  chatId?: number | null
  rawUserIdea: string
  status: PipelineStatus
  artifactPath?: string | null
  totalPromptTokens: number
  totalCompletionTokens: number
  estimatedCostUsd?: number | null
  needsRefactoring?: boolean
  createdAt: string   // ISO-8601
  updatedAt?: string   // ISO-8601
}

export interface MidasAgentLog {
  id?: string
  runId?: string
  agentType: string   // e.g. "SystemAnalystAgent"
  rawOutput: string | null
  promptTokens: number
  completionTokens: number
  modelId?: string | null
  finishReason?: string | null
  estimatedCostUsd?: number | null
  executionTimeMs: number
  isError: boolean
  createdAt?: string   // ISO-8601
}

// ── Dashboard aggregate types ─────────────────────────────────────────────────

export interface DashboardOverview {
  totalRuns: number
  completedRuns: number
  errorRuns: number
  runningRuns: number
  totalPromptTokens: number
  totalCompletionTokens: number
  avgPipelineTimeMs: number
  mostExpensiveAgent: {
    agentType: string
    avgExecutionTimeMs: number
  } | null
}

/** Stats per agent type for the pie chart */
export interface AgentPerformanceStat {
  agentType: string
  avgExecutionTimeMs: number
  maxExecutionTimeMs: number
  totalInvocations: number
}

/** Single run with its full agent log list, returned from the detail endpoint */
export interface RunDetail {
  run: MidasRun
  agentLogs: MidasAgentLog[]
}

/** Paginated list of runs */
export interface RunsPage {
  content: MidasRun[]
  totalElements: number
  totalPages: number
  currentPage: number
}

/** Token usage data for a single run — used in the line chart */
export interface RunTokenUsage {
  runIndex: number          // 1-based label like "Run 1"
  runId: string
  promptTokens: number
  completionTokens: number
  createdAt: string
}

/**
 * A single entry from GET /api/v1/dashboard/evolution-history.
 * Mirrors the backend EvolutionLogItemDto record.
 */
export interface EvolutionLogItem {
  id: string
  runId: string
  refactoringReport: string   // Full Markdown report produced by the EvolutionAgent
  createdAt: string           // ISO-8601 UTC timestamp
}
