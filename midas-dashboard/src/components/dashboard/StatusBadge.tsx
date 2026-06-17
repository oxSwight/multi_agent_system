import { cn, statusLabel } from '@/lib/utils'
import type { PipelineStatus } from '@/types'

interface StatusBadgeProps {
  status: PipelineStatus
  size?: 'sm' | 'md'
  showDot?: boolean
}

// ── Visual config per status ──────────────────────────────────────────────────

type BadgeCfg = {
  classes: string
  dotClasses: string
  pulse: boolean
}

function getConfig(status: PipelineStatus): BadgeCfg {
  switch (status) {
    case 'COMPLETED':
      return {
        classes:    'bg-emerald-500/10 text-emerald-400 border-emerald-500/25',
        dotClasses: 'bg-emerald-400',
        pulse:      false,
      }
    case 'ERROR':
      return {
        classes:    'bg-red-500/10 text-red-400 border-red-500/25',
        dotClasses: 'bg-red-400',
        pulse:      false,
      }
    case 'WAITING_FOR_USER_INPUT':
      return {
        classes:    'bg-amber-500/10 text-amber-400 border-amber-500/25 badge-pulse',
        dotClasses: 'bg-amber-400 animate-pulse',
        pulse:      true,
      }
    case 'STARTED':
      return {
        classes:    'bg-sky-500/10 text-sky-400 border-sky-500/25',
        dotClasses: 'bg-sky-400',
        pulse:      false,
      }
    // All active processing states use indigo
    case 'SYSTEM_ANALYSIS':
    case 'ARCHITECTURE_DESIGN':
    case 'INTEGRATION_STRATEGY':
    case 'CODE_GENERATION':
    case 'TEST_GENERATION':
    case 'SECOPS_AUDIT':
      return {
        classes:    'bg-indigo-500/10 text-indigo-400 border-indigo-500/25',
        dotClasses: 'bg-indigo-400 animate-pulse',
        pulse:      true,
      }
    default:
      return {
        classes:    'bg-slate-700/30 text-slate-400 border-slate-600/30',
        dotClasses: 'bg-slate-400',
        pulse:      false,
      }
  }
}

export default function StatusBadge({
  status,
  size = 'md',
  showDot = true,
}: StatusBadgeProps) {
  const cfg = getConfig(status)

  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 rounded-full border font-medium',
        size === 'sm'
          ? 'px-2 py-0.5 text-[10px]'
          : 'px-2.5 py-0.5 text-xs',
        cfg.classes,
      )}
    >
      {showDot && (
        <span className={cn('h-1.5 w-1.5 shrink-0 rounded-full', cfg.dotClasses)} />
      )}
      {statusLabel(status)}
    </span>
  )
}
