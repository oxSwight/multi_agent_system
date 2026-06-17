export default function RunsListSkeleton() {
  return (
    <div className="min-h-full px-6 py-8 md:px-8 space-y-8 animate-pulse">
      <div className="space-y-2">
        <div className="h-6 w-36 rounded-lg bg-[#1e1e3a]" />
        <div className="h-4 w-56 rounded bg-[#1a1a30]" />
      </div>

      <div className="rounded-xl border border-[#1e1e3a] bg-[#111127] overflow-hidden">
        <div className="px-5 py-4 border-b border-[#1e1e3a] space-y-1.5">
          <div className="h-3 w-32 rounded bg-[#1e1e3a]" />
          <div className="h-3 w-20 rounded bg-[#1a1a30]" />
        </div>
        {Array.from({ length: 10 }).map((_, i) => (
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
