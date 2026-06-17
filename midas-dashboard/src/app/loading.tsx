/**
 * Dashboard skeleton loader (route-level Suspense boundary).
 * Shown immediately on navigation — before the Server Component resolves its
 * four parallel data fetches. Mirrors the exact layout of page.tsx so the
 * page loads without any Cumulative Layout Shift (CLS).
 */
export default function DashboardSkeleton() {
  return (
    <div className="min-h-full px-6 py-8 md:px-8 space-y-8 animate-pulse">

      {/* Page header */}
      <div className="flex items-start justify-between">
        <div className="space-y-2">
          <div className="h-6 w-44 rounded-lg bg-[#1e1e3a]" />
          <div className="h-4 w-64 rounded bg-[#1a1a30]" />
        </div>
        <div className="h-9 w-36 rounded-lg bg-[#1e1e3a]" />
      </div>

      {/* Metric cards — 4 columns */}
      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        {Array.from({ length: 4 }).map((_, i) => (
          <div key={i} className="rounded-xl border border-[#1e1e3a] bg-[#111127] p-5 space-y-3">
            <div className="flex items-center justify-between">
              <div className="h-3 w-20 rounded bg-[#1e1e3a]" />
              <div className="h-7 w-7 rounded-lg bg-[#1e1e3a]" />
            </div>
            <div className="h-8 w-16 rounded-lg bg-[#252545]" />
            <div className="h-3 w-28 rounded bg-[#1a1a30]" />
          </div>
        ))}
      </div>

      {/* Charts row */}
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-5">
        <div className="lg:col-span-3 rounded-xl border border-[#1e1e3a] bg-[#111127] p-5">
          <div className="h-3 w-24 rounded bg-[#1e1e3a] mb-2" />
          <div className="h-3 w-40 rounded bg-[#1a1a30] mb-5" />
          <div className="h-[220px] w-full rounded-lg bg-[#1a1a30]" />
        </div>
        <div className="lg:col-span-2 rounded-xl border border-[#1e1e3a] bg-[#111127] p-5">
          <div className="h-3 w-24 rounded bg-[#1e1e3a] mb-2" />
          <div className="h-3 w-40 rounded bg-[#1a1a30] mb-5" />
          <div className="flex items-center gap-6">
            <div className="h-[160px] w-[160px] shrink-0 rounded-full bg-[#1a1a30]" />
            <div className="flex-1 space-y-2.5">
              {Array.from({ length: 5 }).map((_, i) => (
                <div key={i} className="flex items-center gap-2">
                  <div className="h-2 w-2 rounded-sm bg-[#1e1e3a]" />
                  <div className="h-3 flex-1 rounded bg-[#1e1e3a]" />
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>

      {/* Runs table */}
      <div className="rounded-xl border border-[#1e1e3a] bg-[#111127] overflow-hidden">
        <div className="flex items-center justify-between px-5 py-4 border-b border-[#1e1e3a]">
          <div className="space-y-1.5">
            <div className="h-3 w-32 rounded bg-[#1e1e3a]" />
            <div className="h-3 w-20 rounded bg-[#1a1a30]" />
          </div>
        </div>
        {/* Table header */}
        <div className="flex gap-4 px-5 py-3 border-b border-[#1e1e3a]">
          {[3, 4, 14, 3, 3, 5].map((w, i) => (
            <div key={i} className={`h-2.5 w-${w * 4} rounded bg-[#1e1e3a]`} />
          ))}
        </div>
        {/* Table rows */}
        {Array.from({ length: 7 }).map((_, i) => (
          <div key={i} className="flex items-center gap-4 px-5 py-4 border-b border-[#1e1e3a] last:border-0">
            <div className="h-4 w-14 shrink-0 rounded bg-[#1e1e3a]" />
            <div className="h-5 w-24 shrink-0 rounded-full bg-[#1e1e3a]" />
            <div className="h-4 flex-1 rounded bg-[#1a1a30]" />
            <div className="h-4 w-12 shrink-0 rounded bg-[#1e1e3a]" />
            <div className="h-4 w-16 shrink-0 rounded bg-[#1e1e3a]" />
            <div className="h-4 w-24 shrink-0 rounded bg-[#1e1e3a]" />
            <div className="h-4 w-4 shrink-0 rounded bg-[#1e1e3a]" />
          </div>
        ))}
      </div>

    </div>
  )
}
