export default function RunDetailSkeleton() {
  return (
    <div className="min-h-full px-6 py-8 md:px-8 space-y-6 animate-pulse">
      {/* Back link */}
      <div className="h-4 w-24 rounded bg-[#1e1e3a]" />

      {/* Run header card */}
      <div className="rounded-xl border border-[#1e1e3a] bg-[#111127] p-6 space-y-4">
        <div className="flex items-start justify-between">
          <div className="space-y-2">
            <div className="h-3 w-20 rounded bg-[#1e1e3a]" />
            <div className="h-6 w-56 rounded-lg bg-[#252545]" />
          </div>
          <div className="h-7 w-24 rounded-full bg-[#1e1e3a]" />
        </div>
        <div className="h-4 flex-1 rounded bg-[#1a1a30]" />
        <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
          {Array.from({ length: 4 }).map((_, i) => (
            <div key={i} className="space-y-1.5">
              <div className="h-3 w-16 rounded bg-[#1e1e3a]" />
              <div className="h-5 w-20 rounded bg-[#252545]" />
            </div>
          ))}
        </div>
      </div>

      {/* Agent log cards */}
      <div className="space-y-3">
        <div className="h-4 w-32 rounded bg-[#1e1e3a]" />
        {Array.from({ length: 5 }).map((_, i) => (
          <div key={i} className="rounded-xl border border-[#1e1e3a] bg-[#111127] px-5 py-4">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                <div className="h-4 w-40 rounded bg-[#252545]" />
                <div className="h-5 w-16 rounded-md bg-[#1e1e3a]" />
                <div className="h-5 w-20 rounded-md bg-[#1e1e3a]" />
              </div>
              <div className="h-4 w-4 rounded bg-[#1e1e3a]" />
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}
