import { useMemo, useState } from 'react'
import {
  useMutation,
  useQuery,
  useQueryClient,
  useSuspenseQuery,
} from '@tanstack/react-query'
import { can } from '@/auth/permission'
import {
  AlertTriangle,
  CheckCircle2,
  Eye,
  FileCheck2,
  RefreshCw,
  Send,
  XCircle,
} from 'lucide-react'
import { toast } from 'sonner'
import { sessionQuery } from '@/api/auth'
import {
  finalizePreview,
  money,
  previewCommand,
  previewDetailQuery,
  previewsQuery,
  recalculatePreview,
  type PreviewSummary,
} from '@/api/operations'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Label } from '@/components/ui/label'
import { Skeleton } from '@/components/ui/skeleton'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { Textarea } from '@/components/ui/textarea'
import { Main } from '@/components/layout/main'
import { ConsoleHeader } from '@/features/shell/console-header'
import { PageHeading } from '@/features/shell/page-heading'

type Command =
  | 'submit-review'
  | 'approve-business'
  | 'approve-finance'
  | 'reject'
  | 'finalize'
  | 'recalculate'

export function PreviewsPage() {
  const queryClient = useQueryClient()
  const { data: session } = useSuspenseQuery(sessionQuery)
  const previews = useQuery(previewsQuery())
  const [selectedId, setSelectedId] = useState<string>()
  const [command, setCommand] = useState<Command>()
  const current = previews.data?.find((item) => item.id === selectedId)
  const detail = useQuery({
    ...previewDetailQuery(selectedId ?? ''),
    enabled: Boolean(selectedId),
  })
  const counts = useMemo(() => {
    const rows = previews.data ?? []
    return {
      review: rows.filter((row) =>
        ['BUSINESS_REVIEW', 'FINANCE_REVIEW'].includes(row.status)
      ).length,
      approved: rows.filter((row) => row.status === 'APPROVED').length,
      blocked: rows.filter((row) => ['ERROR', 'REJECTED'].includes(row.status))
        .length,
    }
  }, [previews.data])

  const commandMutation = useMutation({
    mutationFn: async ({ action, note }: { action: Command; note: string }) => {
      if (!current) throw new Error('未选择预览账单')
      if (action === 'finalize') return finalizePreview(current, note)
      if (action === 'recalculate') return recalculatePreview(current, note)
      return previewCommand(current, action, note)
    },
    onSuccess: async (_result, variables) => {
      toast.success(commandLabel(variables.action) + '已提交')
      setCommand(undefined)
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['invoice-previews'] }),
        queryClient.invalidateQueries({
          queryKey: ['invoice-preview', selectedId],
        }),
        queryClient.invalidateQueries({ queryKey: ['jobs'] }),
      ])
    },
  })

  return (
    <>
      <ConsoleHeader label='previews' />
      <Main className='space-y-7'>
        <PageHeading
          eyebrow='预览与审批'
          title='账单审核工作台'
          description='系统明细保持来源可追溯；金额变化通过调整或排除表达。任何内容变更都会立即撤销已有审批。'
        />
        <div className='grid [grid-template-columns:repeat(auto-fit,minmax(min(100%,13rem),1fr))] gap-4'>
          <Metric
            label='审核中'
            value={counts.review}
            hint='业务或财务队列'
            tone='amber'
          />
          <Metric
            label='可正式化'
            value={counts.approved}
            hint='审批版本与内容版本一致'
            tone='emerald'
          />
          <Metric
            label='需处理'
            value={counts.blocked}
            hint='错误或驳回'
            tone='red'
          />
        </div>
        <Card className='gap-0 overflow-hidden py-0 shadow-none'>
          <CardHeader className='border-b py-5'>
            <CardTitle className='text-base'>预览账单队列</CardTitle>
          </CardHeader>
          <CardContent className='p-0'>
            {previews.isLoading ? (
              <LoadingRows />
            ) : previews.isError ? (
              <EmptyState error />
            ) : !previews.data?.length ? (
              <EmptyState />
            ) : (
              <Table>
                <TableHeader>
                  <TableRow className='bg-muted/30'>
                    <TableHead>预览号</TableHead>
                    <TableHead>账期</TableHead>
                    <TableHead>金额</TableHead>
                    <TableHead>状态</TableHead>
                    <TableHead>版本</TableHead>
                    <TableHead className='text-right'>操作</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {previews.data.map((preview) => (
                    <TableRow key={preview.id}>
                      <TableCell>
                        <p className='font-mono text-xs font-semibold'>
                          {preview.preview_number}
                        </p>
                        <p className='mt-1 text-xs text-muted-foreground'>
                          到期 {preview.due_date}
                        </p>
                      </TableCell>
                      <TableCell className='font-mono text-xs'>
                        {shortDate(preview.period_start)} →{' '}
                        {shortDate(preview.period_end)}
                      </TableCell>
                      <TableCell className='font-mono font-semibold'>
                        {money(preview.total_minor, preview.currency_code)}
                      </TableCell>
                      <TableCell>
                        <StatusBadge status={preview.status} />
                      </TableCell>
                      <TableCell className='font-mono text-xs'>
                        v{preview.version} / 审批 {preview.approval_revision}
                      </TableCell>
                      <TableCell>
                        <div className='flex justify-end gap-2'>
                          <Button
                            size='sm'
                            variant='outline'
                            onClick={() => setSelectedId(preview.id)}
                          >
                            <Eye />
                            明细
                          </Button>
                          {primaryAction(
                            preview,
                            session.permissions,
                            setCommand,
                            setSelectedId
                          )}
                        </div>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
          </CardContent>
        </Card>
      </Main>
      <Dialog
        open={Boolean(selectedId)}
        onOpenChange={(open) => !open && setSelectedId(undefined)}
      >
        <DialogContent className='max-h-[92svh] overflow-y-auto sm:max-w-5xl'>
          <DialogHeader>
            <DialogTitle>预览证据与计算明细</DialogTitle>
            <DialogDescription>
              金额由服务端快照给出，浏览器仅负责安全展示。
            </DialogDescription>
          </DialogHeader>
          {detail.isLoading ? (
            <LoadingRows />
          ) : detail.data ? (
            <div className='space-y-5'>
              <div className='grid gap-3 sm:grid-cols-5'>
                <CompactMetric
                  label='小计'
                  value={money(
                    detail.data.preview.subtotal_minor,
                    detail.data.preview.currency_code
                  )}
                />
                <CompactMetric
                  label='折扣'
                  value={money(
                    detail.data.preview.discount_minor,
                    detail.data.preview.currency_code
                  )}
                />
                <CompactMetric
                  label='税额'
                  value={money(
                    detail.data.preview.tax_minor,
                    detail.data.preview.currency_code
                  )}
                />
                <CompactMetric
                  label='调整'
                  value={money(
                    detail.data.preview.adjustment_minor,
                    detail.data.preview.currency_code
                  )}
                />
                <CompactMetric
                  label='应收'
                  value={money(
                    detail.data.preview.total_minor,
                    detail.data.preview.currency_code
                  )}
                  strong
                />
              </div>
              <div className='overflow-hidden rounded-lg border'>
                <Table>
                  <TableHeader>
                    <TableRow className='bg-muted/30'>
                      <TableHead>#</TableHead>
                      <TableHead>系统明细</TableHead>
                      <TableHead>用量</TableHead>
                      <TableHead>单价</TableHead>
                      <TableHead>金额</TableHead>
                      <TableHead>证据</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {detail.data.items.map((item) => (
                      <TableRow
                        key={item.id}
                        className={item.excluded ? 'opacity-45' : ''}
                      >
                        <TableCell className='font-mono text-xs'>
                          {item.line_no}
                        </TableCell>
                        <TableCell>
                          <p className='font-medium'>{item.item_name}</p>
                          <p className='mt-1 font-mono text-[11px] text-muted-foreground'>
                            {shortDate(item.billing_period_start)} →{' '}
                            {shortDate(item.billing_period_end)}
                          </p>
                        </TableCell>
                        <TableCell className='font-mono text-xs'>
                          {item.billing_usage ?? '—'} {item.unit ?? ''}
                        </TableCell>
                        <TableCell className='font-mono text-xs'>
                          {item.unit_price ?? '—'}
                        </TableCell>
                        <TableCell className='font-mono font-semibold'>
                          {money(
                            item.total_minor,
                            detail.data.preview.currency_code
                          )}
                        </TableCell>
                        <TableCell>
                          {item.excluded ? (
                            <Badge variant='secondary'>已排除</Badge>
                          ) : item.calculation_snapshot.usage_snapshot_id ? (
                            <Badge variant='outline'>用量快照</Badge>
                          ) : (
                            <Badge variant='outline'>合同/价格</Badge>
                          )}
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </div>
              {detail.data.adjustments.length > 0 && (
                <div>
                  <p className='mb-2 text-sm font-semibold'>人工调整</p>
                  <div className='space-y-2'>
                    {detail.data.adjustments.map((item) => (
                      <div
                        key={item.id}
                        className='flex items-center justify-between rounded-lg border px-4 py-3'
                      >
                        <div>
                          <p className='text-sm font-medium'>
                            {item.description}
                          </p>
                          <p className='mt-1 text-xs text-muted-foreground'>
                            {item.adjustment_type} · {item.reason}
                          </p>
                        </div>
                        <span className='font-mono font-semibold'>
                          {money(
                            item.amount_minor,
                            detail.data.preview.currency_code
                          )}
                        </span>
                      </div>
                    ))}
                  </div>
                </div>
              )}
              <div className='flex flex-wrap justify-end gap-2'>
                {current &&
                  !['FINALIZING', 'FINALIZED', 'VOIDED'].includes(
                    current.status
                  ) &&
                  can(session, 'preview.generate') && (
                    <Button
                      variant='outline'
                      onClick={() => setCommand('recalculate')}
                    >
                      <RefreshCw />
                      重新计算
                    </Button>
                  )}
                {current &&
                  primaryButtons(current, session.permissions, setCommand)}
              </div>
            </div>
          ) : (
            <EmptyState error />
          )}
        </DialogContent>
      </Dialog>
      <CommandDialog
        command={command}
        pending={commandMutation.isPending}
        onClose={() => setCommand(undefined)}
        onConfirm={(note) =>
          command && commandMutation.mutate({ action: command, note })
        }
      />
    </>
  )
}

function primaryAction(
  preview: PreviewSummary,
  permissions: string[],
  setCommand: (value: Command) => void,
  setSelectedId: (value: string) => void
) {
  const action = nextAction(preview, permissions)
  if (!action) return null
  return (
    <Button
      size='sm'
      onClick={() => {
        setSelectedId(preview.id)
        setCommand(action)
      }}
    >
      {actionIcon(action)}
      {commandLabel(action)}
    </Button>
  )
}

function primaryButtons(
  preview: PreviewSummary,
  permissions: string[],
  setCommand: (value: Command) => void
) {
  const action = nextAction(preview, permissions)
  if (!action) return null
  return (
    <Button onClick={() => setCommand(action)}>
      {actionIcon(action)}
      {commandLabel(action)}
    </Button>
  )
}

function nextAction(
  preview: PreviewSummary,
  permissions: string[]
): Command | undefined {
  if (
    ['DRAFT', 'REJECTED'].includes(preview.status) &&
    permissions.includes('preview.generate')
  )
    return 'submit-review'
  if (
    preview.status === 'BUSINESS_REVIEW' &&
    permissions.includes('preview.approve.business')
  )
    return 'approve-business'
  if (
    preview.status === 'FINANCE_REVIEW' &&
    permissions.includes('preview.approve.finance')
  )
    return 'approve-finance'
  if (preview.status === 'APPROVED' && permissions.includes('invoice.finalize'))
    return 'finalize'
}

function actionIcon(action: Command) {
  if (action === 'finalize') return <FileCheck2 />
  if (action === 'reject') return <XCircle />
  if (action === 'recalculate') return <RefreshCw />
  if (action.startsWith('approve')) return <CheckCircle2 />
  return <Send />
}

function commandLabel(command: Command) {
  return (
    {
      'submit-review': '提交审核',
      'approve-business': '业务通过',
      'approve-finance': '财务通过',
      reject: '驳回',
      finalize: '正式化',
      recalculate: '重新计算',
    } as const
  )[command]
}

function CommandDialog({
  command,
  pending,
  onClose,
  onConfirm,
}: {
  command?: Command
  pending: boolean
  onClose: () => void
  onConfirm: (note: string) => void
}) {
  const [note, setNote] = useState('')
  return (
    <Dialog
      open={Boolean(command)}
      onOpenChange={(open) => {
        if (!open) {
          setNote('')
          onClose()
        }
      }}
    >
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{command ? commandLabel(command) : ''}</DialogTitle>
          <DialogDescription>
            该操作会写入审计记录。正式化后金额、用量、价格、模板和抬头立即冻结。
          </DialogDescription>
        </DialogHeader>
        <div className='space-y-2'>
          <Label>操作说明</Label>
          <Textarea
            value={note}
            onChange={(event) => setNote(event.target.value)}
            placeholder='输入审核意见或操作原因'
          />
        </div>
        <DialogFooter>
          <Button variant='outline' onClick={onClose}>
            取消
          </Button>
          <Button
            variant={command === 'reject' ? 'destructive' : 'default'}
            disabled={pending || note.trim().length < 2}
            onClick={() => onConfirm(note.trim())}
          >
            {pending ? '正在提交…' : '确认'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function Metric({
  label,
  value,
  hint,
  tone,
}: {
  label: string
  value: number
  hint: string
  tone: 'amber' | 'emerald' | 'red'
}) {
  const colors = {
    amber:
      'border-amber-300/60 bg-amber-50/40 dark:border-amber-900 dark:bg-amber-950/15',
    emerald:
      'border-emerald-300/60 bg-emerald-50/40 dark:border-emerald-900 dark:bg-emerald-950/15',
    red: 'border-red-300/60 bg-red-50/40 dark:border-red-900 dark:bg-red-950/15',
  }
  return (
    <Card className={`${colors[tone]} gap-2 py-5 shadow-none`}>
      <CardContent>
        <p className='text-xs text-muted-foreground'>{label}</p>
        <p className='mt-2 font-mono text-3xl font-semibold'>{value}</p>
        <p className='mt-2 text-xs text-muted-foreground'>{hint}</p>
      </CardContent>
    </Card>
  )
}

function CompactMetric({
  label,
  value,
  strong,
}: {
  label: string
  value: string
  strong?: boolean
}) {
  return (
    <div
      className={`rounded-lg border p-3 ${strong ? 'border-emerald-400/60 bg-emerald-50/40 dark:bg-emerald-950/20' : 'bg-muted/15'}`}
    >
      <p className='text-[11px] text-muted-foreground'>{label}</p>
      <p className='mt-1 font-mono text-sm font-semibold'>{value}</p>
    </div>
  )
}

function StatusBadge({ status }: { status: string }) {
  const destructive = ['ERROR', 'REJECTED', 'VOIDED'].includes(status)
  const positive = ['APPROVED', 'FINALIZED'].includes(status)
  return (
    <Badge
      variant={destructive ? 'destructive' : positive ? 'default' : 'outline'}
    >
      {status}
    </Badge>
  )
}

function LoadingRows() {
  return (
    <div className='space-y-3 p-6'>
      {Array.from({ length: 5 }).map((_, index) => (
        <Skeleton key={index} className='h-12 w-full' />
      ))}
    </div>
  )
}
function EmptyState({ error }: { error?: boolean }) {
  return (
    <div className='grid place-items-center px-6 py-16 text-center'>
      <span className='grid size-12 place-items-center rounded-full bg-muted'>
        {error ? (
          <AlertTriangle className='size-5 text-destructive' />
        ) : (
          <FileCheck2 className='size-5 text-muted-foreground' />
        )}
      </span>
      <p className='mt-4 font-semibold'>
        {error ? '预览数据读取失败' : '暂无预览账单'}
      </p>
      <p className='mt-2 text-sm text-muted-foreground'>
        {error
          ? '请检查会话、权限和 API 状态。'
          : '先在账单配置中选择账期并生成预览。'}
      </p>
    </div>
  )
}
function shortDate(value: string) {
  return value.slice(0, 10)
}
