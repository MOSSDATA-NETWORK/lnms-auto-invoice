import { useMemo, useState } from 'react'
import {
  useMutation,
  useQuery,
  useQueryClient,
  useSuspenseQuery,
} from '@tanstack/react-query'
import { can } from '@/auth/permission'
import { Activity, CircleCheck, Clock3, RotateCcw, Skull } from 'lucide-react'
import { toast } from 'sonner'
import { sessionQuery } from '@/api/auth'
import { jobsQuery, retryJob, type Job } from '@/api/operations'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
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

export function JobsPage() {
  const queryClient = useQueryClient()
  const { data: session } = useSuspenseQuery(sessionQuery)
  const [status, setStatus] = useState<string>()
  const jobs = useQuery(jobsQuery(status))
  const counts = useMemo(
    () => ({
      pending:
        jobs.data?.filter((job) =>
          ['PENDING', 'LEASED', 'RETRY'].includes(job.status)
        ).length ?? 0,
      dead: jobs.data?.filter((job) => job.status === 'DEAD').length ?? 0,
      complete:
        jobs.data?.filter((job) => job.status === 'COMPLETED').length ?? 0,
    }),
    [jobs.data]
  )
  const retry = useMutation({
    mutationFn: (job: Job) => retryJob(job, '管理员从任务控制台重新入队'),
    onSuccess: async () => {
      toast.success('任务已重新入队')
      await queryClient.invalidateQueries({ queryKey: ['jobs'] })
    },
  })
  return (
    <>
      <ConsoleHeader label='jobs' />
      <Main className='space-y-7'>
        <PageHeading
          eyebrow='平台运行'
          title='持久任务与恢复控制台'
          description='Quartz 只触发轮询；PostgreSQL 任务租约、重试次数、结果和死信记录才是可恢复事实来源。'
        />
        <div className='grid [grid-template-columns:repeat(auto-fit,minmax(min(100%,13rem),1fr))] gap-4'>
          <Signal icon={<Clock3 />} label='运行/等待' value={counts.pending} />
          <Signal icon={<Skull />} label='死信' value={counts.dead} danger />
          <Signal
            icon={<CircleCheck />}
            label='已完成（当前筛选）'
            value={counts.complete}
          />
        </div>
        <div className='flex flex-wrap gap-2'>
          {[undefined, 'PENDING', 'LEASED', 'RETRY', 'DEAD', 'COMPLETED'].map(
            (value) => (
              <Button
                key={value ?? 'ALL'}
                size='sm'
                variant={status === value ? 'default' : 'outline'}
                onClick={() => setStatus(value)}
              >
                {value ?? '全部'}
              </Button>
            )
          )}
        </div>
        <div className='overflow-hidden rounded-xl border bg-card'>
          {jobs.isLoading ? (
            <Loading />
          ) : !jobs.data?.length ? (
            <div className='grid place-items-center py-16 text-center'>
              <Activity className='size-7 text-muted-foreground' />
              <p className='mt-4 font-semibold'>当前筛选无任务</p>
            </div>
          ) : (
            <Table>
              <TableHeader>
                <TableRow className='bg-muted/30'>
                  <TableHead>任务</TableHead>
                  <TableHead>状态</TableHead>
                  <TableHead>尝试</TableHead>
                  <TableHead>创建/完成</TableHead>
                  <TableHead>结果或错误</TableHead>
                  <TableHead className='text-right'>恢复</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {jobs.data.map((job) => (
                  <TableRow key={job.id}>
                    <TableCell>
                      <p className='font-mono text-xs font-semibold'>
                        {job.type}
                      </p>
                      <p className='mt-1 font-mono text-[11px] text-muted-foreground'>
                        {job.id}
                      </p>
                    </TableCell>
                    <TableCell>
                      <Status status={job.status} />
                    </TableCell>
                    <TableCell className='font-mono text-xs'>
                      {job.attempt_count} / {job.max_attempts}
                    </TableCell>
                    <TableCell className='font-mono text-[11px]'>
                      <p>{job.created_at.slice(0, 16).replace('T', ' ')}</p>
                      <p className='mt-1 text-muted-foreground'>
                        {job.completed_at?.slice(0, 16).replace('T', ' ') ??
                          '—'}
                      </p>
                    </TableCell>
                    <TableCell className='max-w-80'>
                      <p
                        className='truncate text-xs'
                        title={
                          job.last_error_message ?? JSON.stringify(job.result)
                        }
                      >
                        {job.last_error_code
                          ? `${job.last_error_code}: ${job.last_error_message}`
                          : job.result
                            ? JSON.stringify(job.result)
                            : '等待执行'}
                      </p>
                    </TableCell>
                    <TableCell className='text-right'>
                      {can(session, 'system.admin') &&
                        ['DEAD', 'RETRY'].includes(job.status) && (
                          <Button
                            size='sm'
                            variant='outline'
                            disabled={retry.isPending}
                            onClick={() => retry.mutate(job)}
                          >
                            <RotateCcw />
                            重试
                          </Button>
                        )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </div>
      </Main>
    </>
  )
}

function Signal({
  icon,
  label,
  value,
  danger,
}: {
  icon: React.ReactNode
  label: string
  value: number
  danger?: boolean
}) {
  return (
    <Card
      className={`py-5 shadow-none ${danger && value > 0 ? 'border-red-300/60 bg-red-50/40 dark:border-red-900 dark:bg-red-950/20' : ''}`}
    >
      <CardContent className='flex items-center gap-4'>
        <span className='grid size-10 place-items-center rounded-lg bg-muted text-muted-foreground'>
          {icon}
        </span>
        <div>
          <p className='text-xs text-muted-foreground'>{label}</p>
          <p className='mt-1 font-mono text-2xl font-semibold'>{value}</p>
        </div>
      </CardContent>
    </Card>
  )
}
function Status({ status }: { status: string }) {
  return (
    <Badge
      variant={
        status === 'DEAD'
          ? 'destructive'
          : status === 'COMPLETED'
            ? 'default'
            : 'outline'
      }
    >
      {status}
    </Badge>
  )
}
function Loading() {
  return (
    <div className='space-y-3 p-6'>
      {Array.from({ length: 6 }).map((_, index) => (
        <Skeleton key={index} className='h-12' />
      ))}
    </div>
  )
}
