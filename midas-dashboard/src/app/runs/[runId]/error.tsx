'use client'

import { useEffect } from 'react'
import { ArrowLeft, RefreshCw } from 'lucide-react'
import Link from 'next/link'

interface ErrorProps {
  error: Error & { digest?: string }
  reset: () => void
}

export default function RunDetailError({ error, reset }: ErrorProps) {
  useEffect(() => {
    console.error('[MIDAS] Run detail error:', error)
  }, [error])

  return (
    <div className="min-h-full px-6 py-8 md:px-8">
      <Link
        href="/runs"
        className="mb-8 inline-flex items-center gap-1.5 text-sm text-slate-500 hover:text-slate-300 transition-colors"
      >
        <ArrowLeft size={14} />
        Все запуски
      </Link>

      <div className="flex flex-col items-center gap-6 py-16 text-center max-w-sm mx-auto">
        <div className="flex h-16 w-16 items-center justify-center rounded-2xl bg-red-500/10 ring-1 ring-red-500/20">
          <RefreshCw size={28} className="text-red-400" />
        </div>
        <div>
          <h2 className="text-lg font-bold text-slate-100 mb-2">
            Детали запуска недоступны
          </h2>
          <p className="text-sm text-slate-500 leading-relaxed">
            Не удалось загрузить данные этого запуска.
          </p>
        </div>
        <button
          onClick={reset}
          className="flex items-center gap-2 rounded-lg border border-indigo-500/30 bg-indigo-600/20 px-4 py-2 text-sm text-indigo-300 transition-colors hover:bg-indigo-600/30"
        >
          <RefreshCw size={14} />
          Повторить
        </button>
      </div>
    </div>
  )
}
