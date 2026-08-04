import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import Decimal from 'decimal.js'
import {
  CircleDollarSign,
  Landmark,
  Plus,
  Undo2,
  WalletCards,
} from 'lucide-react'
import { toast } from 'sonner'
import { customersQuery } from '@/api/customers'
import {
  allocatePayment,
  isValidPositiveAmount,
  invoicesQuery,
  money,
  paymentDetailQuery,
  paymentsQuery,
  recordPayment,
  refundPayment,
  reversePaymentAllocation,
  toPositiveMinorUnits,
  type Payment,
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
import { Textarea } from '@/components/ui/textarea'
import { Main } from '@/components/layout/main'
import { ConsoleHeader } from '@/features/shell/console-header'
import { PageHeading } from '@/features/shell/page-heading'

export function PaymentsPage() {
  const queryClient = useQueryClient()
  const payments = useQuery(paymentsQuery)
  const invoices = useQuery(invoicesQuery)
  const [open, setOpen] = useState(false)
  const [selected, setSelected] = useState<Payment>()
  const detail = useQuery(paymentDetailQuery(selected?.id))
  const [invoiceId, setInvoiceId] = useState('')
  const [allocationAmount, setAllocationAmount] = useState('')
  const [refundAmount, setRefundAmount] = useState('')
  const [operationReason, setOperationReason] = useState('人工核对付款流水')
  const allocation = useMutation({
    mutationFn: () =>
      allocatePayment(
        selected!,
        invoiceId,
        toPositiveMinorUnits(allocationAmount, selected!.currency_code),
        operationReason
      ),
    onSuccess: async () => {
      toast.success('付款已分配到账单')
      setSelected(undefined)
      setInvoiceId('')
      setAllocationAmount('')
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['payments'] }),
        queryClient.invalidateQueries({ queryKey: ['invoices'] }),
      ])
    },
  })
  const refund = useMutation({
    mutationFn: () =>
      refundPayment(
        selected!,
        toPositiveMinorUnits(refundAmount, selected!.currency_code),
        operationReason
      ),
    onSuccess: async () => {
      toast.success('退款流水已记录')
      setSelected(undefined)
      setRefundAmount('')
      await queryClient.invalidateQueries({ queryKey: ['payments'] })
    },
  })
  const reverse = useMutation({
    mutationFn: (allocationId: string) =>
      reversePaymentAllocation(selected!, allocationId, operationReason),
    onSuccess: async () => {
      toast.success('付款分配已冲销')
      setSelected(undefined)
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['payments'] }),
        queryClient.invalidateQueries({ queryKey: ['invoices'] }),
      ])
    },
  })
  const totals = useMemo(
    () => ({
      count: payments.data?.length ?? 0,
      allocated:
        payments.data
          ?.reduce((sum, row) => sum.plus(row.allocated_minor), new Decimal(0))
          .toFixed(0) ?? '0',
      refundable:
        payments.data
          ?.reduce(
            (sum, row) =>
              sum.plus(
                new Decimal(row.amount_minor)
                  .minus(row.allocated_minor)
                  .minus(row.refunded_minor)
              ),
            new Decimal(0)
          )
          .toFixed(0) ?? '0',
    }),
    [payments.data]
  )
  return (
    <>
      <ConsoleHeader label='payments' />
      <Main className='space-y-7'>
        <PageHeading
          eyebrow='应收与付款'
          title='付款、分配与退款流水'
          description='付款事实、账单分配和退款分别留痕；状态由有效分配与退款流水派生，不直接手工覆盖。'
          action={
            <Button onClick={() => setOpen(true)}>
              <Plus />
              记录付款
            </Button>
          }
        />
        <div className='grid [grid-template-columns:repeat(auto-fit,minmax(min(100%,13rem),1fr))] gap-4'>
          <Signal
            icon={<WalletCards />}
            label='当前记录'
            value={String(totals.count)}
          />
          <Signal
            icon={<Landmark />}
            label='已分配（跨币种汇总仅供计数）'
            value={String(totals.allocated)}
          />
          <Signal
            icon={<Undo2 />}
            label='未分配/可退款最小单位'
            value={String(totals.refundable)}
          />
        </div>
        <div className='overflow-hidden rounded-xl border bg-card'>
          {payments.isLoading ? (
            <Loading />
          ) : !payments.data?.length ? (
            <div className='grid place-items-center py-16 text-center'>
              <CircleDollarSign className='size-7 text-muted-foreground' />
              <p className='mt-4 font-semibold'>暂无付款记录</p>
              <p className='mt-2 text-sm text-muted-foreground'>
                记录到账后，再分配到已确认或已发送的正式账单。
              </p>
            </div>
          ) : (
            <Table>
              <TableHeader>
                <TableRow className='bg-muted/30'>
                  <TableHead>付款号</TableHead>
                  <TableHead>到账</TableHead>
                  <TableHead>金额</TableHead>
                  <TableHead>已分配</TableHead>
                  <TableHead>已退款</TableHead>
                  <TableHead>状态</TableHead>
                  <TableHead />
                </TableRow>
              </TableHeader>
              <TableBody>
                {payments.data.map((payment) => (
                  <TableRow key={payment.id}>
                    <TableCell>
                      <p className='font-mono text-xs font-semibold'>
                        {payment.payment_number}
                      </p>
                      <p className='mt-1 text-xs text-muted-foreground'>
                        {payment.payment_method}
                        {payment.external_reference
                          ? ` · ${payment.external_reference}`
                          : ''}
                      </p>
                    </TableCell>
                    <TableCell className='font-mono text-xs'>
                      {payment.paid_at.slice(0, 16).replace('T', ' ')}
                    </TableCell>
                    <TableCell className='font-mono font-semibold'>
                      {money(payment.amount_minor, payment.currency_code)}
                    </TableCell>
                    <TableCell className='font-mono text-xs'>
                      {money(payment.allocated_minor, payment.currency_code)}
                    </TableCell>
                    <TableCell className='font-mono text-xs'>
                      {money(payment.refunded_minor, payment.currency_code)}
                    </TableCell>
                    <TableCell>
                      <Badge
                        variant={
                          payment.status === 'CONFIRMED' ? 'outline' : 'default'
                        }
                      >
                        {payment.status}
                      </Badge>
                    </TableCell>
                    <TableCell>
                      <Button
                        size='sm'
                        variant='outline'
                        onClick={() => {
                          setSelected(payment)
                          setInvoiceId('')
                          setAllocationAmount('')
                          setRefundAmount('')
                        }}
                      >
                        分配/退款
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </div>
      </Main>
      <PaymentDialog open={open} onClose={() => setOpen(false)} />
      <Dialog
        open={Boolean(selected)}
        onOpenChange={(value) => !value && setSelected(undefined)}
      >
        <DialogContent className='max-h-[calc(100svh-2rem)] min-w-0 overflow-x-auto overflow-y-auto sm:max-w-3xl'>
          <DialogHeader>
            <DialogTitle className='pe-6 font-mono break-all'>
              {selected?.payment_number}
            </DialogTitle>
            <DialogDescription>
              分配不能超过付款可用余额或账单未付金额；退款只能使用未分配、未退款余额。
            </DialogDescription>
          </DialogHeader>
          {!selected || detail.isLoading || !detail.data ? (
            <Loading />
          ) : (
            <div className='min-w-0 space-y-5'>
              <div className='grid min-w-0 gap-3 sm:grid-cols-3'>
                <Metric
                  label='付款金额'
                  value={money(selected.amount_minor, selected.currency_code)}
                />
                <Metric
                  label='已分配'
                  value={money(
                    detail.data.payment.allocated_minor,
                    selected.currency_code
                  )}
                />
                <Metric
                  label='可退款余额'
                  value={money(
                    new Decimal(selected.amount_minor)
                      .minus(detail.data.payment.allocated_minor)
                      .minus(detail.data.payment.refunded_minor),
                    selected.currency_code
                  )}
                />
              </div>
              <div className='grid min-w-0 gap-5 md:grid-cols-2'>
                <div className='min-w-0 space-y-3 rounded-lg border p-4'>
                  <p className='font-medium'>分配到正式账单</p>
                  <select
                    value={invoiceId}
                    onChange={(event) => setInvoiceId(event.target.value)}
                    className='h-9 max-w-full min-w-0 rounded-md border bg-background px-3 text-sm'
                  >
                    <option value=''>请选择同币种未结账单</option>
                    {invoices.data
                      ?.filter(
                        (invoice) =>
                          invoice.customer_id === selected.customer_id &&
                          (!selected.company_id ||
                            invoice.company_id === selected.company_id) &&
                          invoice.currency_code === selected.currency_code &&
                          ['CONFIRMED', 'SENT'].includes(
                            invoice.document_status
                          ) &&
                          invoice.payment_status !== 'PAID'
                      )
                      .map((invoice) => (
                        <option key={invoice.id} value={invoice.id}>
                          {invoice.invoice_number} ·{' '}
                          {money(invoice.total_minor, invoice.currency_code)}
                        </option>
                      ))}
                  </select>
                  <Input
                    value={allocationAmount}
                    onChange={(event) =>
                      setAllocationAmount(event.target.value)
                    }
                    inputMode='decimal'
                    placeholder='分配金额'
                  />
                  <Button
                    className='w-full'
                    disabled={
                      allocation.isPending ||
                      !invoiceId ||
                      !isValidPositiveAmount(
                        allocationAmount,
                        selected.currency_code
                      ) ||
                      operationReason.trim().length < 2
                    }
                    onClick={() => allocation.mutate()}
                  >
                    确认分配
                  </Button>
                </div>
                <div className='min-w-0 space-y-3 rounded-lg border p-4'>
                  <p className='font-medium'>记录退款</p>
                  <Input
                    value={refundAmount}
                    onChange={(event) => setRefundAmount(event.target.value)}
                    inputMode='decimal'
                    placeholder='退款金额'
                  />
                  <Button
                    variant='destructive'
                    className='w-full'
                    disabled={
                      refund.isPending ||
                      !isValidPositiveAmount(
                        refundAmount,
                        selected.currency_code
                      ) ||
                      operationReason.trim().length < 2
                    }
                    onClick={() => refund.mutate()}
                  >
                    确认退款
                  </Button>
                </div>
              </div>
              <Field label='操作原因'>
                <Textarea
                  value={operationReason}
                  onChange={(event) => setOperationReason(event.target.value)}
                />
              </Field>
              <div className='max-w-full min-w-0 overflow-x-auto rounded-lg border'>
                <Table>
                  <TableHeader>
                    <TableRow className='bg-muted/30'>
                      <TableHead>账单</TableHead>
                      <TableHead>金额</TableHead>
                      <TableHead>状态</TableHead>
                      <TableHead />
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {detail.data.allocations.map((item) => (
                      <TableRow key={item.id}>
                        <TableCell className='font-mono text-xs'>
                          {item.invoice_id.slice(0, 8)}
                        </TableCell>
                        <TableCell className='font-mono'>
                          {money(item.amount_minor, selected.currency_code)}
                        </TableCell>
                        <TableCell>
                          <Badge variant='outline'>{item.status}</Badge>
                        </TableCell>
                        <TableCell>
                          <Button
                            size='sm'
                            variant='ghost'
                            disabled={
                              item.status !== 'ACTIVE' ||
                              reverse.isPending ||
                              operationReason.trim().length < 2
                            }
                            onClick={() => reverse.mutate(item.id)}
                          >
                            冲销
                          </Button>
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </div>
            </div>
          )}
        </DialogContent>
      </Dialog>
    </>
  )
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div className='min-w-0 rounded-lg border bg-muted/20 p-3'>
      <p className='text-xs text-muted-foreground'>{label}</p>
      <p className='mt-1 font-mono text-sm font-semibold break-all'>{value}</p>
    </div>
  )
}

function PaymentDialog({
  open,
  onClose,
}: {
  open: boolean
  onClose: () => void
}) {
  const queryClient = useQueryClient()
  const customers = useQuery(customersQuery())
  const [customerId, setCustomerId] = useState('')
  const [currency, setCurrency] = useState('CNY')
  const [amount, setAmount] = useState('')
  const [method, setMethod] = useState('BANK_TRANSFER')
  const [reference, setReference] = useState('')
  const [reason, setReason] = useState('人工核对银行到账记录')
  const mutation = useMutation({
    mutationFn: () =>
      recordPayment({
        customer_id: customerId,
        currency_code: currency,
        amount_minor: toPositiveMinorUnits(amount, currency),
        payment_method: method,
        paid_at: new Date().toISOString(),
        external_reference: reference || undefined,
        reason,
      }),
    onSuccess: async () => {
      toast.success('付款已记录')
      onClose()
      await queryClient.invalidateQueries({ queryKey: ['payments'] })
    },
  })
  return (
    <Dialog open={open} onOpenChange={(value) => !value && onClose()}>
      <DialogContent className='grid max-h-[calc(100svh-2rem)] min-w-0 grid-rows-[auto_minmax(0,1fr)_auto] gap-0 overflow-hidden p-0 sm:max-w-xl'>
        <DialogHeader className='border-b px-6 pt-6 pb-4'>
          <DialogTitle>记录付款</DialogTitle>
          <DialogDescription>
            金额使用 Decimal.js
            做安全输入转换，服务端仍会校验客户、币种、余额与后续分配。
          </DialogDescription>
        </DialogHeader>
        <div className='grid min-h-0 min-w-0 gap-4 overflow-y-auto px-6 py-4 sm:grid-cols-2'>
          <Field label='客户'>
            <select
              value={customerId}
              onChange={(event) => {
                setCustomerId(event.target.value)
                const customer = customers.data?.data.find(
                  (item) => item.id === event.target.value
                )
                if (customer) setCurrency(customer.default_currency)
              }}
              className='h-9 w-full rounded-md border bg-transparent px-3 text-sm'
            >
              <option value=''>请选择客户</option>
              {customers.data?.data.map((customer) => (
                <option key={customer.id} value={customer.id}>
                  {customer.customer_no} · {customer.customer_name}
                </option>
              ))}
            </select>
          </Field>
          <Field label='币种'>
            <Input
              value={currency}
              maxLength={3}
              onChange={(event) =>
                setCurrency(event.target.value.toUpperCase())
              }
              className='font-mono'
            />
          </Field>
          <Field label='到账金额'>
            <Input
              value={amount}
              inputMode='decimal'
              placeholder='0.00'
              onChange={(event) => setAmount(event.target.value)}
            />
          </Field>
          <Field label='付款方式'>
            <Input
              value={method}
              onChange={(event) => setMethod(event.target.value)}
              className='font-mono'
            />
          </Field>
          <Field label='外部流水号' className='sm:col-span-2'>
            <Input
              value={reference}
              onChange={(event) => setReference(event.target.value)}
            />
          </Field>
          <Field label='记录原因' className='sm:col-span-2'>
            <Textarea
              value={reason}
              onChange={(event) => setReason(event.target.value)}
            />
          </Field>
        </div>
        <DialogFooter className='border-t bg-background px-6 py-4'>
          <Button variant='outline' onClick={onClose}>
            取消
          </Button>
          <Button
            disabled={
              mutation.isPending ||
              !customerId ||
              !isValidPositiveAmount(amount, currency) ||
              reason.trim().length < 2
            }
            onClick={() => mutation.mutate()}
          >
            {mutation.isPending ? '正在记录…' : '确认到账'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function Field({
  label,
  className,
  children,
}: {
  label: string
  className?: string
  children: React.ReactNode
}) {
  return (
    <div className={`space-y-2 ${className ?? ''}`}>
      <Label>{label}</Label>
      {children}
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
  value: string
}) {
  return (
    <Card className='py-5 shadow-none'>
      <CardContent className='flex items-center gap-4'>
        <span className='grid size-10 place-items-center rounded-lg bg-muted text-muted-foreground'>
          {icon}
        </span>
        <div className='min-w-0'>
          <p className='text-xs text-muted-foreground'>{label}</p>
          <p className='mt-1 font-mono text-xl font-semibold break-all'>
            {value}
          </p>
        </div>
      </CardContent>
    </Card>
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
