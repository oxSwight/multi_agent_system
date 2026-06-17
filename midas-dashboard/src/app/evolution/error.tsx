'use client'

import { useEffect } from 'react'
import { GitCommitHorizontal, RefreshCw } from 'lucide-react'

interface ErrorProps {
  error: Error & { digest?: string }
  reset: () => void
}

export default function EvolutionError({ error, reset }: ErrorProps) {
  useEffect(() => {
    console.error('[MIDAS] Evolution page error:', error)
  }, [error])

  return (
    <div className="flex min-h-full items-center justify-center px-6 py-16">
      <div className="flex flex-col items-center gap-6 text-center max-w-sm w-full">
        <div className="flex h-16 w-16 items-center justify-center rounded-2xl bg-amber-500/10 ring-1 ring-amber-500/20">
          <GitCommitHorizontal size={28} className="text-amber-400" />
        </div>
        <div>
          <h2 className="text-lg font-bold text-slate-100 mb-2">
            История эволюции недоступна
          </h2>
          <p className="text-sm text-slate-500 leading-relaxed">
            Не удалось загрузить отчёты EvolutionAgent. Попробуйте обновить
            страницу — это может быть временная проблема.
          </p>
        </div>
        <button
          onClick={reset}
          className="flex items-center gap-2 rounded-lg border border-indigo-500/30 bg-indigo-600/20 px-4 py-2 text-sm text-indigo-300 transition-colors hover:bg-indigo-600/30"
        >
          <RefreshCw size={14} />
          Повторить загрузку
        </button>
      </div>
    </div>
  )
}
