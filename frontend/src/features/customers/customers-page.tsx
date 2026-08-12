import { useMemo, useState } from 'react'
import { z } from 'zod'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import {
  useMutation,
  useQuery,
  useQueryClient,
  useSuspenseQuery,
} from '@tanstack/react-query'
import {
  flexRender,
  getCoreRowModel,
  useReactTable,
  type ColumnDef,
} from '@tanstack/react-table'
import { can } from '@/auth/permission'
import { Route } from '@/routes/_authenticated/customers'
import {
  Archive,
  Building2,
  MoreHorizontal,
  Pencil,
  Plus,
  Search,
} from 'lucide-react'
import { toast } from 'sonner'
import { sessionQuery } from '@/api/auth'
import {
  archiveCustomer,
  createCustomer,
  customersQuery,
  updateCustomer,
  type Customer,
} from '@/api/customers'
import { problemFrom } from '@/api/http'
import {
  createCompany,
  companiesQuery,
  updateCompany,
  type Company,
} from '@/api/operations'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
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
import { ConfirmDialog } from '@/components/confirm-dialog'
import { Main } from '@/components/layout/main'
import { ConsoleHeader } from '@/features/shell/console-header'
import { PageHeading } from '@/features/shell/page-heading'

const schema = z.object({
  customer_no: z
    .string()
    .trim()
    .regex(/^[A-Z0-9][A-Z0-9-]{2,63}$/, '使用 3–64 位大写字母、数字或连字符'),
  customer_name: z.string().trim().min(2, '请输入客户名称'),
  customer_type: z.enum(['ENTERPRISE', 'INDIVIDUAL', 'RESELLER']),
  default_currency: z
    .string()
    .trim()
    .regex(/^[A-Z]{3}$/, '请输入三位币种代码'),
  default_language: z.string().trim().min(2),
  default_billing_cycle: z.enum(['MONTHLY', 'QUARTERLY', 'ANNUAL']),
  default_payment_terms_days: z.number().int().min(0).max(365),
  notes: z.string().max(2000).optional(),
  reason: z.string().trim().min(2, '请填写变更原因'),
})
type CustomerInput = z.infer<typeof schema>

export function CustomersPage() {
  const { q } = Route.useSearch()
  const navigate = Route.useNavigate()
  const queryClient = useQueryClient()
  const { data: session } = useSuspenseQuery(sessionQuery)
  const customers = useQuery(customersQuery(q ?? ''))
  const companies = useQuery(companiesQuery)
  const companiesByCustomer = useMemo(() => {
    const grouped = new Map<string, Company[]>()
    for (const company of companies.data ?? []) {
      const list = grouped.get(company.customer_id) ?? []
      list.push(company)
      grouped.set(company.customer_id, list)
    }
    return grouped
  }, [companies.data])
  const [dialogOpen, setDialogOpen] = useState(false)
  const [editing, setEditing] = useState<Customer>()
  const [archiving, setArchiving] = useState<Customer>()
  const [companyFor, setCompanyFor] = useState<Customer>()
  const [editingCompany, setEditingCompany] = useState<Company>()
  const writable = can(session, 'customer.write')

  const updateCompanyMutation = useMutation({
    mutationFn: ({
      id,
      version,
      input,
    }: {
      id: string
      version: number
      input: Parameters<typeof updateCompany>[2]
    }) => updateCompany(id, version, input),
    onSuccess: async (updated) => {
      toast.success(`公司 ${updated.company_name} 已更新`)
      setEditingCompany(undefined)
      await queryClient.invalidateQueries({ queryKey: ['companies'] })
    },
    onError: (error) => {
      const problem = problemFrom(error)
      toast.error(problem.detail ?? problem.title ?? '更新公司失败')
    },
  })

  const createCompanyMutation = useMutation({
    mutationFn: createCompany,
    onSuccess: async (created) => {
      toast.success(`公司 ${created.company_name} 已创建`)
      setCompanyFor(undefined)
      await queryClient.invalidateQueries({ queryKey: ['companies'] })
    },
    onError: (error) => {
      const problem = problemFrom(error)
      toast.error(problem.detail ?? problem.title ?? '创建公司失败')
    },
  })

  const archiveMutation = useMutation({
    mutationFn: ({
      customer,
      reason,
    }: {
      customer: Customer
      reason: string
    }) => archiveCustomer(customer, reason),
    onSuccess: async () => {
      toast.success('客户已归档')
      setArchiving(undefined)
      await queryClient.invalidateQueries({ queryKey: ['customers'] })
    },
  })

  const columns = useMemo<ColumnDef<Customer>[]>(
    () => [
      {
        accessorKey: 'customer_no',
        header: '客户编号',
        cell: ({ row }) => (
          <span className='font-mono text-xs font-semibold'>
            {row.original.customer_no}
          </span>
        ),
      },
      {
        accessorKey: 'customer_name',
        header: '客户名称',
        cell: ({ row }) => (
          <div>
            <p className='font-medium'>{row.original.customer_name}</p>
            <p className='mt-1 text-xs text-muted-foreground'>
              {typeLabel(row.original.customer_type)}
            </p>
          </div>
        ),
      },
      {
        accessorKey: 'default_currency',
        header: '币种',
        cell: ({ row }) => (
          <span className='font-mono'>{row.original.default_currency}</span>
        ),
      },
      {
        id: 'companies',
        header: '公司',
        cell: ({ row }) => {
          const list = companiesByCustomer.get(row.original.id) ?? []
          if (list.length === 0) {
            return <span className='text-xs text-muted-foreground'>—</span>
          }
          return (
            <div className='flex max-w-64 flex-wrap gap-1'>
              {list.map((company) => (
                <button
                  key={company.id}
                  type='button'
                  title={`${company.company_code}(点击编辑)`}
                  onClick={() => writable && setEditingCompany(company)}
                  className='rounded-md transition-transform hover:scale-[1.03]'
                >
                  <Badge
                    variant='secondary'
                    className='cursor-pointer font-normal'
                  >
                    {company.company_name}
                  </Badge>
                </button>
              ))}
            </div>
          )
        },
      },
      {
        accessorKey: 'default_payment_terms_days',
        header: '付款期限',
        cell: ({ row }) => `${row.original.default_payment_terms_days} 天`,
      },
      {
        accessorKey: 'status',
        header: '状态',
        cell: ({ row }) => <StatusBadge status={row.original.status} />,
      },
      {
        id: 'actions',
        header: '',
        cell: ({ row }) =>
          writable ? (
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant='ghost' size='icon' aria-label='客户操作'>
                  <MoreHorizontal />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align='end'>
                <DropdownMenuItem
                  onClick={() => {
                    setEditing(row.original)
                    setDialogOpen(true)
                  }}
                >
                  <Pencil />
                  编辑
                </DropdownMenuItem>
                <DropdownMenuItem
                  disabled={row.original.status === 'ARCHIVED'}
                  onClick={() => setCompanyFor(row.original)}
                >
                  <Building2 />
                  新增公司
                </DropdownMenuItem>
                <DropdownMenuItem
                  variant='destructive'
                  disabled={row.original.status === 'ARCHIVED'}
                  onClick={() => setArchiving(row.original)}
                >
                  <Archive />
                  归档
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          ) : null,
      },
    ],
    [writable, companiesByCustomer]
  )

  // TanStack Table returns stable imperative helpers that React Compiler intentionally skips.
  // eslint-disable-next-line react-hooks/incompatible-library
  const table = useReactTable({
    data: customers.data?.data ?? [],
    columns,
    getCoreRowModel: getCoreRowModel(),
  })

  return (
    <>
      <ConsoleHeader label='customers' />
      <Main className='space-y-7'>
        <PageHeading
          eyebrow='主数据'
          title='客户与商业主体'
          description='客户是商业关系主体；公司、业务、合同和账单配置作为独立实体继续向下关联。'
          action={
            writable ? (
              <Button
                onClick={() => {
                  setEditing(undefined)
                  setDialogOpen(true)
                }}
              >
                <Plus />
                新增客户
              </Button>
            ) : undefined
          }
        />
        <div className='flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between'>
          <div className='relative w-full max-w-sm'>
            <Search className='absolute start-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground' />
            <Input
              value={q ?? ''}
              onChange={(event) =>
                void navigate({
                  search: { q: event.target.value || undefined },
                  replace: true,
                })
              }
              className='ps-9'
              placeholder='按编号或名称搜索'
            />
          </div>
          <p className='font-mono text-xs text-muted-foreground'>
            {customers.data
              ? `${customers.data.data.length} 条当前结果`
              : '正在读取…'}
          </p>
        </div>
        <div className='overflow-hidden rounded-xl border bg-card'>
          {customers.isLoading ? (
            <div className='space-y-3 p-6'>
              {Array.from({ length: 5 }).map((_, index) => (
                <Skeleton key={index} className='h-12 w-full' />
              ))}
            </div>
          ) : customers.isError ? (
            <div className='p-10 text-center'>
              <p className='font-semibold'>客户数据读取失败</p>
              <p className='mt-2 text-sm text-muted-foreground'>
                未使用本地假数据兜底，请检查会话和 API。
              </p>
            </div>
          ) : table.getRowModel().rows.length === 0 ? (
            <EmptyCustomers
              onCreate={writable ? () => setDialogOpen(true) : undefined}
            />
          ) : (
            <Table>
              <TableHeader>
                {table.getHeaderGroups().map((group) => (
                  <TableRow key={group.id} className='bg-muted/30'>
                    {group.headers.map((header) => (
                      <TableHead key={header.id} className='px-4'>
                        {header.isPlaceholder
                          ? null
                          : flexRender(
                              header.column.columnDef.header,
                              header.getContext()
                            )}
                      </TableHead>
                    ))}
                  </TableRow>
                ))}
              </TableHeader>
              <TableBody>
                {table.getRowModel().rows.map((row) => (
                  <TableRow key={row.id}>
                    {row.getVisibleCells().map((cell) => (
                      <TableCell key={cell.id} className='px-4 py-3'>
                        {flexRender(
                          cell.column.columnDef.cell,
                          cell.getContext()
                        )}
                      </TableCell>
                    ))}
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </div>
      </Main>
      <CustomerDialog
        open={dialogOpen}
        onOpenChange={setDialogOpen}
        customer={editing}
      />
      <CompanyDialog
        key={companyFor?.id ?? 'closed'}
        customer={companyFor}
        pending={createCompanyMutation.isPending}
        onClose={() => setCompanyFor(undefined)}
        onSubmit={(input) => createCompanyMutation.mutate(input)}
      />
      <CompanyEditDialog
        key={editingCompany?.id ?? 'closed'}
        company={editingCompany}
        pending={updateCompanyMutation.isPending}
        onClose={() => setEditingCompany(undefined)}
        onSubmit={(id, version, input) =>
          updateCompanyMutation.mutate({ id, version, input })
        }
      />
      <ConfirmDialog
        open={Boolean(archiving)}
        onOpenChange={(open) => !open && setArchiving(undefined)}
        title='归档客户'
        desc={`归档“${archiving?.customer_name ?? ''}”后，不再允许配置新业务；既有账单与审计记录保持不变。`}
        confirmText={archiveMutation.isPending ? '正在归档…' : '确认归档'}
        destructive
        handleConfirm={() =>
          archiving &&
          archiveMutation.mutate({
            customer: archiving,
            reason: '用户从客户管理页面执行归档',
          })
        }
      />
    </>
  )
}

function CustomerDialog({
  open,
  onOpenChange,
  customer,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  customer?: Customer
}) {
  const queryClient = useQueryClient()
  const form = useForm<CustomerInput>({
    resolver: zodResolver(schema),
    values: customer
      ? {
          customer_no: customer.customer_no,
          customer_name: customer.customer_name,
          customer_type:
            customer.customer_type as CustomerInput['customer_type'],
          default_currency: customer.default_currency,
          default_language: customer.default_language,
          default_billing_cycle:
            customer.default_billing_cycle as CustomerInput['default_billing_cycle'],
          default_payment_terms_days: customer.default_payment_terms_days,
          notes: customer.notes ?? '',
          reason: '',
        }
      : {
          customer_no: '',
          customer_name: '',
          customer_type: 'ENTERPRISE',
          default_currency: 'CNY',
          default_language: 'zh-CN',
          default_billing_cycle: 'MONTHLY',
          default_payment_terms_days: 30,
          notes: '',
          reason: '',
        },
  })
  const mutation = useMutation({
    mutationFn: (input: CustomerInput) =>
      customer
        ? updateCustomer(customer, {
            customer_name: input.customer_name,
            default_currency: input.default_currency,
            default_language: input.default_language,
            default_payment_terms_days: input.default_payment_terms_days,
            notes: input.notes,
            reason: input.reason,
          })
        : createCustomer(input),
    onSuccess: async () => {
      toast.success(customer ? '客户信息已更新' : '客户已创建')
      onOpenChange(false)
      await queryClient.invalidateQueries({ queryKey: ['customers'] })
    },
  })
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className='max-h-[92svh] overflow-y-auto sm:max-w-2xl'>
        <DialogHeader>
          <DialogTitle>{customer ? '编辑客户' : '新增客户'}</DialogTitle>
          <DialogDescription>
            租户由当前认证会话确定，表单不会发送 tenant_id。
          </DialogDescription>
        </DialogHeader>
        <form
          id='customer-form'
          className='grid gap-5 sm:grid-cols-2'
          onSubmit={form.handleSubmit((value) => mutation.mutate(value))}
        >
          <FormField
            label='客户编号'
            error={form.formState.errors.customer_no?.message}
          >
            <Input
              disabled={Boolean(customer)}
              className='font-mono uppercase'
              {...form.register('customer_no')}
            />
          </FormField>
          <FormField
            label='客户名称'
            error={form.formState.errors.customer_name?.message}
          >
            <Input {...form.register('customer_name')} />
          </FormField>
          <FormField label='客户类型'>
            <NativeSelect
              disabled={Boolean(customer)}
              {...form.register('customer_type')}
            >
              <option value='ENTERPRISE'>企业客户</option>
              <option value='RESELLER'>渠道商</option>
              <option value='INDIVIDUAL'>个人客户</option>
            </NativeSelect>
          </FormField>
          <FormField
            label='默认币种'
            error={form.formState.errors.default_currency?.message}
          >
            <Input
              className='font-mono uppercase'
              maxLength={3}
              {...form.register('default_currency')}
            />
          </FormField>
          <FormField label='默认语言'>
            <Input {...form.register('default_language')} />
          </FormField>
          <FormField label='账单周期'>
            <NativeSelect
              disabled={Boolean(customer)}
              {...form.register('default_billing_cycle')}
            >
              <option value='MONTHLY'>每月</option>
              <option value='QUARTERLY'>每季度</option>
              <option value='ANNUAL'>每年</option>
            </NativeSelect>
          </FormField>
          <FormField
            label='付款期限（天）'
            error={form.formState.errors.default_payment_terms_days?.message}
          >
            <Input
              type='number'
              min={0}
              max={365}
              {...form.register('default_payment_terms_days', {
                valueAsNumber: true,
              })}
            />
          </FormField>
          <FormField
            label='变更原因'
            error={form.formState.errors.reason?.message}
          >
            <Input placeholder='用于审计留痕' {...form.register('reason')} />
          </FormField>
          <FormField label='备注' className='sm:col-span-2'>
            <Textarea rows={4} {...form.register('notes')} />
          </FormField>
        </form>
        <DialogFooter>
          <Button
            type='button'
            variant='outline'
            onClick={() => onOpenChange(false)}
          >
            取消
          </Button>
          <Button
            form='customer-form'
            type='submit'
            disabled={mutation.isPending}
          >
            {mutation.isPending ? '正在保存…' : '保存客户'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function FormField({
  label,
  error,
  className,
  children,
}: {
  label: string
  error?: string
  className?: string
  children: React.ReactNode
}) {
  return (
    <div className={`space-y-2 ${className ?? ''}`}>
      <Label>{label}</Label>
      {children}
      {error && <p className='text-xs text-destructive'>{error}</p>}
    </div>
  )
}

function NativeSelect(props: React.ComponentProps<'select'>) {
  return (
    <select
      {...props}
      className='h-9 w-full rounded-md border border-input bg-transparent px-3 text-sm shadow-xs outline-none focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-50'
    />
  )
}

function StatusBadge({ status }: { status: string }) {
  return status === 'ACTIVE' ? (
    <Badge
      className='border-emerald-200 bg-emerald-50 text-emerald-800 dark:border-emerald-900 dark:bg-emerald-950 dark:text-emerald-300'
      variant='outline'
    >
      有效
    </Badge>
  ) : (
    <Badge variant='secondary'>已归档</Badge>
  )
}

function EmptyCustomers({ onCreate }: { onCreate?: () => void }) {
  return (
    <div className='grid place-items-center px-6 py-16 text-center'>
      <span className='grid size-12 place-items-center rounded-full bg-muted'>
        <Building2 className='size-5 text-muted-foreground' />
      </span>
      <h2 className='mt-5 font-semibold'>尚无客户</h2>
      <p className='mt-2 max-w-sm text-sm leading-6 text-muted-foreground'>
        创建首个客户后，才能继续配置公司、业务、合同与账单。
      </p>
      {onCreate && (
        <Button className='mt-5' onClick={onCreate}>
          <Plus />
          新增客户
        </Button>
      )}
    </div>
  )
}

function typeLabel(value: string) {
  return (
    (
      {
        ENTERPRISE: '企业客户',
        INDIVIDUAL: '个人客户',
        RESELLER: '渠道商',
      } as Record<string, string>
    )[value] ?? value
  )
}

function CompanyDialog({
  customer,
  pending,
  onClose,
  onSubmit,
}: {
  customer?: Customer
  pending: boolean
  onClose: () => void
  onSubmit: (input: {
    customer_id: string
    company_code: string
    company_name: string
    default_currency: string
    tax_number?: string
    invoice_title?: string
  }) => void
}) {
  const [code, setCode] = useState('')
  const [name, setName] = useState('')
  const [taxNumber, setTaxNumber] = useState('')
  const [invoiceTitle, setInvoiceTitle] = useState('')
  const [currency, setCurrency] = useState('CNY')
  const valid =
    /^[A-Z0-9][A-Z0-9-]{2,63}$/.test(code.trim()) &&
    name.trim().length >= 2 &&
    /^[A-Z]{3}$/.test(currency.trim())

  return (
    <Dialog
      open={Boolean(customer)}
      onOpenChange={(open) => !open && onClose()}
    >
      <DialogContent>
        <DialogHeader>
          <DialogTitle>新增公司 · {customer?.customer_name}</DialogTitle>
          <DialogDescription>
            公司是开票与合同主体；创建后即可在业务管理中选用。
          </DialogDescription>
        </DialogHeader>
        <div className='grid gap-4 sm:grid-cols-2'>
          <div className='space-y-2'>
            <Label>公司编码</Label>
            <Input
              placeholder='ACME-CN'
              className='font-mono'
              value={code}
              onChange={(event) => setCode(event.target.value.toUpperCase())}
            />
          </div>
          <div className='space-y-2'>
            <Label>默认币种</Label>
            <Input
              className='font-mono'
              value={currency}
              onChange={(event) =>
                setCurrency(event.target.value.toUpperCase())
              }
            />
          </div>
          <div className='space-y-2 sm:col-span-2'>
            <Label>公司名称</Label>
            <Input
              placeholder='某某科技（上海）有限公司'
              value={name}
              onChange={(event) => setName(event.target.value)}
            />
          </div>
          <div className='space-y-2'>
            <Label>税号（可选）</Label>
            <Input
              className='font-mono'
              value={taxNumber}
              onChange={(event) => setTaxNumber(event.target.value)}
            />
          </div>
          <div className='space-y-2'>
            <Label>发票抬头（可选）</Label>
            <Input
              value={invoiceTitle}
              onChange={(event) => setInvoiceTitle(event.target.value)}
            />
          </div>
        </div>
        <DialogFooter>
          <Button variant='outline' onClick={onClose}>
            取消
          </Button>
          <Button
            disabled={pending || !valid || !customer}
            onClick={() =>
              customer &&
              onSubmit({
                customer_id: customer.id,
                company_code: code.trim(),
                company_name: name.trim(),
                default_currency: currency.trim(),
                tax_number: taxNumber.trim() || undefined,
                invoice_title: invoiceTitle.trim() || undefined,
              })
            }
          >
            创建公司
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function CompanyEditDialog({
  company,
  pending,
  onClose,
  onSubmit,
}: {
  company?: Company
  pending: boolean
  onClose: () => void
  onSubmit: (
    id: string,
    version: number,
    input: {
      company_name?: string
      company_name_en?: string
      address?: string
      tax_number?: string
      invoice_title?: string
      default_currency?: string
      default_tax_rate?: string
      status?: string
      reason: string
    }
  ) => void
}) {
  const [name, setName] = useState(company?.company_name ?? '')
  const [nameEn, setNameEn] = useState(company?.company_name_en ?? '')
  const [address, setAddress] = useState(company?.address ?? '')
  const [taxNumber, setTaxNumber] = useState(company?.tax_number ?? '')
  const [invoiceTitle, setInvoiceTitle] = useState(company?.invoice_title ?? '')
  const [currency, setCurrency] = useState(company?.default_currency ?? 'CNY')
  const [taxRate, setTaxRate] = useState(company?.default_tax_rate ?? '')
  const [status, setStatus] = useState(company?.status ?? 'ACTIVE')
  if (!company) return null
  const valid = name.trim().length >= 2 && /^[A-Z]{3}$/.test(currency.trim())

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>编辑公司 · {company.company_code}</DialogTitle>
          <DialogDescription>
            税号与发票抬头会进入新开账单的公司快照；历史账单保持不变。
          </DialogDescription>
        </DialogHeader>
        <div className='grid gap-4 sm:grid-cols-2'>
          <div className='space-y-2'>
            <Label>公司名称</Label>
            <Input value={name} onChange={(e) => setName(e.target.value)} />
          </div>
          <div className='space-y-2'>
            <Label>英文名称</Label>
            <Input value={nameEn} onChange={(e) => setNameEn(e.target.value)} />
          </div>
          <div className='space-y-2 sm:col-span-2'>
            <Label>地址</Label>
            <Input
              value={address}
              onChange={(e) => setAddress(e.target.value)}
            />
          </div>
          <div className='space-y-2'>
            <Label>税号</Label>
            <Input
              className='font-mono'
              value={taxNumber}
              onChange={(e) => setTaxNumber(e.target.value)}
            />
          </div>
          <div className='space-y-2'>
            <Label>发票抬头</Label>
            <Input
              value={invoiceTitle}
              onChange={(e) => setInvoiceTitle(e.target.value)}
            />
          </div>
          <div className='space-y-2'>
            <Label>默认币种</Label>
            <Input
              className='font-mono'
              value={currency}
              onChange={(e) => setCurrency(e.target.value.toUpperCase())}
            />
          </div>
          <div className='space-y-2'>
            <Label>默认税率(如 0.06)</Label>
            <Input
              className='font-mono'
              value={taxRate}
              onChange={(e) => setTaxRate(e.target.value)}
            />
          </div>
          <div className='space-y-2'>
            <Label>状态</Label>
            <select
              value={status}
              onChange={(e) => setStatus(e.target.value)}
              className='h-9 w-full rounded-md border bg-background px-3 text-sm'
            >
              <option value='ACTIVE'>ACTIVE</option>
              <option value='ARCHIVED'>ARCHIVED</option>
            </select>
          </div>
        </div>
        <DialogFooter>
          <Button variant='outline' onClick={onClose}>
            取消
          </Button>
          <Button
            disabled={pending || !valid}
            onClick={() =>
              onSubmit(company.id, company.version, {
                company_name: name.trim(),
                company_name_en: nameEn.trim() || undefined,
                address: address.trim() || undefined,
                tax_number: taxNumber.trim() || undefined,
                invoice_title: invoiceTitle.trim() || undefined,
                default_currency: currency.trim(),
                default_tax_rate: taxRate.trim() || undefined,
                status,
                reason: '在客户页编辑公司资料',
              })
            }
          >
            保存修改
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
