import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Building, Pencil, Plus } from 'lucide-react'
import { toast } from 'sonner'
import { problemFrom } from '@/api/http'
import {
  billingEntitiesQuery,
  createBillingEntity,
  updateBillingEntity,
  type BillingEntity,
  type BillingEntityInput,
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

export function BillingEntitiesPanel() {
  const queryClient = useQueryClient()
  const entities = useQuery(billingEntitiesQuery)
  const [createOpen, setCreateOpen] = useState(false)
  const [editing, setEditing] = useState<BillingEntity>()

  const save = useMutation({
    mutationFn: (input: {
      id?: string
      version?: number
      values: BillingEntityInput
    }) =>
      input.id
        ? updateBillingEntity(input.id, input.version ?? 0, {
            ...input.values,
            reason: '在系统管理编辑出账主体',
          })
        : createBillingEntity(input.values),
    onSuccess: async (saved) => {
      toast.success(`出账主体 ${saved.entity_name} 已保存`)
      setCreateOpen(false)
      setEditing(undefined)
      await queryClient.invalidateQueries({ queryKey: ['billing-entities'] })
    },
    onError: (error) => {
      const problem = problemFrom(error)
      toast.error(problem.detail ?? problem.title ?? '保存失败')
    },
  })

  return (
    <Card className='shadow-none'>
      <CardHeader>
        <div className='flex items-start justify-between gap-4'>
          <div>
            <CardTitle className='flex items-center gap-2 text-base'>
              <Building className='size-4' />
              出账主体
            </CardTitle>
            <CardDescription>
              账单由哪个公司主体开具与收款;在账单配置上选择主体。中国内地与香港主体字段分别维护。
            </CardDescription>
          </div>
          <Button size='sm' onClick={() => setCreateOpen(true)}>
            <Plus /> 新增主体
          </Button>
        </div>
      </CardHeader>
      <CardContent className='p-0'>
        {entities.isLoading ? (
          <div className='p-6'>
            <Skeleton className='h-12 w-full' />
          </div>
        ) : !entities.data?.length ? (
          <p className='p-12 text-center text-sm text-muted-foreground'>
            尚无出账主体。新增后,在账单配置上选择即可。
          </p>
        ) : (
          <Table>
            <TableHeader>
              <TableRow className='bg-muted/30'>
                <TableHead>主体</TableHead>
                <TableHead>地区</TableHead>
                <TableHead>币种</TableHead>
                <TableHead>收款账户</TableHead>
                <TableHead>状态</TableHead>
                <TableHead />
              </TableRow>
            </TableHeader>
            <TableBody>
              {entities.data.map((entity) => (
                <TableRow key={entity.id}>
                  <TableCell>
                    <p className='font-medium'>{entity.entity_name}</p>
                    <p className='mt-1 font-mono text-xs text-muted-foreground'>
                      {entity.entity_code}
                      {entity.entity_name_en
                        ? ` · ${entity.entity_name_en}`
                        : ''}
                    </p>
                  </TableCell>
                  <TableCell>
                    <Badge variant='outline'>
                      {entity.country_region === 'HK' ? '香港' : '中国'}
                    </Badge>
                  </TableCell>
                  <TableCell className='font-mono text-xs'>
                    {entity.default_currency}
                  </TableCell>
                  <TableCell className='font-mono text-xs'>
                    <p>{entity.bank_name ?? '—'}</p>
                    <p className='text-muted-foreground'>
                      {entity.bank_account ?? ''}
                    </p>
                  </TableCell>
                  <TableCell>
                    <Badge
                      variant={
                        entity.status === 'ACTIVE' ? 'default' : 'secondary'
                      }
                    >
                      {entity.status}
                    </Badge>
                  </TableCell>
                  <TableCell className='text-right'>
                    <Button
                      size='sm'
                      variant='outline'
                      onClick={() => setEditing(entity)}
                    >
                      <Pencil /> 编辑
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </CardContent>
      <EntityDialog
        key={editing?.id ?? (createOpen ? 'new' : 'closed')}
        open={createOpen || Boolean(editing)}
        entity={editing}
        pending={save.isPending}
        onClose={() => {
          setCreateOpen(false)
          setEditing(undefined)
        }}
        onSubmit={(values) =>
          save.mutate({
            id: editing?.id,
            version: editing?.version,
            values,
          })
        }
      />
    </Card>
  )
}

function EntityDialog({
  open,
  entity,
  pending,
  onClose,
  onSubmit,
}: {
  open: boolean
  entity?: BillingEntity
  pending: boolean
  onClose: () => void
  onSubmit: (values: BillingEntityInput) => void
}) {
  const editing = Boolean(entity)
  const [region, setRegion] = useState(
    entity?.country_region === 'HK' ? 'HK' : 'CN'
  )
  const [code, setCode] = useState(entity?.entity_code ?? '')
  const [name, setName] = useState(entity?.entity_name ?? '')
  const [nameEn, setNameEn] = useState(entity?.entity_name_en ?? '')
  const [address, setAddress] = useState(entity?.address ?? '')
  const [phone, setPhone] = useState(entity?.phone ?? '')
  const [taxNumber, setTaxNumber] = useState(entity?.tax_number ?? '')
  const [brNumber, setBrNumber] = useState(entity?.br_number ?? '')
  const [invoiceTitle, setInvoiceTitle] = useState(entity?.invoice_title ?? '')
  const [bankName, setBankName] = useState(entity?.bank_name ?? '')
  const [bankCode, setBankCode] = useState(entity?.bank_code ?? '')
  const [swiftCode, setSwiftCode] = useState(entity?.swift_code ?? '')
  const [bankAddress, setBankAddress] = useState(entity?.bank_address ?? '')
  const [bankAccount, setBankAccount] = useState(entity?.bank_account ?? '')
  const [currency, setCurrency] = useState(
    entity?.default_currency ?? (region === 'HK' ? 'HKD' : 'CNY')
  )
  const [status, setStatus] = useState(entity?.status ?? 'ACTIVE')

  const valid =
    (editing || /^[A-Z0-9][A-Z0-9_-]{2,99}$/.test(code.trim())) &&
    name.trim().length >= 2 &&
    /^[A-Z]{3}$/.test(currency.trim())

  return (
    <Dialog open={open} onOpenChange={(next) => !next && onClose()}>
      <DialogContent className='max-h-[90vh] overflow-y-auto sm:max-w-2xl'>
        <DialogHeader>
          <DialogTitle>
            {editing ? `编辑出账主体 · ${entity?.entity_code}` : '新增出账主体'}
          </DialogTitle>
          <DialogDescription>
            主体信息会冻结进每张账单的卖方快照(seller.* 模板变量)。
          </DialogDescription>
        </DialogHeader>
        <div className='grid gap-4 sm:grid-cols-2'>
          <div className='space-y-2'>
            <Label>地区</Label>
            <select
              value={region}
              onChange={(e) => {
                setRegion(e.target.value)
                setCurrency(e.target.value === 'HK' ? 'HKD' : 'CNY')
              }}
              className='h-9 w-full rounded-md border bg-background px-3 text-sm'
            >
              <option value='CN'>中国</option>
              <option value='HK'>中国香港</option>
            </select>
          </div>
          {!editing && (
            <div className='space-y-2'>
              <Label>主体编码</Label>
              <Input
                placeholder='MOSS-CN'
                className='font-mono'
                value={code}
                onChange={(e) => setCode(e.target.value.toUpperCase())}
              />
            </div>
          )}
          <div className='space-y-2'>
            <Label>主体名称</Label>
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
            <Label>电话</Label>
            <Input
              className='font-mono'
              value={phone}
              onChange={(e) => setPhone(e.target.value)}
            />
          </div>
          {region === 'CN' ? (
            <>
              <div className='space-y-2'>
                <Label>税号</Label>
                <Input
                  className='font-mono'
                  value={taxNumber}
                  onChange={(e) => setTaxNumber(e.target.value)}
                />
              </div>
              <div className='space-y-2'>
                <Label>开票抬头</Label>
                <Input
                  value={invoiceTitle}
                  onChange={(e) => setInvoiceTitle(e.target.value)}
                />
              </div>
            </>
          ) : (
            <div className='space-y-2'>
              <Label>商业登记号(BR Number)</Label>
              <Input
                className='font-mono'
                value={brNumber}
                onChange={(e) => setBrNumber(e.target.value)}
              />
            </div>
          )}
          <div className='space-y-2'>
            <Label>{region === 'HK' ? 'Bank Name' : '开户银行'}</Label>
            <Input
              value={bankName}
              onChange={(e) => setBankName(e.target.value)}
            />
          </div>
          <div className='space-y-2'>
            <Label>{region === 'HK' ? '账户号码' : '银行账户'}</Label>
            <Input
              className='font-mono'
              value={bankAccount}
              onChange={(e) => setBankAccount(e.target.value)}
            />
          </div>
          {region === 'HK' && (
            <>
              <div className='space-y-2'>
                <Label>Bank Code</Label>
                <Input
                  className='font-mono'
                  value={bankCode}
                  onChange={(e) => setBankCode(e.target.value)}
                />
              </div>
              <div className='space-y-2'>
                <Label>Swift Code</Label>
                <Input
                  className='font-mono'
                  value={swiftCode}
                  onChange={(e) => setSwiftCode(e.target.value.toUpperCase())}
                />
              </div>
              <div className='space-y-2 sm:col-span-2'>
                <Label>Bank Address</Label>
                <Input
                  value={bankAddress}
                  onChange={(e) => setBankAddress(e.target.value)}
                />
              </div>
            </>
          )}
          <div className='space-y-2'>
            <Label>默认币种</Label>
            <Input
              className='font-mono'
              value={currency}
              onChange={(e) => setCurrency(e.target.value.toUpperCase())}
            />
          </div>
          {editing && (
            <div className='space-y-2'>
              <Label>状态</Label>
              <select
                value={status}
                onChange={(e) => setStatus(e.target.value)}
                className='h-9 w-full rounded-md border bg-background px-3 text-sm'
              >
                <option value='ACTIVE'>ACTIVE</option>
                <option value='DISABLED'>DISABLED</option>
              </select>
            </div>
          )}
        </div>
        <DialogFooter>
          <Button variant='outline' onClick={onClose}>
            取消
          </Button>
          <Button
            disabled={pending || !valid}
            onClick={() =>
              onSubmit({
                entity_code: code.trim(),
                entity_name: name.trim(),
                entity_name_en: nameEn.trim() || undefined,
                country_region: region,
                address: address.trim() || undefined,
                phone: phone.trim() || undefined,
                tax_number:
                  region === 'CN' ? taxNumber.trim() || undefined : undefined,
                br_number:
                  region === 'HK' ? brNumber.trim() || undefined : undefined,
                invoice_title:
                  region === 'CN'
                    ? invoiceTitle.trim() || undefined
                    : undefined,
                bank_name: bankName.trim() || undefined,
                bank_code:
                  region === 'HK' ? bankCode.trim() || undefined : undefined,
                swift_code:
                  region === 'HK' ? swiftCode.trim() || undefined : undefined,
                bank_address:
                  region === 'HK' ? bankAddress.trim() || undefined : undefined,
                bank_account: bankAccount.trim() || undefined,
                default_currency: currency.trim(),
                status: editing ? status : undefined,
              })
            }
          >
            {editing ? '保存修改' : '创建主体'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
