import { useQuery, useSuspenseQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import Decimal from 'decimal.js'
import {
  Activity,
  AlertTriangle,
  ArrowUpRight,
  Building2,
  FileCheck2,
  FileClock,
  ServerCog,
  ShieldCheck,
  WalletCards,
} from 'lucide-react'
import {
  Bar,
  BarChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
} from 'recharts'
import { sessionQuery } from '@/api/auth'
import {
  dashboardSummaryQuery,
  type DashboardReceivable,
} from '@/api/dashboard'
import { Badge } from '@/components/ui/badge'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { Main } from '@/components/layout/main'
import { ConsoleHeader } from '@/features/shell/console-header'
import { PageHeading } from '@/features/shell/page-heading'
import { dashboardVisibility } from './dashboard-permissions'

const formatter = new Intl.NumberFormat('zh-CN')

type MetricTone = 'neutral' | 'positive' | 'warning' | 'danger'

type MetricDefinition = {
  label: string
  value?: number
  icon: React.ElementType
  tone: MetricTone
  visible: boolean
  chart: boolean
}

export function DashboardPage() {
  const { data: session } = useSuspenseQuery(sessionQuery)
  const summary = useQuery(dashboardSummaryQuery)
  const value = summary.data
  const visibility = dashboardVisibility(session)
  const allMetrics: MetricDefinition[] = [
    {
      label: '客户',
      value: value?.customers,
      icon: Building2,
      tone: 'neutral',
      visible: visibility.customerMetrics,
      chart: true,
    },
    {
      label: '有效业务',
      value: value?.active_services,
      icon: Activity,
      tone: 'positive',
      visible: visibility.customerMetrics,
      chart: true,
    },
    {
      label: '待审核预览',
      value: value?.previews_awaiting_review,
      icon: FileClock,
      tone: 'warning',
      visible: visibility.previewMetrics,
      chart: true,
    },
    {
      label: '正式化中',
      value: value?.invoices_finalizing,
      icon: FileCheck2,
      tone: 'neutral',
      visible: visibility.invoiceMetrics,
      chart: true,
    },
    {
      label: '死信任务',
      value: value?.dead_jobs,
      icon: AlertTriangle,
      tone: value?.dead_jobs ? 'danger' : 'positive',
      visible: visibility.jobMetrics,
      chart: false,
    },
  ]
  const metrics = allMetrics.filter(
    (metric) =>
      metric.visible && (summary.isLoading || metric.value !== undefined)
  )
  const pipeline = metrics
    .filter(
      (metric): metric is MetricDefinition & { value: number } =>
        metric.chart && metric.value !== undefined
    )
    .map((metric) => ({ name: metric.label, value: metric.value }))
  const showPipeline = metrics.some((metric) => metric.chart)
  const showReceivable =
    visibility.receivableMetrics &&
    (summary.isLoading || value?.receivables !== undefined)
  const showOperations = visibility.jobMetrics
  const showSidePanel = showReceivable || showOperations
  const hasDashboardContent = metrics.length > 0 || showSidePanel

  return (
    <>
      <ConsoleHeader label='overview' />
      <Main className='space-y-7'>
        <PageHeading
          eyebrow='账期运行态势'
          title='自动账单作业台'
          description='从用量证据到正式账单，优先处理会阻断出账、正式化与发送的事项。'
          action={
            <Badge variant='outline' className='gap-2 rounded-full px-3 py-1.5'>
              <span className='size-2 rounded-full bg-emerald-500' />每 30
              秒更新
            </Badge>
          }
        />
        {summary.isError ? (
          <DashboardError />
        ) : hasDashboardContent ? (
          <>
            {metrics.length > 0 ? (
              <div className='grid [grid-template-columns:repeat(auto-fit,minmax(min(100%,13rem),1fr))] gap-4'>
                {metrics.map((metric) => (
                  <MetricCard
                    key={metric.label}
                    label={metric.label}
                    value={metric.value}
                    icon={metric.icon}
                    tone={metric.tone}
                    loading={summary.isLoading}
                  />
                ))}
              </div>
            ) : null}
            <div
              className={
                showPipeline && showSidePanel
                  ? 'grid gap-5 xl:grid-cols-[1.45fr_.85fr]'
                  : 'grid gap-5'
              }
            >
              {showPipeline ? (
                <Card className='overflow-hidden border-border/80 shadow-none'>
                  <CardHeader className='border-b bg-muted/20'>
                    <CardTitle>账务管线</CardTitle>
                    <CardDescription>
                      仅展示当前账号获授权的租户内指标，不包含跨租户汇总。
                    </CardDescription>
                  </CardHeader>
                  <CardContent className='h-72 pt-7'>
                    {summary.isLoading ? (
                      <Skeleton className='h-full w-full' />
                    ) : pipeline.length > 0 ? (
                      <ResponsiveContainer
                        width='100%'
                        height='100%'
                        initialDimension={{ width: 1, height: 1 }}
                      >
                        <BarChart
                          data={pipeline}
                          margin={{ top: 8, right: 8, left: 0, bottom: 0 }}
                        >
                          <CartesianGrid
                            strokeDasharray='3 3'
                            vertical={false}
                            opacity={0.25}
                          />
                          <XAxis
                            dataKey='name'
                            axisLine={false}
                            tickLine={false}
                            fontSize={12}
                          />
                          <Tooltip
                            cursor={{ fill: 'var(--muted)', opacity: 0.5 }}
                            contentStyle={{
                              borderRadius: 8,
                              borderColor: 'var(--border)',
                              background: 'var(--card)',
                            }}
                          />
                          <Bar
                            dataKey='value'
                            fill='oklch(0.49 0.12 170)'
                            radius={[5, 5, 0, 0]}
                            maxBarSize={52}
                          />
                        </BarChart>
                      </ResponsiveContainer>
                    ) : (
                      <ChartEmpty />
                    )}
                  </CardContent>
                </Card>
              ) : null}
              {showSidePanel ? (
                <div className='grid gap-5'>
                  {showReceivable ? (
                    <Card className='border-border/80 bg-[#0a1714] text-white shadow-none dark:bg-emerald-950/50'>
                      <CardHeader>
                        <CardDescription className='text-emerald-100/60'>
                          未结应收
                        </CardDescription>
                        <CardTitle className='text-base font-medium text-emerald-50'>
                          按币种列示可收余额
                        </CardTitle>
                      </CardHeader>
                      <CardContent className='space-y-4'>
                        {summary.isLoading ? (
                          <div className='space-y-3'>
                            <Skeleton className='h-8 w-full bg-white/10' />
                            <Skeleton className='h-8 w-4/5 bg-white/10' />
                          </div>
                        ) : value?.receivables?.length ? (
                          <div
                            className='max-h-44 space-y-2 overflow-y-auto pr-1'
                            aria-label='按币种列示的未结应收'
                          >
                            {value.receivables.map((receivable) => (
                              <div
                                key={receivable.currency_code}
                                className='flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 border-b border-white/10 pb-2 last:border-0 last:pb-0'
                              >
                                <span className='text-xs font-semibold tracking-[0.12em] text-emerald-100/55'>
                                  {receivable.currency_code}
                                </span>
                                <span className='text-right font-mono text-xl font-semibold tracking-tight break-all sm:text-2xl'>
                                  {formatReceivable(receivable)}
                                </span>
                              </div>
                            ))}
                          </div>
                        ) : (
                          <p className='text-sm text-emerald-100/60'>
                            当前没有未结应收余额。
                          </p>
                        )}
                        <div className='flex items-center justify-between text-xs text-emerald-100/55'>
                          <span>已扣除有效付款分配，不跨币种相加</span>
                          <WalletCards className='size-5 shrink-0 text-emerald-300' />
                        </div>
                      </CardContent>
                    </Card>
                  ) : null}
                  {showOperations ? (
                    <Card className='border-amber-300/50 bg-amber-50/60 shadow-none dark:border-amber-800/50 dark:bg-amber-950/20'>
                      <CardHeader>
                        <CardTitle className='flex items-center gap-2 text-base'>
                          <ServerCog className='size-4 text-amber-700' />
                          运行提醒
                        </CardTitle>
                        <CardDescription>
                          死信、长时间 FINALIZING 与用量证据缺失会阻断自动发送。
                        </CardDescription>
                      </CardHeader>
                      <CardContent>
                        <Link
                          className='inline-flex items-center gap-1 text-sm font-semibold text-amber-800 hover:underline dark:text-amber-300'
                          to='/jobs'
                        >
                          查看任务与审计
                          <ArrowUpRight className='size-3.5' />
                        </Link>
                      </CardContent>
                    </Card>
                  ) : null}
                </div>
              ) : null}
            </div>
          </>
        ) : (
          <DashboardEmpty />
        )}
      </Main>
    </>
  )
}

function MetricCard({
  label,
  value,
  icon: Icon,
  tone,
  loading,
}: {
  label: string
  value?: number
  icon: React.ElementType
  tone: MetricTone
  loading: boolean
}) {
  const tones = {
    neutral:
      'bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-200',
    positive:
      'bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300',
    warning:
      'bg-amber-100 text-amber-800 dark:bg-amber-950 dark:text-amber-300',
    danger: 'bg-red-100 text-red-800 dark:bg-red-950 dark:text-red-300',
  }
  return (
    <Card className='gap-4 border-border/80 py-5 shadow-none'>
      <CardHeader className='flex grid-cols-none flex-row items-center justify-between px-5'>
        <CardDescription className='font-medium'>{label}</CardDescription>
        <span
          className={`grid size-8 place-items-center rounded-md ${tones[tone]}`}
        >
          <Icon className='size-4' />
        </span>
      </CardHeader>
      <CardContent className='px-5'>
        {loading ? (
          <Skeleton className='h-9 w-20' />
        ) : (
          <p className='font-mono text-3xl font-semibold tracking-tight'>
            {formatter.format(value ?? 0)}
          </p>
        )}
      </CardContent>
    </Card>
  )
}

function ChartEmpty() {
  return (
    <div className='flex h-full items-center justify-center px-6 text-center text-sm text-muted-foreground'>
      当前权限范围内暂无可绘制的账务指标。
    </div>
  )
}

function DashboardEmpty() {
  return (
    <Card className='border-dashed border-border/80 shadow-none'>
      <CardContent className='flex min-h-40 flex-col items-center justify-center gap-3 px-6 py-8 text-center'>
        <span className='grid size-10 place-items-center rounded-md bg-muted text-muted-foreground'>
          <ShieldCheck className='size-5' />
        </span>
        <p className='max-w-xl text-sm leading-6 text-muted-foreground'>
          当前账号没有可展示的总览指标，请从左侧导航进入获授权的工作区。
        </p>
      </CardContent>
    </Card>
  )
}

function DashboardError() {
  return (
    <Card className='border-destructive/40 shadow-none'>
      <CardHeader>
        <CardTitle>暂时无法读取运行态势</CardTitle>
        <CardDescription>
          请检查 API、数据库连接和当前会话。页面不会使用假数据代替账务事实。
        </CardDescription>
      </CardHeader>
    </Card>
  )
}

function formatReceivable(receivable: DashboardReceivable) {
  const amount = new Decimal(receivable.outstanding_minor)
    .div(new Decimal(10).pow(receivable.minor_unit))
    .toDecimalPlaces(receivable.minor_unit)
    .toFixed(receivable.minor_unit)
  return `${receivable.currency_symbol || receivable.currency_code} ${amount}`
}
