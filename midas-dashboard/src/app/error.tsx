'use client'

/**
 * Global error boundary for the entire application.
 * Next.js App Router requires this to be a Client Component.
 * Catches unhandled exceptions thrown by Server Components that are NOT
 * caught by a more specific nested error.tsx.
 */

import { useEffect } from 'react'
import { AlertTriangle, RefreshCw, Home } from 'lucide-react'
import Link from 'next/link'

interface ErrorProps {
  error: Error & { digest?: string }
  reset: () => void
}

export default function GlobalError({ error, reset }: ErrorProps) {
  useEffect(() => {
    // Log to your observability stack here (Sentry, Datadog, etc.)
    console.error('[MIDAS] Unhandled error:', error)
  }, [error])

  return (
    <div className="flex min-h-full items-center justify-center px-6 py-16">
      <div className="flex flex-col items-center gap-6 text-center max-w-md w-full">

        {/* Icon */}
        <div className="flex h-16 w-16 items-center justify-center rounded-2xl bg-red-500/10 ring-1 ring-red-500/20">
          <AlertTriangle size={28} className="text-red-400" />
        </div>

        {/* Copy */}
        <div>
          <h2 className="text-lg font-bold text-slate-100 mb-2">
            Что-то пошло не так
          </h2>
          <p className="text-sm text-slate-500 leading-relaxed">
            Произошла ошибка при загрузке данных. Это может быть временная
            проблема — попробуйте обновить страницу.
          </p>
          {error.digest && (
            <p className="mt-3 font-mono text-xs text-slate-600">
              Error ID: {error.digest}
            </p>
          )}
        </div>

        {/* Actions */}
        <div className="flex items-center gap-3">
          <button
            onClick={reset}
            className="flex items-center gap-2 rounded-lg border border-indigo-500/30 bg-indigo-600/20 px-4 py-2 text-sm text-indigo-300 transition-colors hover:bg-indigo-600/30"
          >
            <RefreshCw size={14} />
            Попробовать снова
          </button>
          <Link
            href="/"
            className="flex items-center gap-2 rounded-lg border border-[#1e1e3a] bg-[#111127] px-4 py-2 text-sm text-slate-400 transition-colors hover:text-slate-200"
          >
            <Home size={14} />
            На главную
          </Link>
        </div>

      </div>
    </div>
  )
}
