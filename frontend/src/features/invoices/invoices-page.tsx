import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Ban,
  Download,
  Eye,
  FileCheck2,
  FileClock,
  FileSpreadsheet,
  RotateCcw,
  Send,
  WalletCards,
} from 'lucide-react'
import { toast } from 'sonner'
import { problemFrom } from '@/api/http'
import {
  createReplacementPreview,
  downloadFile,
  invoiceDetailQuery,
  invoicesQuery,
  money,
  renderInvoiceExcel,
  sendInvoice,
  voidInvoice,
  webhookEndpointsQuery,
  type InvoiceSummary,
} from '@/api/operations'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
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
import { Main } from '@/components/layout/main'
import { ConsoleHeader } from '@/features/shell/console-header'
import { PageHeading } from '@/features/shell/page-heading'

export function InvoicesPage() {
  const queryClient = useQueryClient()
  const invoices = useQuery(invoicesQuery)
  const endpoints = useQuery(webhookEndpointsQuery)
  const [sending, setSending] = useState<InvoiceSummary>()
  const [selected, setSelected] = useState<InvoiceSummary>()
  const detail = useQuery(invoiceDetailQuery(selected?.id))
  const [correctionReason, setCorrectionReason] = useState('')
  const [emails, setEmails] = useState('')
  const [selectedEndpoints, setSelectedEndpoints] = useState<string[]>([])
  const sendMutation = useMutation({
    mutationFn: () =>
      sendInvoice(sending!, {
        emails: emails
          .split(/[;,\n]/)
          .map((value) => value.trim())
          .filter(Boolean),
        webhook_endpoint_ids: selectedEndpoints,
        reason: '从正式账单页面发送',
      }),
    onSuccess: async () => {
      toast.success('发送任务已进入持久队列')
      setSending(undefined)
      setEmails('')
      setSelectedEndpoints([])
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['invoices'] }),
        queryClient.invalidateQueries({ queryKey: ['notification-logs'] }),
        queryClient.invalidateQueries({ queryKey: ['jobs'] }),
      ])
    },
  })
  const voidMutation = useMutation({
    mutationFn: () => voidInvoice(selected!, correctionReason),
    onSuccess: async (result) => {
      toast.success('正式账单已作废；冻结内容保持不变')
      setSelected((current) =>
        current
          ? {
              ...current,
              document_status: result.document_status,
              version: result.version,
            }
          : current
      )
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['invoices'] }),
        queryClient.invalidateQueries({ queryKey: ['invoice', selected?.id] }),
      ])
    },
  })
  const replacementMutation = useMutation({
    mutationFn: () => createReplacementPreview(selected!, correctionReason),
    onSuccess: async (result) => {
      toast.success(`更正预览已创建：${result.preview_number}`)
      setSelected(undefined)
      setCorrectionReason('')
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['invoices'] }),
        queryClient.invalidateQueries({ queryKey: ['invoice-previews'] }),
      ])
    },
  })
  const excelMutation = useMutation({
    mutationFn: (invoice: InvoiceSummary) => renderInvoiceExcel(invoice.id),
    onSuccess: async (file) => {
      toast.success(`Excel 账单已生成：${file.filename}`)
      await downloadFile(file.id, file.filename)
    },
    onError: (error) => {
      const problem = problemFrom(error)
      toast.error(problem.detail ?? problem.title ?? '生成 Excel 失败')
    },
  })
  const counts = useMemo(
    () => ({
      finalizing:
        invoices.data?.filter((row) => row.document_status === 'FINALIZING')
          .length ?? 0,
      sent:
        invoices.data?.filter((row) => row.send_status === 'SENT').length ?? 0,
      unpaid:
        invoices.data?.filter((row) =>
          ['UNPAID', 'PARTIALLY_PAID', 'OVERDUE'].includes(row.payment_status)
        ).length ?? 0,
    }),
    [invoices.data]
  )
  return (
    <>
      <ConsoleHeader label='invoices' />
      <Main className='space-y-7'>
        <PageHeading
          eyebrow='正式账单'
          title='冻结账单与文件状态'
          description='正式账单只读。更正必须作废原账单、复制为新预览、重新审核并产生新的正式账单。'
        />
        <div className='grid [grid-template-columns:repeat(auto-fit,minmax(min(100%,13rem),1fr))] gap-4'>
          <Signal
            icon={<FileClock />}
            label='PDF 正式化中'
            value={counts.finalizing}
          />
          <Signal icon={<Send />} label='已发送' value={counts.sent} />
          <Signal
            icon={<WalletCards />}
            label='未结应收'
            value={counts.unpaid}
          />
        </div>
        <div className='overflow-hidden rounded-xl border bg-card'>
          {invoices.isLoading ? (
            <Loading />
          ) : !invoices.data?.length ? (
            <div className='grid place-items-center py-16 text-center'>
              <FileCheck2 className='size-7 text-muted-foreground' />
              <p className='mt-4 font-semibold'>尚无正式账单</p>
              <p className='mt-2 text-sm text-muted-foreground'>
                完成业务与财务审批后，从预览工作台正式化。
              </p>
            </div>
          ) : (
            <Table>
              <TableHeader>
                <TableRow className='bg-muted/30'>
                  <TableHead>正式编号</TableHead>
                  <TableHead>开票/到期</TableHead>
                  <TableHead>金额</TableHead>
                  <TableHead>文档</TableHead>
                  <TableHead>发送</TableHead>
                  <TableHead>付款</TableHead>
                  <TableHead>冻结版本</TableHead>
                  <TableHead />
                </TableRow>
              </TableHeader>
              <TableBody>
                {invoices.data.map((invoice) => (
                  <TableRow key={invoice.id}>
                    <TableCell>
                      <p className='font-mono text-xs font-semibold'>
                        {invoice.invoice_number}
                      </p>
                      <p className='mt-1 font-mono text-[11px] text-muted-foreground'>
                        来源 {invoice.source_preview_id.slice(0, 8)}
                      </p>
                    </TableCell>
                    <TableCell className='font-mono text-xs'>
                      {invoice.issue_date} → {invoice.due_date}
                    </TableCell>
                    <TableCell className='font-mono font-semibold'>
                      {money(invoice.total_minor, invoice.currency_code)}
                    </TableCell>
                    <TableCell>
                      <State value={invoice.document_status} />
                    </TableCell>
                    <TableCell>
                      <State value={invoice.send_status} />
                    </TableCell>
                    <TableCell>
                      <State value={invoice.payment_status} />
                    </TableCell>
                    <TableCell className='font-mono text-xs'>
                      v{invoice.version}
                    </TableCell>
                    <TableCell>
                      <div className='flex gap-2'>
                        <Button
                          size='sm'
                          variant='ghost'
                          onClick={() => {
                            setSelected(invoice)
                            setCorrectionReason('')
                          }}
                        >
                          <Eye />
                          详情
                        </Button>
                        <Button
                          size='sm'
                          variant='outline'
                          disabled={
                            !['CONFIRMED', 'SENT'].includes(
                              invoice.document_status
                            )
                          }
                          onClick={() => setSending(invoice)}
                        >
                          <Send />
                          发送
                        </Button>
                      </div>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </div>
      </Main>
      <Dialog
        open={Boolean(sending)}
        onOpenChange={(open) => !open && setSending(undefined)}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>发送正式账单</DialogTitle>
            <DialogDescription>
              邮件将附带冻结 PDF；Webhook 使用稳定事件 ID 和 HMAC-SHA256 签名。
            </DialogDescription>
          </DialogHeader>
          <div className='space-y-5'>
            <div className='space-y-2'>
              <Label>收件邮箱</Label>
              <Input
                value={emails}
                onChange={(event) => setEmails(event.target.value)}
                placeholder='finance@example.com；多个邮箱用分号分隔'
              />
            </div>
            <div className='space-y-2'>
              <Label>Webhook 端点</Label>
              <div className='grid gap-2'>
                {endpoints.data
                  ?.filter((endpoint) => endpoint.status === 'ACTIVE')
                  .map((endpoint) => (
                    <label
                      key={endpoint.id}
                      className='flex items-center gap-3 rounded-lg border p-3 text-sm'
                    >
                      <input
                        type='checkbox'
                        checked={selectedEndpoints.includes(endpoint.id)}
                        onChange={(event) =>
                          setSelectedEndpoints((current) =>
                            event.target.checked
                              ? [...current, endpoint.id]
                              : current.filter((id) => id !== endpoint.id)
                          )
                        }
                      />
                      <span>
                        <strong>{endpoint.endpoint_name}</strong>
                        <span className='ms-2 font-mono text-xs text-muted-foreground'>
                          {endpoint.endpoint_code}
                        </span>
                      </span>
                    </label>
                  ))}
              </div>
            </div>
          </div>
          <DialogFooter>
            <Button variant='outline' onClick={() => setSending(undefined)}>
              取消
            </Button>
            <Button
              disabled={
                sendMutation.isPending ||
                (!emails.trim() && selectedEndpoints.length === 0)
              }
              onClick={() => sendMutation.mutate()}
            >
              {sendMutation.isPending ? '正在排队…' : '确认发送'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
      <Dialog
        open={Boolean(selected)}
        onOpenChange={(open) => !open && setSelected(undefined)}
      >
        <DialogContent className='max-h-[calc(100svh-2rem)] overflow-y-auto sm:max-w-4xl'>
          <DialogHeader>
            <DialogTitle className='font-mono'>
              {selected?.invoice_number}
            </DialogTitle>
            <DialogDescription>
              冻结金额、用量、价格和模板不可修改。作废后只能复制成新预览并重新审批。
            </DialogDescription>
          </DialogHeader>
          {detail.isLoading || !detail.data ? (
            <Loading />
          ) : (
            <div className='space-y-5'>
              <div className='grid gap-3 sm:grid-cols-4'>
                <Metric
                  label='总额'
                  value={money(
                    detail.data.invoice.total_minor,
                    detail.data.invoice.currency_code
                  )}
                />
                <Metric
                  label='文档'
                  value={detail.data.invoice.document_status}
                />
                <Metric label='发送' value={detail.data.invoice.send_status} />
                <Metric
                  label='付款'
                  value={detail.data.invoice.payment_status}
                />
              </div>
              <div className='rounded-lg border'>
                <Table>
                  <TableHeader>
                    <TableRow className='bg-muted/30'>
                      <TableHead>#</TableHead>
                      <TableHead>冻结明细</TableHead>
                      <TableHead>用量</TableHead>
                      <TableHead className='text-right'>金额</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {detail.data.items.map((item) => (
                      <TableRow key={item.id}>
                        <TableCell className='font-mono text-xs'>
                          {item.line_no}
                        </TableCell>
                        <TableCell>{item.item_name}</TableCell>
                        <TableCell className='font-mono text-xs'>
                          {item.billing_usage ?? '—'} {item.unit ?? ''}
                        </TableCell>
                        <TableCell className='text-right font-mono'>
                          {money(
                            item.total_minor,
                            detail.data.invoice.currency_code
                          )}
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </div>
              <div className='rounded-lg border bg-muted/20 p-4'>
                <p className='text-xs text-muted-foreground'>冻结快照哈希</p>
                <p className='mt-2 font-mono text-xs break-all'>
                  {detail.data.invoice.data_snapshot_hash}
                </p>
                {detail.data.files.map((file) => (
                  <div key={file.id} className='mt-3 border-t pt-3 text-xs'>
                    <span className='font-medium'>{file.file_role}</span>
                    <span className='ms-2 font-mono text-muted-foreground'>
                      {file.content_sha256}
                    </span>
                    <p className='mt-1 text-muted-foreground'>
                      {file.renderer_version ?? '—'} /{' '}
                      {file.chromium_version ?? '—'}
                    </p>
                  </div>
                ))}
              </div>
              {detail.data.relations.length > 0 && (
                <div className='rounded-lg border p-4 text-sm'>
                  <p className='font-medium'>更正关系</p>
                  {detail.data.relations.map((relation) => (
                    <p key={relation.id} className='mt-2 text-muted-foreground'>
                      {relation.source_invoice_number} {relation.relation_type}{' '}
                      {relation.target_invoice_number}
                    </p>
                  ))}
                </div>
              )}
              {['FINALIZING', 'CONFIRMED', 'SENT', 'VOIDED'].includes(
                selected?.document_status ?? ''
              ) && (
                <div className='space-y-2'>
                  <Label>作废/更正原因</Label>
                  <Input
                    value={correctionReason}
                    onChange={(event) =>
                      setCorrectionReason(event.target.value)
                    }
                    placeholder='说明错误来源和财务处理依据'
                  />
                </div>
              )}
            </div>
          )}
          <DialogFooter className='gap-2 sm:justify-between'>
            <div className='flex gap-2'>
              {detail.data?.files.some((file) => file.file_role === 'PDF') && (
                <Button variant='outline' asChild>
                  <a href={`/api/v1/invoices/${selected?.id}/pdf`}>
                    <Download />
                    下载 PDF
                  </a>
                </Button>
              )}
              {selected && (
                <Button
                  variant='outline'
                  disabled={excelMutation.isPending}
                  onClick={() => excelMutation.mutate(selected)}
                >
                  <FileSpreadsheet />
                  下载 Excel
                </Button>
              )}
            </div>
            <div className='flex gap-2'>
              {selected?.document_status === 'VOIDED' ? (
                <Button
                  disabled={
                    replacementMutation.isPending || correctionReason.length < 2
                  }
                  onClick={() => replacementMutation.mutate()}
                >
                  <RotateCcw />
                  创建更正预览
                </Button>
              ) : (
                <Button
                  variant='destructive'
                  disabled={
                    voidMutation.isPending ||
                    correctionReason.length < 2 ||
                    !['FINALIZING', 'CONFIRMED', 'SENT'].includes(
                      selected?.document_status ?? ''
                    )
                  }
                  onClick={() => voidMutation.mutate()}
                >
                  <Ban />
                  作废账单
                </Button>
              )}
            </div>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  )
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div className='rounded-lg border bg-muted/20 p-3'>
      <p className='text-xs text-muted-foreground'>{label}</p>
      <p className='mt-1 font-mono text-sm font-semibold'>{value}</p>
    </div>
  )
}

function Signal({
  icon,
  label,
  value,
}: {
  icon: React.ReactNode
  label: string
  value: number
}) {
  return (
    <Card className='py-5 shadow-none'>
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
function State({ value }: { value: string }) {
  const bad = ['FAILED', 'VOIDED', 'OVERDUE'].includes(value)
  const good = ['CONFIRMED', 'SENT', 'PAID'].includes(value)
  return (
    <Badge variant={bad ? 'destructive' : good ? 'default' : 'outline'}>
      {value}
    </Badge>
  )
}
function Loading() {
  return (
    <div className='space-y-3 p-6'>
      {Array.from({ length: 5 }).map((_, index) => (
        <Skeleton key={index} className='h-12' />
      ))}
    </div>
  )
}
