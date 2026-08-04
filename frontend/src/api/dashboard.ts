import { queryOptions } from '@tanstack/react-query'
import { summary as fetchDashboardSummary } from './generated/dashboard-controller/dashboard-controller'
import type { DashboardSummary as GeneratedDashboardSummary } from './generated/model'

export type DashboardReceivable = {
  currency_code: string
  currency_symbol?: string
  minor_unit: number
  outstanding_minor: string
}

type DashboardSummary = Omit<GeneratedDashboardSummary, 'receivables'> & {
  receivables?: DashboardReceivable[]
}

export const dashboardSummaryQuery = queryOptions({
  queryKey: ['dashboard', 'summary'],
  queryFn: async ({ signal }) =>
    (await fetchDashboardSummary(signal)) as DashboardSummary,
  refetchInterval: 30_000,
})
