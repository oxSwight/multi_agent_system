import Link from 'next/link'
import { LockKeyhole, MessageCircle, RefreshCw } from 'lucide-react'

export default function UnauthorizedPage() {
  return (
    <div className="flex min-h-screen items-center justify-center bg-[#080812] px-6">
      <div className="w-full max-w-lg text-center">

        {/* Lock icon with glow */}
        <div className="relative mx-auto mb-8 flex h-24 w-24 items-center justify-center">
          <div className="absolute inset-0 rounded-full bg-red-500/10 blur-xl" />
          <div className="relative flex h-20 w-20 items-center justify-center rounded-full border border-red-500/30 bg-[#1a0a0a]">
            <LockKeyhole size={36} className="text-red-400" />
          </div>
        </div>

        {/* Headline */}
        <h1 className="text-2xl font-bold text-slate-100 mb-3">
          Сессия истекла или доступ запрещён
        </h1>

        {/* Description */}
        <p className="text-slate-400 text-sm leading-relaxed mb-2">
          Ваш токен авторизации недействителен или истёк (срок действия — 24 часа).
        </p>
        <p className="text-slate-400 text-sm leading-relaxed mb-8">
          Чтобы получить новую ссылку, отправьте команду в Telegram-бот:
        </p>

        {/* Command badge */}
        <div className="mx-auto mb-8 inline-flex items-center gap-3 rounded-xl border border-indigo-500/30 bg-indigo-600/10 px-5 py-3">
          <MessageCircle size={18} className="text-indigo-400 shrink-0" />
          <code className="font-mono text-indigo-300 text-sm tracking-wide">/dashboard</code>
        </div>

        {/* Status detail */}
        <div className="mx-auto mb-8 rounded-xl border border-[#1e1e3a] bg-[#0d0d1f] px-5 py-4 text-left space-y-2">
          <p className="text-xs font-semibold uppercase tracking-widest text-slate-600">
            Детали ошибки
          </p>
          <div className="flex items-center gap-2">
            <span className="inline-flex items-center rounded-md bg-red-500/15 px-2 py-0.5 text-xs font-mono font-medium text-red-400 ring-1 ring-red-500/20">
              401 / 403
            </span>
            <span className="text-slate-400 text-xs">
              Authentication required — provide a valid Bearer token
            </span>
          </div>
        </div>

        {/* Actions */}
        <div className="flex flex-col sm:flex-row items-center justify-center gap-3">
          <Link
            href="/auth"
            className="flex items-center gap-2 rounded-lg bg-indigo-600 px-5 py-2.5 text-sm font-medium text-white hover:bg-indigo-500 transition-colors"
          >
            <RefreshCw size={14} />
            Уже есть ссылка
          </Link>
          <a
            href="https://t.me"
            target="_blank"
            rel="noopener noreferrer"
            className="flex items-center gap-2 rounded-lg border border-[#1e1e3a] bg-[#111127] px-5 py-2.5 text-sm font-medium text-slate-300 hover:text-slate-100 hover:border-indigo-500/40 transition-colors"
          >
            <MessageCircle size={14} />
            Открыть Telegram
          </a>
        </div>

      </div>
    </div>
  )
}
