'use client'

import Link from 'next/link'
import { usePathname } from 'next/navigation'
import {
  LayoutDashboard,
  ListOrdered,
  GitCommitHorizontal,
  Cpu,
  Zap,
  Github,
} from 'lucide-react'
import { cn } from '@/lib/utils'

// ── Nav items ─────────────────────────────────────────────────────────────────

const NAV = [
  { href: '/',          label: 'Обзор',       Icon: LayoutDashboard },
  { href: '/runs',      label: 'Все запуски',  Icon: ListOrdered    },
  { href: '/evolution', label: 'AI Эволюция',  Icon: GitCommitHorizontal },
]

// ── Pages that render without the sidebar (full-screen auth flows) ─────────────

const NO_SIDEBAR_PATHS = ['/auth', '/unauthorized']

export default function Sidebar() {
  const pathname = usePathname()

  // Auth and error pages get a full-screen layout (no sidebar chrome)
  if (NO_SIDEBAR_PATHS.some(p => pathname.startsWith(p))) return null

  const isActive = (href: string) =>
    href === '/' ? pathname === '/' : pathname.startsWith(href)

  return (
    <aside className="flex h-screen w-60 shrink-0 flex-col border-r border-[#1e1e3a] bg-[#0d0d1f]">

      {/* ── Brand ─────────────────────────────────────────────────────── */}
      <div className="flex items-center gap-3 px-5 py-5 border-b border-[#1e1e3a]">
        <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-indigo-600/20 ring-1 ring-indigo-500/40">
          <Cpu size={16} className="text-indigo-400" />
        </div>
        <div>
          <p className="text-sm font-semibold text-slate-100 tracking-wide">MIDAS D3</p>
          <p className="text-[10px] text-slate-500 font-mono uppercase tracking-widest">Dashboard</p>
        </div>
      </div>

      {/* ── Navigation ────────────────────────────────────────────────── */}
      <nav className="flex-1 overflow-y-auto px-3 py-4 space-y-0.5">
        <p className="px-2 pb-2 text-[10px] font-semibold uppercase tracking-widest text-slate-600">
          Мониторинг
        </p>
        {NAV.map(({ href, label, Icon }) => (
          <Link
            key={href}
            href={href}
            className={cn(
              'group flex items-center gap-3 rounded-lg px-3 py-2 text-sm transition-colors',
              isActive(href)
                ? 'bg-indigo-600/15 text-indigo-300 font-medium'
                : 'text-slate-400 hover:bg-white/5 hover:text-slate-200',
            )}
          >
            <Icon
              size={15}
              className={cn(
                'shrink-0 transition-colors',
                isActive(href) ? 'text-indigo-400' : 'text-slate-500 group-hover:text-slate-300',
              )}
            />
            {label}
            {isActive(href) && (
              <span className="ml-auto h-1.5 w-1.5 rounded-full bg-indigo-400" />
            )}
          </Link>
        ))}
      </nav>

      {/* ── Footer ────────────────────────────────────────────────────── */}
      <div className="border-t border-[#1e1e3a] px-4 py-4 space-y-3">
        <div className="flex items-center gap-2 text-xs text-slate-600">
          <Zap size={12} className="text-indigo-600" />
          <span>Powered by MIDAS D3</span>
        </div>
        <a
          href="https://github.com"
          target="_blank"
          rel="noopener noreferrer"
          className="flex items-center gap-2 text-xs text-slate-500 hover:text-slate-300 transition-colors"
        >
          <Github size={13} />
          <span>Source code</span>
        </a>
      </div>
    </aside>
  )
}
