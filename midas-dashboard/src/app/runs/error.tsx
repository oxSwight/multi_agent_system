'use client'

import { useEffect } from 'react'
import { List, RefreshCw } from 'lucide-react'

interface ErrorProps {
  error: Error & { digest?: string }
  reset: () => void
}

export default function RunsError({ error, reset }: ErrorProps) {
  useEffect(() => {
    console.error('[MIDAS] Runs page error:', error)
  }, [error])

  return (
    <div className="flex min-h-full items-center justify-center px-6 py-16">
      <div className="flex flex-col items-center gap-6 text-center max-w-sm w-full">
        <div className="flex h-16 w-16 items-center justify-center rounded-2xl bg-red-500/10 ring-1 ring-red-500/20">
          <List size={28} className="text-red-400" />
        </div>
        <div>
          <h2 className="text-lg font-bold text-slate-100 mb-2">
            Список запусков недоступен
          </h2>
          <p className="text-sm text-slate-500 leading-relaxed">
            Не удалось получить данные о запусках пайплайна.
          </p>
        </div>
        <button
          onClick={reset}
          className="flex items-center gap-2 rounded-lg border border-indigo-500/30 bg-indigo-600/20 px-4 py-2 text-sm text-indigo-300 transition-colors hover:bg-indigo-600/30"
        >
          <RefreshCw size={14} />
          Попробовать снова
        </button>
      </div>
    </div>
  )
}
