import { useState } from 'react'
import {
  useMutation,
  useQuery,
  useQueryClient,
  useSuspenseQuery,
} from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { can } from '@/auth/permission'
import { Route } from '@/routes/_authenticated/customers_.$customerId'
import { ArrowLeft, Building2, FileText, Pencil, Plus } from 'lucide-react'
import { toast } from 'sonner'
import { sessionQuery } from '@/api/auth'
import { customerDetailQuery } from '@/api/customers'
import { problemFrom } from '@/api/http'
import {
  companiesQuery,
  contractsQuery,
  createCompany,
  updateCompany,
  type Company,
  type Contract,
} from '@/api/operations'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
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
import { CompanyFormDialog } from '@/features/customers/company-form-dialog'
import { ConsoleHeader } from '@/features/shell/console-header'
import { PageHeading } from '@/features/shell/page-heading'

export function CustomerDetailPage() {
  const { customerId } = Route.useParams()
  const queryClient = useQueryClient()
  const { data: session } = useSuspenseQuery(sessionQuery)
  const customer = useQuery(customerDetailQuery(customerId))
  const companies = useQuery(companiesQuery)
  const writable = can(session, 'customer.write')
  const [createOpen, setCreateOpen] = useState(false)
  const [editing, setEditing] = useState<Company>()

  const saveMutation = useMutation({
    mutationFn: (input: {
      id?: string
      version?: number
      values: Parameters<typeof createCompany>[0] & {
        phone?: string
        bank_name?: string
        bank_account?: string
        invoice_type?: string
      }
    }) =>
      input.id
        ? updateCompany(input.id, input.version ?? 0, {
            ...input.values,
            reason: '在客户详情页编辑公司',
          })
        : createCompany(input.values),
    onSuccess: async (saved) => {
      toast.success(`公司 ${saved.company_name} 已保存`)
      setCreateOpen(false)
      setEditing(undefined)
      await queryClient.invalidateQueries({ queryKey: ['companies'] })
    },
    onError: (error) => {
      const problem = problemFrom(error)
      toast.error(problem.detail ?? problem.title ?? '保存公司失败')
    },
  })

  const list = (companies.data ?? []).filter(
    (company) => company.customer_id === customerId
  )
  const contracts = useQuery(contractsQuery)
  const activeContracts = (contracts.data ?? []).filter(
    (contract) =>
      contract.customer_id === customerId && contract.status === 'ACTIVE'
  )
  const detail = customer.data

  return (
    <>
      <ConsoleHeader label='customers' />
      <Main className='space-y-7'>
        <div>
          <Link
            to='/customers'
            className='inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground'
          >
            <ArrowLeft className='size-4' /> 返回客户列表
          </Link>
        </div>
        {customer.isLoading ? (
          <Skeleton className='h-24 w-full' />
        ) : !detail ? (
          <p className='py-16 text-center text-sm text-muted-foreground'>
            客户不存在或已被归档。
          </p>
        ) : (
          <>
            <PageHeading
              eyebrow={detail.customer_no}
              title={detail.customer_name}
              description={`${detail.customer_type} · 默认币种 ${detail.default_currency} · 付款期限 ${detail.default_payment_terms_days} 天`}
              action={
                writable ? (
                  <Button onClick={() => setCreateOpen(true)}>
                    <Plus /> 新增公司
                  </Button>
                ) : undefined
              }
            />
            <Card className='shadow-none'>
              <CardHeader>
                <CardTitle className='flex items-center gap-2 text-base'>
                  <Building2 className='size-4' />
                  所属公司({list.length})
                </CardTitle>
                <CardDescription>
                  公司是开票与合同主体;开票种类、税号、银行账户会进入账单公司快照。
                </CardDescription>
              </CardHeader>
              <CardContent className='p-0'>
                {companies.isLoading ? (
                  <div className='p-6'>
                    <Skeleton className='h-12 w-full' />
                  </div>
                ) : !list.length ? (
                  <p className='p-12 text-center text-sm text-muted-foreground'>
                    该客户下尚无公司。
                  </p>
                ) : (
                  <Table>
                    <TableHeader>
                      <TableRow className='bg-muted/30'>
                        <TableHead>公司</TableHead>
                        <TableHead>地区</TableHead>
                        <TableHead>开票/收款信息</TableHead>
                        <TableHead>电话</TableHead>
                        <TableHead>状态</TableHead>
                        {writable && <TableHead />}
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {list.map((company) => (
                        <TableRow key={company.id}>
                          <TableCell>
                            <p className='font-medium'>
                              {company.company_name}
                            </p>
                            <p className='mt-1 font-mono text-xs text-muted-foreground'>
                              {company.company_code}
                            </p>
                          </TableCell>
                          <TableCell>
                            <Badge variant='outline'>
                              {company.country_region === 'HK'
                                ? '香港'
                                : '中国'}
                            </Badge>
                          </TableCell>
                          <TableCell className='text-xs'>
                            {company.country_region === 'HK' ? (
                              <div className='space-y-0.5 font-mono'>
                                {company.br_number && (
                                  <p>BR: {company.br_number}</p>
                                )}
                                <p>{company.bank_name ?? '—'}</p>
                                <p className='text-muted-foreground'>
                                  {[company.bank_code, company.swift_code]
                                    .filter(Boolean)
                                    .join(' · ') || '—'}
                                </p>
                                <p className='text-muted-foreground'>
                                  {company.bank_account ?? ''}
                                </p>
                              </div>
                            ) : (
                              <div className='space-y-0.5'>
                                <p>
                                  {company.invoice_type === 'SPECIAL'
                                    ? '专票'
                                    : '普票'}
                                  <span className='ml-2 font-mono text-muted-foreground'>
                                    {company.tax_number ?? ''}
                                  </span>
                                </p>
                                <p className='font-mono text-muted-foreground'>
                                  {[company.bank_name, company.bank_account]
                                    .filter(Boolean)
                                    .join(' ') || '—'}
                                </p>
                              </div>
                            )}
                          </TableCell>
                          <TableCell className='font-mono text-xs'>
                            {company.phone ?? '—'}
                          </TableCell>
                          <TableCell>
                            <Badge
                              variant={
                                company.status === 'ACTIVE'
                                  ? 'default'
                                  : 'secondary'
                              }
                            >
                              {company.status}
                            </Badge>
                          </TableCell>
                          {writable && (
                            <TableCell className='text-right'>
                              <Button
                                size='sm'
                                variant='outline'
                                onClick={() => setEditing(company)}
                              >
                                <Pencil /> 编辑
                              </Button>
                            </TableCell>
                          )}
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                )}
              </CardContent>
            </Card>
            <Card className='shadow-none'>
              <CardHeader>
                <CardTitle className='flex items-center gap-2 text-base'>
                  <FileText className='size-4' />
                  已生效合同({activeContracts.length})
                </CardTitle>
                <CardDescription>
                  到期前 30 天提示续订;合同主体与计费项在合同管理中维护。
                </CardDescription>
              </CardHeader>
              <CardContent className='p-0'>
                {contracts.isLoading ? (
                  <div className='p-6'>
                    <Skeleton className='h-12 w-full' />
                  </div>
                ) : !activeContracts.length ? (
                  <p className='p-12 text-center text-sm text-muted-foreground'>
                    该客户暂无已生效合同。
                  </p>
                ) : (
                  <Table>
                    <TableHeader>
                      <TableRow className='bg-muted/30'>
                        <TableHead>合同</TableHead>
                        <TableHead>签约公司</TableHead>
                        <TableHead>开始时间</TableHead>
                        <TableHead>到期时间</TableHead>
                        <TableHead>续订</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {activeContracts.map((contract) => {
                        const hint = renewalHint(contract)
                        return (
                          <TableRow key={contract.id}>
                            <TableCell>
                              <p className='font-medium'>
                                {contract.contract_name}
                              </p>
                              <p className='mt-1 font-mono text-xs text-muted-foreground'>
                                {contract.contract_no}
                              </p>
                            </TableCell>
                            <TableCell className='text-xs'>
                              {companies.data?.find(
                                (c) => c.id === contract.company_id
                              )?.company_name ?? '—'}
                            </TableCell>
                            <TableCell className='font-mono text-xs'>
                              {contract.effective_from}
                            </TableCell>
                            <TableCell className='font-mono text-xs'>
                              {contract.effective_to ?? '长期'}
                            </TableCell>
                            <TableCell>
                              <Badge variant={hint.variant}>{hint.text}</Badge>
                            </TableCell>
                          </TableRow>
                        )
                      })}
                    </TableBody>
                  </Table>
                )}
              </CardContent>
            </Card>
          </>
        )}
      </Main>
      {detail && (
        <CompanyFormDialog
          key={editing?.id ?? (createOpen ? 'new' : 'closed')}
          open={createOpen || Boolean(editing)}
          company={editing}
          customerId={customerId}
          pending={saveMutation.isPending}
          onClose={() => {
            setCreateOpen(false)
            setEditing(undefined)
          }}
          onSubmit={(values) =>
            saveMutation.mutate({
              id: editing?.id,
              version: editing?.version,
              values,
            })
          }
        />
      )}
    </>
  )
}

function renewalHint(contract: Contract): {
  text: string
  variant: 'default' | 'secondary' | 'destructive' | 'outline'
} {
  if (contract.auto_renew) {
    return { text: '自动续订', variant: 'default' }
  }
  if (!contract.effective_to) {
    return { text: '长期有效', variant: 'secondary' }
  }
  const days = Math.ceil(
    (new Date(`${contract.effective_to}T00:00:00`).getTime() - Date.now()) /
      86_400_000
  )
  if (days < 0) {
    return { text: '已到期', variant: 'destructive' }
  }
  if (days <= 30) {
    return { text: `${days} 天后到期·待续订`, variant: 'outline' }
  }
  return { text: '履约中', variant: 'secondary' }
}
