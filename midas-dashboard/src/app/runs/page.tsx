import { fetchRuns } from '@/services/api'
import RunsTable from '@/components/dashboard/RunsTable'

export const revalidate = 10

export default async function RunsPage() {
  const page = await fetchRuns(0, 50)

  return (
    <div className="page-enter min-h-full px-6 py-8 md:px-8 space-y-6">
      <div>
        <h1 className="text-xl font-bold text-slate-100">Все запуски</h1>
        <p className="mt-1 text-sm text-slate-500">
          История всех запусков пайплайна · всего {page.totalElements}
        </p>
      </div>
      <RunsTable runs={page.content} />
    </div>
  )
}
