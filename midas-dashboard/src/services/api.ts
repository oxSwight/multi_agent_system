/**
 * MIDAS Dashboard API Service
 *
 * Auth flow:
 *  1. The /auth Magic Link page stores the JWT in a cookie (`midas_token`) after
 *     the Telegram bot sends the user a link like /auth?token=<jwt>.
 *  2. Every server-side fetch reads the cookie via `cookies()` from next/headers
 *     and injects it as `Authorization: Bearer <token>`.
 *  3. When the backend returns 401 or 403 the user is redirected to /unauthorized.
 *  4. When the backend is completely unreachable the function falls back to rich
 *     mock data so the dashboard remains functional offline.
 */

import { cookies }  from 'next/headers'
import { redirect } from 'next/navigation'

import type {
  DashboardOverview,
  RunsPage,
  RunDetail,
  AgentPerformanceStat,
  RunTokenUsage,
  EvolutionLogItem,
} from '@/types'
import { TOKEN_COOKIE_NAME } from '@/lib/auth'

const API_BASE = `${process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8080'}/api/v1/dashboard`

/** Returns the JWT stored in the request cookie, or undefined. */
function getToken(): string | undefined {
  try {
    return cookies().get(TOKEN_COOKIE_NAME)?.value
  } catch {
    // cookies() throws outside a server request context (e.g., static build).
    return undefined
  }
}

// ── Next.js redirect detection ────────────────────────────────────────────────

function isNextRedirect(e: unknown): boolean {
  return (
    typeof e === 'object' &&
    e !== null &&
    'digest' in e &&
    typeof (e as { digest: unknown }).digest === 'string' &&
    (e as { digest: string }).digest.startsWith('NEXT_REDIRECT')
  )
}

// ── Generic fetch wrapper ─────────────────────────────────────────────────────

async function apiFetch<T>(path: string, mock: T): Promise<T> {
  const token = getToken()
  const headers: HeadersInit = token ? { Authorization: `Bearer ${token}` } : {}

  try {
    const res = await fetch(`${API_BASE}${path}`, { cache: 'no-store', headers })

    if (res.status === 401 || res.status === 403) {
      // Token expired or missing — send the user to the graceful error page.
      redirect('/unauthorized')
    }

    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    return (await res.json()) as T
  } catch (e: unknown) {
    if (isNextRedirect(e)) throw e          // Let Next.js handle the redirect
    return mock                             // Backend unreachable → use mock data
  }
}

// ═════════════════════════════════════════════════════════════════════════════
//  MOCK DATA
// ═════════════════════════════════════════════════════════════════════════════

const now = new Date()
const hoursAgo   = (h: number) => new Date(now.getTime() - h * 3_600_000).toISOString()
const minutesAgo = (m: number) => new Date(now.getTime() - m * 60_000).toISOString()

// ── Mock agent outputs ────────────────────────────────────────────────────────

const SYSTEM_ANALYST_OUTPUT = `{
  "business_goal": "Разработать браузерное расширение Manifest V3 «Автофиллер-резюме / Resume Autofiller», которое автоматически заполняет формы откликов на вакансии данными из профиля пользователя — полностью на стороне клиента, без сервера и базы данных.",
  "core_features": [
    "Распознавание полей форм отклика (ФИО, email, телефон, опыт, навыки) на страницах вакансий",
    "Заполнение форм в один клик из popup или по контекстному меню",
    "Локальное хранение профилей резюме в chrome.storage.local (никаких внешних серверов)",
    "Несколько профилей резюме с быстрым переключением",
    "Импорт / экспорт профиля в JSON для бэкапа между устройствами"
  ],
  "edge_cases": [
    { "case": "Поле формы не распознано эвристикой",      "solution": "Fallback на ручной маппинг + подсветка незаполненных полей" },
    { "case": "Профиль резюме пуст или повреждён",        "solution": "Валидация схемы при чтении из storage, мягкий сброс к дефолту" },
    { "case": "chrome.storage.local недоступен",          "solution": "Деградация в in-memory режим на время сессии + уведомление" },
    { "case": "Динамические формы (SPA, react-hook-form)","solution": "MutationObserver переотрабатывает заполнение при ре-рендере" },
    { "case": "Конфликт со скриптами страницы (CSP)",     "solution": "Изоляция логики в content_script, без inline-eval" }
  ],
  "performance_constraints": [
    "Холодный старт popup < 80ms",
    "Заполнение формы < 30ms на 20 полей",
    "Нулевые сетевые запросы — весь функционал офлайн",
    "Размер упакованного расширения < 500 KB"
  ]
}`

const ARCHITECT_OUTPUT = `{
  "architecture_style": "CLIENT_ONLY",
  "runtime_environment": {
    "execution_model": "CLIENT_SIDE",
    "deployment_target": "BROWSER_EXTENSION",
    "manifest_version": "Manifest V3",
    "persistence": "chrome.storage.local",
    "server_infrastructure": "NONE"
  },
  "tech_stack": {
    "language":   "JavaScript / TypeScript",
    "platform":   "Manifest V3 (Chrome / Edge / Firefox MV3)",
    "storage":    "chrome.storage.local",
    "bundler":    "Vite + @crxjs/vite-plugin",
    "ui":         "Vanilla TS + minimal popup HTML/CSS"
  },
  "components": [
    "content_script.js  — внедряется в страницу вакансии, распознаёт и заполняет поля формы",
    "popup.html         — UI выбора профиля резюме и запуска автозаполнения",
    "background.js       — service worker MV3: контекстное меню, события, маршрутизация сообщений"
  ],
  "tables": [],
  "api_endpoints": []
}`

const INTEGRATION_OUTPUT = `{
  "integrations": [
    {
      "name": "chrome.storage.local",
      "type": "CLIENT_STORAGE",
      "connection": "chrome.storage API (без сети)",
      "config": { "scope": "local", "quota_bytes": 5242880, "sync": false }
    },
    {
      "name": "chrome.runtime messaging",
      "type": "EXTENSION_IPC",
      "flow": "popup ⇄ background (service worker) ⇄ content_script через runtime.sendMessage",
      "channels": ["FILL_FORM", "SAVE_PROFILE", "LIST_PROFILES"]
    },
    {
      "name": "chrome.contextMenus",
      "type": "BROWSER_UI",
      "trigger": "Правый клик на странице вакансии → «Заполнить резюме»",
      "handler": "background.js регистрирует пункт меню при onInstalled"
    }
  ]
}`

const DEVELOPER_OUTPUT = `\`\`\`typescript
// content_script.ts — распознавание и автозаполнение формы резюме (фрагмент)

import type { ResumeProfile } from './types'

const FIELD_HEURISTICS: Record<keyof ResumeProfile, RegExp> = {
  fullName: /(full[\\s_-]?name|fio|имя|фио)/i,
  email:    /(e-?mail|почта)/i,
  phone:    /(phone|tel|телефон)/i,
  summary:  /(about|summary|о себе|опыт)/i,
  skills:   /(skills|stack|навыки)/i,
}

/** Сопоставляет input/textarea со страницы с полем профиля резюме. */
function matchField(el: HTMLInputElement | HTMLTextAreaElement): keyof ResumeProfile | null {
  const haystack = [el.name, el.id, el.placeholder, el.labels?.[0]?.innerText]
    .filter(Boolean)
    .join(' ')
  for (const key of Object.keys(FIELD_HEURISTICS) as (keyof ResumeProfile)[]) {
    if (FIELD_HEURISTICS[key].test(haystack)) return key
  }
  return null
}

/** Заполняет распознанные поля и диспатчит input-событие для SPA-фреймворков. */
export function fillForm(profile: ResumeProfile): number {
  let filled = 0
  document.querySelectorAll('input, textarea').forEach((node) => {
    const el = node as HTMLInputElement | HTMLTextAreaElement
    const key = matchField(el)
    if (!key || !profile[key]) return
    el.value = String(profile[key])
    el.dispatchEvent(new Event('input', { bubbles: true })) // react-hook-form / Vue
    filled++
  })
  return filled
}

// Заполнение по сообщению из popup/background (chrome.runtime messaging)
chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  if (msg.type !== 'FILL_FORM') return
  chrome.storage.local.get(['activeProfile'], ({ activeProfile }) => {
    sendResponse({ filled: fillForm(activeProfile as ResumeProfile) })
  })
  return true // async response
})
\`\`\``

const QA_OUTPUT = `\`\`\`typescript
// content_script.test.ts — unit tests (Vitest + jsdom, фрагмент)

import { describe, it, expect, beforeEach } from 'vitest'
import { fillForm } from '../src/content_script'
import type { ResumeProfile } from '../src/types'

const profile: ResumeProfile = {
  fullName: 'Иван Петров',
  email:    'ivan.petrov@example.com',
  phone:    '+7 999 123-45-67',
  summary:  'Senior Frontend Engineer, 6 лет опыта',
  skills:   'TypeScript, React, Manifest V3',
}

describe('fillForm', () => {
  beforeEach(() => {
    document.body.innerHTML = \`
      <input name="applicant_full_name" />
      <input id="email" />
      <textarea placeholder="Расскажите о себе"></textarea>
      <input name="unrelated_captcha" />
    \`
  })

  it('заполняет распознанные поля и пропускает неизвестные', () => {
    const filled = fillForm(profile)
    expect(filled).toBe(3)
    expect((document.querySelector('[name="applicant_full_name"]') as HTMLInputElement).value)
      .toBe('Иван Петров')
    expect((document.querySelector('[name="unrelated_captcha"]') as HTMLInputElement).value)
      .toBe('')
  })

  it('диспатчит input-событие для реактивных форм', () => {
    let fired = false
    document.querySelector('#email')!.addEventListener('input', () => { fired = true })
    fillForm(profile)
    expect(fired).toBe(true)
  })
})
\`\`\``

const SECOPS_OUTPUT = `{
  "security_audit_result": "PASS",
  "findings": [],
  "recommendations": [
    {
      "severity": "INFO",
      "category": "MANIFEST_PERMISSIONS",
      "message": "Использовать optional_host_permissions вместо широкого <all_urls> — запрашивать доступ к домену вакансии по требованию.",
      "mitigated": false
    },
    {
      "severity": "INFO",
      "category": "DATA_PRIVACY",
      "message": "Добавить экспорт/удаление профиля в один клик для соответствия GDPR (право на забвение).",
      "mitigated": false
    }
  ],
  "passed_checks": [
    "Нулевая поверхность атаки на сервере — серверной части нет (CLIENT_ONLY)",
    "Данные резюме не покидают устройство: хранение только в chrome.storage.local",
    "Manifest V3 service worker — нет постоянного фонового процесса и remote-кода",
    "Content Security Policy MV3 запрещает inline-eval и внешние скрипты",
    "Минимальный набор permissions: activeTab, storage, contextMenus"
  ]
}`

// ── Mock runs ─────────────────────────────────────────────────────────────────

const MOCK_RUNS_LIST = [
  {
    id: 'a1b2c3d4-e5f6-7890-abcd-ef1234567890',
    chatId: null,
    rawUserIdea: 'Браузерное расширение Manifest V3 «Автофиллер-резюме / Resume Autofiller» — автозаполнение форм откликов на вакансии из локального профиля (chrome.storage.local), полностью на стороне клиента',
    status: 'COMPLETED' as const,
    artifactPath: '/tmp/midas_result_20260614_a1b2c3d4.zip',
    totalPromptTokens: 28_450,
    totalCompletionTokens: 19_320,
    needsRefactoring: false,
    createdAt: hoursAgo(0.8),
    updatedAt: minutesAgo(10),
  },
  {
    id: 'b2c3d4e5-f6a7-8901-bcde-f12345678901',
    chatId: 88221133,
    rawUserIdea: 'Создать микросервис для обработки платежей через Stripe с идемпотентностью и retry-логикой',
    status: 'COMPLETED' as const,
    artifactPath: '/tmp/midas_result_20260614_b2c3d4e5.zip',
    totalPromptTokens: 31_200,
    totalCompletionTokens: 22_800,
    needsRefactoring: false,
    createdAt: hoursAgo(2.5),
    updatedAt: hoursAgo(1.7),
  },
  {
    id: 'c3d4e5f6-a7b8-9012-cdef-123456789012',
    chatId: null,
    rawUserIdea: 'Blockchain-based voting system with Solidity smart contracts and React frontend',
    status: 'ERROR' as const,
    artifactPath: null,
    totalPromptTokens: 14_100,
    totalCompletionTokens: 8_900,
    needsRefactoring: false,
    createdAt: hoursAgo(3.2),
    updatedAt: hoursAgo(2.8),
  },
  {
    id: 'd4e5f6a7-b8c9-0123-defa-234567890123',
    chatId: 77441122,
    rawUserIdea: 'E-commerce платформа: каталог товаров, корзина, заказы, интеграция с ЮKassa, email-уведомления',
    status: 'COMPLETED' as const,
    artifactPath: '/tmp/midas_result_20260613_d4e5f6a7.zip',
    totalPromptTokens: 42_600,
    totalCompletionTokens: 31_400,
    needsRefactoring: true,
    createdAt: hoursAgo(5),
    updatedAt: hoursAgo(4.1),
  },
  {
    id: 'e5f6a7b8-c9d0-1234-efab-345678901234',
    chatId: 99553344,
    rawUserIdea: 'Нужно что-то для чат-бота с интеграцией OpenAI',
    status: 'WAITING_FOR_USER_INPUT' as const,
    artifactPath: null,
    totalPromptTokens: 4_200,
    totalCompletionTokens: 2_100,
    needsRefactoring: false,
    createdAt: minutesAgo(35),
    updatedAt: minutesAgo(12),
  },
  {
    id: 'f6a7b8c9-d0e1-2345-fabc-456789012345',
    chatId: null,
    rawUserIdea: 'Сервис аналитики событий (event sourcing + CQRS) с Kafka и ClickHouse',
    status: 'CODE_GENERATION' as const,
    artifactPath: null,
    totalPromptTokens: 18_800,
    totalCompletionTokens: 13_200,
    needsRefactoring: false,
    createdAt: minutesAgo(8),
    updatedAt: minutesAgo(2),
  },
  {
    id: 'a7b8c9d0-e1f2-3456-abcd-567890123456',
    chatId: 11223344,
    rawUserIdea: 'GraphQL API для блог-платформы с категориями, тегами, комментариями и полнотекстовым поиском',
    status: 'COMPLETED' as const,
    artifactPath: '/tmp/midas_result_20260613_a7b8c9d0.zip',
    totalPromptTokens: 26_700,
    totalCompletionTokens: 18_300,
    needsRefactoring: false,
    createdAt: hoursAgo(28),
    updatedAt: hoursAgo(27),
  },
  {
    id: 'b8c9d0e1-f2a3-4567-bcde-678901234567',
    chatId: null,
    rawUserIdea: 'Real-time аналитический дашборд для IoT устройств: телеметрия, алерты, агрегация метрик',
    status: 'COMPLETED' as const,
    artifactPath: '/tmp/midas_result_20260612_b8c9d0e1.zip',
    totalPromptTokens: 35_900,
    totalCompletionTokens: 25_100,
    needsRefactoring: false,
    createdAt: hoursAgo(36),
    updatedAt: hoursAgo(35),
  },
  {
    id: 'c9d0e1f2-a3b4-5678-cdef-789012345678',
    chatId: 55667788,
    rawUserIdea: 'Сервис аутентификации: OAuth2, social login (Google/GitHub), 2FA через TOTP',
    status: 'COMPLETED' as const,
    artifactPath: '/tmp/midas_result_20260611_c9d0e1f2.zip',
    totalPromptTokens: 23_100,
    totalCompletionTokens: 16_400,
    needsRefactoring: false,
    createdAt: hoursAgo(60),
    updatedAt: hoursAgo(59),
  },
  {
    id: 'd0e1f2a3-b4c5-6789-defa-890123456789',
    chatId: null,
    rawUserIdea: 'ETL-пайплайн для загрузки данных из S3 → Spark обработка → PostgreSQL с мониторингом через Prometheus',
    status: 'COMPLETED' as const,
    artifactPath: '/tmp/midas_result_20260610_d0e1f2a3.zip',
    totalPromptTokens: 38_400,
    totalCompletionTokens: 27_200,
    needsRefactoring: false,
    createdAt: hoursAgo(84),
    updatedAt: hoursAgo(83),
  },
]

// ── Mock agent logs for the first run ─────────────────────────────────────────

const MOCK_AGENT_LOGS_RUN1 = [
  { id: 'log-0001', runId: MOCK_RUNS_LIST[0].id, agentType: 'SystemAnalystAgent',       rawOutput: SYSTEM_ANALYST_OUTPUT, promptTokens: 1_200, completionTokens: 850,   executionTimeMs: 3_450,  isError: false, createdAt: new Date(new Date(MOCK_RUNS_LIST[0].createdAt).getTime() + 0).toISOString() },
  { id: 'log-0002', runId: MOCK_RUNS_LIST[0].id, agentType: 'SoftwareArchitectAgent',   rawOutput: ARCHITECT_OUTPUT,      promptTokens: 1_800, completionTokens: 1_200, executionTimeMs: 5_200,  isError: false, createdAt: new Date(new Date(MOCK_RUNS_LIST[0].createdAt).getTime() + 3_500).toISOString() },
  { id: 'log-0003', runId: MOCK_RUNS_LIST[0].id, agentType: 'IntegrationEngineerAgent', rawOutput: INTEGRATION_OUTPUT,    promptTokens: 1_100, completionTokens: 750,   executionTimeMs: 2_800,  isError: false, createdAt: new Date(new Date(MOCK_RUNS_LIST[0].createdAt).getTime() + 9_000).toISOString() },
  { id: 'log-0004', runId: MOCK_RUNS_LIST[0].id, agentType: 'ImplementationEngineerAgent', rawOutput: DEVELOPER_OUTPUT,   promptTokens: 6_200, completionTokens: 4_800, executionTimeMs: 18_500, isError: false, createdAt: new Date(new Date(MOCK_RUNS_LIST[0].createdAt).getTime() + 12_000).toISOString() },
  { id: 'log-0005', runId: MOCK_RUNS_LIST[0].id, agentType: 'QaAutomationAgent',        rawOutput: QA_OUTPUT,             promptTokens: 2_800, completionTokens: 2_100, executionTimeMs: 8_200,  isError: false, createdAt: new Date(new Date(MOCK_RUNS_LIST[0].createdAt).getTime() + 31_000).toISOString() },
  { id: 'log-0006', runId: MOCK_RUNS_LIST[0].id, agentType: 'SecOpsAgent',              rawOutput: SECOPS_OUTPUT,         promptTokens: 1_350, completionTokens: 950,   executionTimeMs: 4_100,  isError: false, createdAt: new Date(new Date(MOCK_RUNS_LIST[0].createdAt).getTime() + 39_500).toISOString() },
]

const MOCK_AGENT_LOGS_RUN3 = [
  { id: 'log-0007', runId: MOCK_RUNS_LIST[2].id, agentType: 'SystemAnalystAgent',     rawOutput: '{"business_goal":"Blockchain voting system","core_features":["Smart contracts in Solidity","Frontend in React","MetaMask wallet integration"]}', promptTokens: 980,    completionTokens: 710,  executionTimeMs: 2_900,  isError: false, createdAt: hoursAgo(3.2) },
  { id: 'log-0008', runId: MOCK_RUNS_LIST[2].id, agentType: 'SoftwareArchitectAgent', rawOutput: '{"technology_stack":{"solidity":"^0.8.20","hardhat":"^2.22"},"contracts":["VotingToken.sol","ElectionContract.sol"]}', promptTokens: 1_420,  completionTokens: 980,  executionTimeMs: 4_200,  isError: false, createdAt: hoursAgo(3.1) },
  { id: 'log-0009', runId: MOCK_RUNS_LIST[2].id, agentType: 'ImplementationEngineerAgent', rawOutput: 'AgentExecutionException: LLM returned malformed JSON after 3 retry attempts. Context window exceeded (32768 tokens).', promptTokens: 11_700, completionTokens: 7_210, executionTimeMs: 25_400, isError: true,  createdAt: hoursAgo(2.9) },
]

// ── Mock overview ─────────────────────────────────────────────────────────────

const MOCK_OVERVIEW: DashboardOverview = {
  totalRuns: 10,
  completedRuns: 7,
  errorRuns: 1,
  runningRuns: 1,
  totalPromptTokens: 263_450,
  totalCompletionTokens: 187_530,
  avgPipelineTimeMs: 52 * 60_000,
  mostExpensiveAgent: { agentType: 'ImplementationEngineerAgent', avgExecutionTimeMs: 16_800 },
}

const MOCK_PERFORMANCE_STATS: AgentPerformanceStat[] = [
  { agentType: 'SystemAnalystAgent',       avgExecutionTimeMs: 3_100,  maxExecutionTimeMs: 5_400,  totalInvocations: 10 },
  { agentType: 'SoftwareArchitectAgent',   avgExecutionTimeMs: 5_400,  maxExecutionTimeMs: 8_700,  totalInvocations: 10 },
  { agentType: 'IntegrationEngineerAgent', avgExecutionTimeMs: 3_600,  maxExecutionTimeMs: 6_100,  totalInvocations: 9  },
  { agentType: 'ImplementationEngineerAgent', avgExecutionTimeMs: 16_800, maxExecutionTimeMs: 28_500, totalInvocations: 9  },
  { agentType: 'QaAutomationAgent',        avgExecutionTimeMs: 9_200,  maxExecutionTimeMs: 14_300, totalInvocations: 8  },
  { agentType: 'SecOpsAgent',              avgExecutionTimeMs: 4_500,  maxExecutionTimeMs: 7_200,  totalInvocations: 8  },
]

const MOCK_TOKEN_USAGE: RunTokenUsage[] = MOCK_RUNS_LIST
  .filter(r => r.status === 'COMPLETED' || r.status === 'ERROR')
  .slice()
  .reverse()
  .map((r, i) => ({
    runIndex: i + 1,
    runId: r.id,
    promptTokens: r.totalPromptTokens,
    completionTokens: r.totalCompletionTokens,
    createdAt: r.createdAt,
  }))

// ── Mock evolution history ────────────────────────────────────────────────────

const MOCK_EVOLUTION_HISTORY: EvolutionLogItem[] = [
  {
    id: 'evo-0001-aaaa-bbbb-cccc-ddddeeeeeeee',
    runId: 'a1b2c3d4-e5f6-7890-abcd-ef1234567890',
    refactoringReport: `# Отчет об эволюции — Resume Autofiller (Manifest V3)

## Оценка: ✅ Высокое качество (8.5 / 10)

---

## 📊 Краткое резюме

Проанализировано браузерное расширение «Автофиллер-резюме». Архитектура CLIENT_ONLY — нулевая инфраструктура, данные не покидают устройство. Соответствует лучшим практикам Manifest V3. Выявлено несколько возможностей для повышения надёжности распознавания форм и приватности.

---

## 🔴 Критические проблемы

_Не обнаружено._

---

## 🟡 Улучшения (рекомендуется)

### 1. Повторное заполнение динамических форм (SPA)

\`\`\`typescript
// ❌ До — однократный проход, поля SPA ещё не отрисованы
fillForm(profile)

// ✅ После — наблюдаем за ре-рендером формы
const observer = new MutationObserver(() => fillForm(profile))
observer.observe(document.body, { childList: true, subtree: true })
\`\`\`

**Эффект:** Корректное заполнение форм на react-hook-form / Vue, которые монтируются асинхронно.

### 2. Сужение host-permissions до запроса по требованию

\`\`\`jsonc
// manifest.json — вместо широкого доступа
"optional_host_permissions": ["*://*/*"],
"permissions": ["activeTab", "storage", "contextMenus"]
\`\`\`

---

## 🟢 Что сделано хорошо

- ✅ CLIENT_ONLY: нет сервера, нет БД — нулевая серверная поверхность атаки
- ✅ Данные резюме хранятся только в \`chrome.storage.local\`
- ✅ MV3 service worker вместо постоянного фонового процесса
- ✅ Эвристики распознавания полей вынесены в конфиг и легко расширяются
- ✅ \`input\`-событие диспатчится для совместимости с реактивными формами

---

## 📈 Прогноз после рефакторинга

| Метрика | До | После |
|---|---|---|
| Успешность заполнения (SPA-формы) | 62% | 94% |
| Запрошенные permissions | <all_urls> | activeTab по требованию |
| Размер бандла | 480 KB | 470 KB |`,
    createdAt: hoursAgo(1.2),
  },
  {
    id: 'evo-0002-aaaa-bbbb-cccc-ddddeeeeeeee',
    runId: 'd4e5f6a7-b8c9-0123-defa-234567890123',
    refactoringReport: `# Отчет об эволюции — E-commerce Platform

## Оценка: ⚠️ Требует доработки (6 / 10)

---

## 📊 Краткое резюме

E-commerce платформа имеет хорошую базовую архитектуру, но выявлен ряд проблем безопасности и производительности, которые должны быть решены до деплоя в production.

---

## 🔴 Критические проблемы

### 1. Платежи: отсутствие идемпотентности в ЮKassa-интеграции

\`\`\`java
// ❌ Опасно — дублирование платежей при retry
public PaymentResult createPayment(Order order) {
    return youKassaClient.createPayment(order.toPaymentRequest());
}

// ✅ Правильно — идемпотентный ключ
public PaymentResult createPayment(Order order) {
    return youKassaClient.createPayment(
        order.toPaymentRequest()
             .withIdempotenceKey(order.getId().toString())
    );
}
\`\`\`

**Риск:** Двойное списание денег с пользователя при сетевой ошибке.

### 2. SQL Injection через динамический фильтр каталога

\`\`\`java
// ❌ Уязвимость
String query = "SELECT * FROM products WHERE " + filterParam;

// ✅ JPA Criteria API или Spring Data Specifications
Specification<Product> spec = ProductSpecifications.withFilter(filter);
return productRepository.findAll(spec, pageable);
\`\`\`

---

## 🟡 Улучшения

### Redis-кэш для каталога товаров

\`\`\`java
@Cacheable(value = "products", key = "#categoryId + ':' + #page")
public Page<ProductDto> getProductsByCategory(UUID categoryId, Pageable page) {
    return productRepository.findByCategoryId(categoryId, page).map(ProductMapper::toDto);
}
\`\`\`

---

## 🟢 Что сделано хорошо

- ✅ Email-уведомления через Spring Events (decoupled)
- ✅ Корзина хранится в Redis — правильное решение для stateless масштабирования
- ✅ Flyway миграции для всех изменений схемы`,
    createdAt: hoursAgo(3.8),
  },
  {
    id: 'evo-0003-aaaa-bbbb-cccc-ddddeeeeeeee',
    runId: 'b8c9d0e1-f2a3-4567-bcde-678901234567',
    refactoringReport: `# Отчет об эволюции — IoT Analytics Dashboard

## Оценка: ✅ Отличное качество (9 / 10)

---

## 📊 Краткое резюме

Один из лучших проектов за последние 30 дней. Event sourcing + CQRS реализованы корректно. Система телеметрии обрабатывает высокую нагрузку без деградации.

---

## 🔴 Критические проблемы

_Не обнаружено._

---

## 🟡 Улучшения

### Партиционирование таблицы телеметрии по времени

\`\`\`sql
-- PostgreSQL 16 — declarative partitioning by month
CREATE TABLE telemetry_data (
    device_id   UUID        NOT NULL,
    metric_name VARCHAR(100) NOT NULL,
    value       DOUBLE PRECISION NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL
) PARTITION BY RANGE (recorded_at);

CREATE TABLE telemetry_2026_06 PARTITION OF telemetry_data
    FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');
\`\`\`

**Эффект:** Запросы за последний месяц ускоряются в 15-40× за счет partition pruning.

---

## 🟢 Что сделано хорошо

- ✅ CQRS — команды и запросы полностью разделены
- ✅ Alert-сервис использует sliding window алгоритм (меньше ложных срабатываний)
- ✅ Prometheus-метрики экспортируются в правильном формате
- ✅ Агрегация метрик через materialized views — снижение нагрузки на 60%
- ✅ Backpressure handling в Kafka consumer

---

## 📈 Рекомендация

Рассмотреть TimescaleDB как замену PostgreSQL для time-series данных в production — даст ещё 3-5× прирост на типичных аналитических запросах.`,
    createdAt: hoursAgo(38),
  },
]

// ═════════════════════════════════════════════════════════════════════════════
//  PUBLIC API FUNCTIONS
// ═════════════════════════════════════════════════════════════════════════════

export async function fetchOverview(): Promise<DashboardOverview> {
  return apiFetch('/overview', MOCK_OVERVIEW)
}

export async function fetchRuns(page = 0, size = 20): Promise<RunsPage> {
  const mock: RunsPage = {
    content: MOCK_RUNS_LIST,
    totalElements: MOCK_RUNS_LIST.length,
    totalPages: 1,
    currentPage: 0,
  }
  return apiFetch(`/runs?page=${page}&size=${size}`, mock)
}

export async function fetchRunDetail(runId: string): Promise<RunDetail | null> {
  // Build a mock fallback regardless of whether the runId is in our local list
  const run = MOCK_RUNS_LIST.find(r => r.id === runId)
  const mockLogs = run
    ? runId === MOCK_RUNS_LIST[0].id ? MOCK_AGENT_LOGS_RUN1
      : runId === MOCK_RUNS_LIST[2].id ? MOCK_AGENT_LOGS_RUN3
      : generateSyntheticLogs(runId, run.status === 'COMPLETED' ? 6 : 3, run.createdAt)
    : []
  const mock: RunDetail | null = run ? { run, agentLogs: mockLogs } : null
  return apiFetch(`/runs/${runId}`, mock)
}

export async function fetchPerformanceStats(): Promise<AgentPerformanceStat[]> {
  return apiFetch('/performance', MOCK_PERFORMANCE_STATS)
}

export async function fetchTokenUsage(): Promise<RunTokenUsage[]> {
  return apiFetch('/token-usage', MOCK_TOKEN_USAGE)
}

export async function fetchEvolutionHistory(): Promise<EvolutionLogItem[]> {
  return apiFetch('/evolution-history', MOCK_EVOLUTION_HISTORY)
}

// ── Helper: synthetic logs for runs not in the detailed mock list ─────────────

const AGENT_TYPES   = ['SystemAnalystAgent','SoftwareArchitectAgent','IntegrationEngineerAgent','ImplementationEngineerAgent','QaAutomationAgent','SecOpsAgent']
const BASE_TIMES    = [3_000, 5_000, 3_200, 16_000, 9_000, 4_200]
const BASE_PROMPTS  = [1_000, 1_600, 950, 5_800, 2_600, 1_100]
const BASE_COMPS    = [700, 1_100, 650, 4_200, 1_900, 800]

function generateSyntheticLogs(runId: string, count: number, baseTime: string) {
  let offset = 0
  return AGENT_TYPES.slice(0, count).map((agentType, i) => {
    const ms = BASE_TIMES[i] + Math.floor(Math.random() * 2_000)
    const log = {
      id:              `synth-${runId}-${i}`,
      runId,
      agentType,
      rawOutput:       `{"status":"ok","agent":"${agentType}","summary":"Analysis complete."}`,
      promptTokens:    BASE_PROMPTS[i] + Math.floor(Math.random() * 300),
      completionTokens: BASE_COMPS[i] + Math.floor(Math.random() * 200),
      executionTimeMs: ms,
      isError:         false,
      createdAt:       new Date(new Date(baseTime).getTime() + offset).toISOString(),
    }
    offset += ms
    return log
  })
}
