import { fetchEvolutionHistory } from '@/services/api'
import EvolutionTimeline        from '@/components/evolution/EvolutionTimeline'
import { GitCommitHorizontal, Sparkles } from 'lucide-react'

export const revalidate = 30

export default async function EvolutionPage() {
  const entries = await fetchEvolutionHistory()

  return (
    <div className="page-enter min-h-full px-6 py-8 md:px-8 space-y-8">

      {/* ── Page header ──────────────────────────────────────────────────── */}
      <div className="flex items-start justify-between">
        <div>
          <h1 className="text-xl font-bold text-slate-100 flex items-center gap-2">
            <GitCommitHorizontal size={20} className="text-indigo-400" />
            AI Эволюция
          </h1>
          <p className="mt-1 text-sm text-slate-500">
            Автоматические отчёты EvolutionAgent об улучшении сгенерированного кода
          </p>
        </div>

        {/* Stats pill */}
        <div className="flex items-center gap-2 rounded-lg border border-[#1e1e3a] bg-[#111127] px-3 py-2">
          <Sparkles size={12} className="text-indigo-400" />
          <span className="text-xs text-slate-400">
            {entries.length} {entries.length === 1 ? 'отчёт' : entries.length < 5 ? 'отчёта' : 'отчётов'}
          </span>
        </div>
      </div>

      {/* ── Info banner ──────────────────────────────────────────────────── */}
      <div className="rounded-xl border border-indigo-500/20 bg-indigo-600/5 px-5 py-4 flex gap-3">
        <Sparkles size={16} className="text-indigo-400 mt-0.5 shrink-0" />
        <div className="text-sm text-slate-400 leading-relaxed">
          <span className="text-slate-300 font-medium">Как это работает: </span>
          когда MIDAS помечает запуск как{' '}
          <code className="rounded bg-slate-800 px-1.5 py-0.5 text-xs text-amber-300 font-mono">needsRefactoring=true</code>,
          {' '}EvolutionAgent автоматически анализирует весь сгенерированный код и составляет
          детальный Markdown-отчёт с конкретными рекомендациями. Цикл запускается каждый час.
        </div>
      </div>

      {/* ── Timeline ─────────────────────────────────────────────────────── */}
      <EvolutionTimeline entries={entries} />

    </div>
  )
}
