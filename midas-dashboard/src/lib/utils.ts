import { clsx, type ClassValue } from 'clsx'
import { twMerge } from 'tailwind-merge'
import type { PipelineStatus } from '@/types'

/** Tailwind class merger — handles conditional classes and prevents conflicts. */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

/** Format milliseconds into a human-readable duration: 3 450 ms → "3.4s". */
export function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`
  const m = Math.floor(ms / 60_000)
  const s = Math.floor((ms % 60_000) / 1000)
  return `${m}m ${s}s`
}

/** Format a large token count with locale separators: 28450 → "28 450". */
export function formatTokens(n: number): string {
  return n.toLocaleString('ru-RU')
}

/** Shorten a UUID to the first 8 chars for display: "a1b2c3d4-..." → "a1b2c3d4" */
export function shortId(id: string): string {
  return id.substring(0, 8)
}

/** Truncate a string to maxLen chars and append ellipsis. */
export function truncate(str: string, maxLen = 80): string {
  if (str.length <= maxLen) return str
  return str.slice(0, maxLen).trimEnd() + '…'
}

/** Format an ISO-8601 string to a localized datetime. */
export function formatDateTime(iso: string): string {
  return new Date(iso).toLocaleString('ru-RU', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

/** Format an ISO-8601 date as a relative string ("5 min ago"). */
export function timeAgo(iso: string): string {
  const diff = (Date.now() - new Date(iso).getTime()) / 1000
  if (diff < 60)   return `${Math.floor(diff)}с назад`
  if (diff < 3600) return `${Math.floor(diff / 60)}мин назад`
  if (diff < 86400) return `${Math.floor(diff / 3600)}ч назад`
  return `${Math.floor(diff / 86400)}д назад`
}

/** Compute pipeline wall-clock duration: updatedAt - createdAt in ms. */
export function pipelineDuration(run: { createdAt: string; updatedAt: string }): number {
  return new Date(run.updatedAt).getTime() - new Date(run.createdAt).getTime()
}

/** Map a PipelineStatus to a human-readable display label. */
export function statusLabel(status: PipelineStatus): string {
  const map: Record<PipelineStatus, string> = {
    STARTED:                'Запуск',
    SYSTEM_ANALYSIS:        'Анализ системы',
    ARCHITECTURE_DESIGN:    'Архитектура',
    INTEGRATION_STRATEGY:   'Интеграции',
    CODE_GENERATION:        'Генерация кода',
    TEST_GENERATION:        'Тесты',
    SECOPS_AUDIT:           'SecOps аудит',
    WAITING_FOR_USER_INPUT: 'Ожидание ввода',
    COMPLETED:              'Завершен',
    ERROR:                  'Ошибка',
  }
  return map[status] ?? status
}

/** Returns whether a status represents an actively running stage. */
export function isRunningStatus(status: PipelineStatus): boolean {
  return [
    'STARTED', 'SYSTEM_ANALYSIS', 'ARCHITECTURE_DESIGN', 'INTEGRATION_STRATEGY',
    'CODE_GENERATION', 'TEST_GENERATION', 'SECOPS_AUDIT',
  ].includes(status)
}

/** Map an agent type string to its pipeline stage position (1-based). */
export function agentTypeToStageIndex(agentType: string): number {
  const map: Record<string, number> = {
    SystemAnalystAgent:          1,
    SoftwareArchitectAgent:      2,
    IntegrationEngineerAgent:    3,
    ImplementationEngineerAgent: 4,
    QaAutomationAgent:           5,
    SecOpsAgent:                 6,
  }
  return map[agentType] ?? 0
}

/** Humanize an agent class name: "ImplementationEngineerAgent" → "Implementation Engineer" */
export function agentDisplayName(agentType: string): string {
  return agentType
    .replace('Agent', '')
    .replace(/([A-Z])/g, ' $1')
    .trim()
}
