import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import Decimal from 'decimal.js'
import { BadgeDollarSign, CalendarDays, Clock3 } from 'lucide-react'
import {
  Bar,
  BarChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import {
  compareMinor,
  money,
  receivablesReportQuery,
  type ReceivablesReport,
} from '@/api/operations'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { Main } from '@/components/layout/main'
import { ConsoleHeader } from '@/features/shell/console-header'
import { PageHeading } from '@/features/shell/page-heading'

export function ReportsPage() {
  const [asOf, setAsOf] = useState(() => new Date().toISOString().slice(0, 10))
  const report = useQuery(receivablesReportQuery(asOf))
  const firstCurrency = report.data?.currencies[0]?.currency_code
  const aging = report.data
    ? normalizeAging(
        report.data.aging.filter((item) => item.currency_code === firstCurrency)
      )
    : undefined
  return (
    <>
      <ConsoleHeader label='reports' />
      <Main className='min-w-0 space-y-7'>
        <PageHeading
          eyebrow='报表中心'
          title='基础应收与账龄'
          description='报表只聚合已确认、已发送或已被更正替代的正式账单；付款状态来自有效分配流水。'
          action={
            <label className='flex max-w-full min-w-0 items-center gap-2 rounded-md border bg-background px-3 py-1.5'>
              <CalendarDays className='size-4 text-muted-foreground' />
              <Input
                type='date'
                value={asOf}
                onChange={(event) => setAsOf(event.target.value)}
                className='h-7 w-[8.5rem] max-w-full border-0 p-0 shadow-none focus-visible:ring-0'
              />
            </label>
          }
        />
        {report.isLoading || !report.data ? (
          <Loading />
        ) : (
          <>
            <div className='grid min-w-0 gap-4 md:grid-cols-2 2xl:grid-cols-4'>
              {report.data.currencies.flatMap((currency) => [
                <Metric
                  key={`${currency.currency_code}-outstanding`}
                  icon={<BadgeDollarSign />}
                  label={`${currency.currency_code} 未结应收`}
                  value={money(
                    currency.outstanding_minor,
                    currency.currency_code
                  )}
                />,
                <Metric
                  key={`${currency.currency_code}-overdue`}
                  icon={<Clock3 />}
                  label={`${currency.currency_code} 逾期`}
                  value={money(currency.overdue_minor, currency.currency_code)}
                  bad={compareMinor(currency.overdue_minor, 0) > 0}
                />,
              ])}
            </div>
            <div className='grid min-w-0 gap-5 xl:grid-cols-[minmax(0,.8fr)_minmax(0,1.2fr)]'>
              <Card className='min-w-0 shadow-none'>
                <CardHeader>
                  <CardTitle className='text-base'>
                    {firstCurrency ?? '—'} 账龄分布
                  </CardTitle>
                </CardHeader>
                <CardContent className='h-72 min-w-0 px-2 sm:px-6'>
                  {aging?.length ? (
                    <ResponsiveContainer
                      width='100%'
                      height='100%'
                      initialDimension={{ width: 1, height: 1 }}
                    >
                      <BarChart
                        data={aging}
                        margin={{ top: 8, right: 4, bottom: 0, left: 0 }}
                      >
                        <CartesianGrid vertical={false} strokeDasharray='3 3' />
                        <XAxis
                          dataKey='label'
                          tickLine={false}
                          axisLine={false}
                        />
                        <YAxis
                          tickLine={false}
                          axisLine={false}
                          width={60}
                          domain={[0, 1]}
                          tickFormatter={(value: number) =>
                            `${Math.round(value * 100)}%`
                          }
                        />
                        <Tooltip
                          cursor={{ fill: 'var(--muted)', opacity: 0.35 }}
                          content={
                            <AgingTooltip currency={firstCurrency ?? 'CNY'} />
                          }
                        />
                        <Bar
                          dataKey='chart_ratio'
                          fill='var(--primary)'
                          radius={[5, 5, 0, 0]}
                        />
                      </BarChart>
                    </ResponsiveContainer>
                  ) : (
                    <p className='grid h-full place-items-center text-sm text-muted-foreground'>
                      当前没有未结账龄。
                    </p>
                  )}
                </CardContent>
              </Card>
              <Card className='min-w-0 gap-0 overflow-hidden py-0 shadow-none'>
                <CardHeader className='border-b py-5'>
                  <CardTitle className='text-base'>最大未结账单</CardTitle>
                </CardHeader>
                <CardContent className='p-0'>
                  <Table>
                    <TableHeader>
                      <TableRow className='bg-muted/30'>
                        <TableHead>正式编号</TableHead>
                        <TableHead>到期日</TableHead>
                        <TableHead>币种</TableHead>
                        <TableHead className='text-right'>未结金额</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {report.data.largest_outstanding.map((invoice) => (
                        <TableRow key={invoice.invoice_id}>
                          <TableCell className='max-w-48 font-mono text-xs font-semibold break-all whitespace-normal'>
                            {invoice.invoice_number}
                          </TableCell>
                          <TableCell className='font-mono text-xs'>
                            {invoice.due_date}
                          </TableCell>
                          <TableCell>{invoice.currency_code}</TableCell>
                          <TableCell className='text-right font-mono font-semibold'>
                            {money(
                              invoice.outstanding_minor,
                              invoice.currency_code
                            )}
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </CardContent>
              </Card>
            </div>
          </>
        )}
      </Main>
    </>
  )
}

function Metric({
  icon,
  label,
  value,
  bad,
}: {
  icon: React.ReactNode
  label: string
  value: string
  bad?: boolean
}) {
  return (
    <Card className='min-w-0 py-5 shadow-none'>
      <CardContent className='flex min-w-0 items-center gap-3 px-4 sm:px-5'>
        <span className='grid size-9 shrink-0 place-items-center rounded-lg bg-muted text-muted-foreground'>
          {icon}
        </span>
        <div className='min-w-0'>
          <p className='text-xs break-words text-muted-foreground'>{label}</p>
          <p
            className={`mt-1 font-mono text-sm font-semibold break-all sm:text-base ${bad ? 'text-destructive' : ''}`}
          >
            {value}
          </p>
        </div>
      </CardContent>
    </Card>
  )
}

export type AgingDatum = ReceivablesReport['aging'][number] & {
  label: string
  chart_ratio: number
}

export function normalizeAging(rows: ReceivablesReport['aging']): AgingDatum[] {
  const values = rows.map((row) => new Decimal(row.outstanding_minor))
  const maximum = values.reduce(
    (current, value) => (value.gt(current) ? value : current),
    new Decimal(0)
  )

  return rows.map((row, index) => ({
    ...row,
    label: bucketLabel(row.bucket),
    // Recharts only receives a bounded visual ratio. The authoritative minor
    // amount remains the decimal string used by the table and tooltip.
    chart_ratio: maximum.isZero()
      ? 0
      : (values[index].isNegative() ? new Decimal(0) : values[index])
          .div(maximum)
          .toNumber(),
  }))
}

function AgingTooltip({
  active,
  payload,
  currency,
}: {
  active?: boolean
  payload?: ReadonlyArray<{ payload?: AgingDatum }>
  currency: string
}) {
  const row = payload?.[0]?.payload
  if (!active || !row) return null

  return (
    <div className='max-w-56 rounded-md border bg-popover px-3 py-2 text-popover-foreground shadow-md'>
      <p className='text-xs text-muted-foreground'>{row.label}</p>
      <p className='mt-1 font-mono text-sm font-semibold break-all'>
        {money(row.outstanding_minor, currency)}
      </p>
    </div>
  )
}

function bucketLabel(value: string) {
  return (
    {
      CURRENT: '未到期',
      '1_30': '1–30 天',
      '31_60': '31–60 天',
      '61_90': '61–90 天',
      OVER_90: '90 天以上',
    }[value] ?? value
  )
}

function Loading() {
  return (
    <div className='grid gap-4 sm:grid-cols-2'>
      {Array.from({ length: 6 }).map((_, index) => (
        <Skeleton key={index} className='h-32' />
      ))}
    </div>
  )
}
