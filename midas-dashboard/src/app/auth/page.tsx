'use client'

import { useEffect, useState, Suspense } from 'react'
import { useSearchParams, useRouter }    from 'next/navigation'
import { Cpu, CheckCircle2, AlertCircle, Loader2 } from 'lucide-react'
import { TOKEN_COOKIE_NAME } from '@/lib/auth'

// ── Inner component (needs Suspense because of useSearchParams) ───────────────

function AuthHandler() {
  const params = useSearchParams()
  const router = useRouter()
  const [state, setState] = useState<'processing' | 'success' | 'error'>('processing')

  useEffect(() => {
    const token = params.get('token')

    if (!token || token.trim() === '') {
      setState('error')
      // Remove the (empty) token param from the URL immediately.
      window.history.replaceState({}, '', '/auth')
      setTimeout(() => router.replace('/unauthorized'), 2000)
      return
    }

    // Remove the JWT from the browser URL and history immediately — before any
    // async work — so it does not linger in the address bar, browser history,
    // or server access logs from subsequent navigations.
    window.history.replaceState({}, '', '/auth')

    // Store in cookie (readable by Next.js Server Components via cookies()).
    // SameSite=Lax is the best we can do for a magic-link flow; HttpOnly is
    // impossible here because the cookie must be written by client-side JS.
    document.cookie = [
      `${TOKEN_COOKIE_NAME}=${encodeURIComponent(token)}`,
      'path=/',
      'max-age=86400',       // 24 hours — matches backend JWT expiry
      'SameSite=Lax',
    ].join('; ')

    // NOTE: localStorage storage removed. It was redundant (the cookie is the
    // canonical store) and created a second XSS-accessible token surface.

    setState('success')
    setTimeout(() => router.replace('/'), 1200)
  }, [params, router])

  return (
    <div className="flex flex-col items-center gap-5 text-center">
      {state === 'processing' && (
        <>
          <Loader2 size={40} className="text-indigo-400 animate-spin" />
          <p className="text-slate-300 text-sm">Проверяем токен доступа…</p>
        </>
      )}
      {state === 'success' && (
        <>
          <CheckCircle2 size={40} className="text-emerald-400" />
          <p className="text-emerald-300 font-medium">Авторизация успешна!</p>
          <p className="text-slate-400 text-sm">Перенаправляем на дашборд…</p>
        </>
      )}
      {state === 'error' && (
        <>
          <AlertCircle size={40} className="text-red-400" />
          <p className="text-red-300 font-medium">Токен не найден в ссылке</p>
          <p className="text-slate-400 text-sm">Перенаправляем на страницу ошибки…</p>
        </>
      )}
    </div>
  )
}

// ── Page ──────────────────────────────────────────────────────────────────────

export default function AuthPage() {
  return (
    <div className="flex min-h-screen items-center justify-center bg-[#080812] px-6">
      <div className="w-full max-w-sm rounded-2xl border border-[#1e1e3a] bg-[#0d0d1f] p-10 shadow-2xl shadow-black/60">

        {/* Brand */}
        <div className="mb-8 flex flex-col items-center gap-3">
          <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-indigo-600/20 ring-2 ring-indigo-500/30">
            <Cpu size={28} className="text-indigo-400" />
          </div>
          <div className="text-center">
            <p className="text-lg font-bold text-slate-100 tracking-wide">MIDAS D3</p>
            <p className="text-xs text-slate-500 font-mono uppercase tracking-widest mt-0.5">
              Magic Link Auth
            </p>
          </div>
        </div>

        {/* Handler (Suspense required for useSearchParams in App Router) */}
        <Suspense
          fallback={
            <div className="flex flex-col items-center gap-4 text-center">
              <Loader2 size={36} className="text-indigo-400 animate-spin" />
              <p className="text-slate-400 text-sm">Загрузка…</p>
            </div>
          }
        >
          <AuthHandler />
        </Suspense>

      </div>
    </div>
  )
}
