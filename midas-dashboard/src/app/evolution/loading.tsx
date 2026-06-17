/**
 * Evolution page skeleton loader (route-level Suspense boundary).
 * Mirrors the accordion timeline layout of the evolution page to prevent CLS.
 */
export default function EvolutionSkeleton() {
  return (
    <div className="min-h-full px-6 py-8 md:px-8 space-y-8 animate-pulse">

      {/* Page header */}
      <div className="flex items-start justify-between">
        <div className="space-y-2">
          <div className="h-6 w-48 rounded-lg bg-[#1e1e3a]" />
          <div className="h-4 w-72 rounded bg-[#1a1a30]" />
        </div>
        <div className="h-9 w-24 rounded-lg bg-[#1e1e3a]" />
      </div>

      {/* Info banner */}
      <div className="rounded-xl border border-indigo-500/10 bg-indigo-600/5 px-5 py-4 h-16" />

      {/* Timeline entries */}
      <div className="relative space-y-0">
        {Array.from({ length: 3 }).map((_, i) => (
          <div key={i} className="relative flex gap-4">
            {/* Connector line */}
            {i < 2 && (
              <div className="absolute left-[19px] top-10 bottom-0 w-px bg-[#1e1e3a]" />
            )}
            {/* Dot */}
            <div className="relative z-10 mt-1 h-10 w-10 shrink-0 rounded-full bg-[#1e1e3a]" />
            {/* Card */}
            <div className="mb-6 flex-1 rounded-xl border border-[#1e1e3a] bg-[#111127] overflow-hidden">
              {/* Card header */}
              <div className="flex items-center justify-between gap-3 px-5 py-4">
                <div className="flex flex-wrap items-center gap-3">
                  <div className="h-6 w-32 rounded-md bg-[#1e1e3a]" />
                  <div className="h-6 w-24 rounded-md bg-[#1e1e3a]" />
                  <div className="h-6 w-20 rounded-md bg-[#1e1e3a]" />
                </div>
                <div className="h-4 w-4 rounded bg-[#1e1e3a]" />
              </div>
              {/* Expanded body for first card only */}
              {i === 0 && (
                <div className="border-t border-[#1e1e3a] px-5 py-5 space-y-3">
                  <div className="h-4 w-3/4 rounded bg-[#1e1e3a]" />
                  <div className="h-4 w-full rounded bg-[#1a1a30]" />
                  <div className="h-4 w-5/6 rounded bg-[#1a1a30]" />
                  <div className="h-4 w-2/3 rounded bg-[#1a1a30]" />
                  <div className="mt-4 h-32 w-full rounded-xl bg-[#0d1117]" />
                  <div className="h-4 w-full rounded bg-[#1a1a30]" />
                  <div className="h-4 w-4/5 rounded bg-[#1a1a30]" />
                </div>
              )}
            </div>
          </div>
        ))}
      </div>

    </div>
  )
}
