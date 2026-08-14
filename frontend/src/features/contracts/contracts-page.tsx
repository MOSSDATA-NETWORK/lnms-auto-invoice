import { useState, type ReactNode } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  ArrowRight,
  BookOpenCheck,
  CalendarRange,
  CheckCircle2,
  CircleDollarSign,
  FileText,
  GitBranch,
  Layers3,
  Pencil,
  Plus,
  Tags,
  Trash2,
} from 'lucide-react'
import { toast } from 'sonner'
import { customersQuery } from '@/api/customers'
import { problemFrom } from '@/api/http'
import {
  activateContract,
  billingEntitiesQuery,
  companiesQuery,
  contractItemsQuery,
  contractsQuery,
  createContract,
  createContractItem,
  createPricingRule,
  createPricingVersion,
  documentTemplatesQuery,
  downloadFile,
  pricingRuleDetailQuery,
  pricingRulesQuery,
  publishPricingVersion,
  renderContractDocument,
  servicesQuery,
  updateContract,
  updateContractItem,
  validatePricingVersion,
  type Contract,
  type ContractItem,
  type PricingVersion,
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
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Separator } from '@/components/ui/separator'
import { Skeleton } from '@/components/ui/skeleton'
import { Switch } from '@/components/ui/switch'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Textarea } from '@/components/ui/textarea'
import { Main } from '@/components/layout/main'
import { ConsoleHeader } from '@/features/shell/console-header'
import { PageHeading } from '@/features/shell/page-heading'

const billingTypes = [
  ['FIXED_FEE', '固定费'],
  ['QUANTITY', '数量单价'],
  ['COMMITTED_PLUS_OVERAGE', '保底加超量'],
  ['TOTAL_TRAFFIC', '总流量'],
  ['GRADUATED', '累进阶梯'],
  ['VOLUME', '全量阶梯'],
] as const

const emptyContract = {
  contract_no: '',
  company_id: '',
  contract_name: '',
  effective_from: today(),
  effective_to: '',
  auto_renew: false,
  billing_cycle: 'MONTHLY',
  billing_day: '1',
  payment_terms_days: '7',
  currency_code: 'CNY',
  tax_rate: '0',
  tax_inclusive: false,
  notes: '',
}

const emptyItem = {
  contract_item_no: '',
  service_id: '',
  pricing_rule_id: '',
  item_name: '',
  billing_type: 'FIXED_FEE',
  billing_cycle: 'MONTHLY',
  effective_from: localDateTime(),
  effective_to: '',
  default_quantity: '1',
  unit: 'month',
  tax_category: '',
  auto_bill: true,
  visible_on_invoice: true,
  sort_order: '0',
  status: 'ACTIVE',
}

const emptyVersion = {
  effective_from: localDateTime(),
  effective_to: '',
  billing_type: 'FIXED_FEE',
  currency_code: 'CNY',
  unit: 'month',
  unit_price: '',
  base_fee: '',
  committed_quantity: '',
  overage_unit_price: '',
  free_allowance: '',
  minimum_charge: '',
  maximum_charge: '',
  discount_rate: '0',
  tax_rate: '0',
  rounding_mode: 'NONE',
  rounding_scale: '2',
  rounding_step: '',
  proration_mode: 'ACTUAL_DAYS',
  change_note: '',
}

type TierDraft = {
  lower_bound: string
  upper_bound: string
  unit_price: string
}

export function ContractsPage() {
  const queryClient = useQueryClient()
  const contracts = useQuery(contractsQuery)
  const pricing = useQuery(pricingRulesQuery)
  const companies = useQuery(companiesQuery)
  const customers = useQuery(customersQuery())
  const services = useQuery(servicesQuery)
  const [selectedContractId, setSelectedContractId] = useState<string>()
  const [selectedRuleId, setSelectedRuleId] = useState<string>()
  const [createContractOpen, setCreateContractOpen] = useState(false)
  const [createItemOpen, setCreateItemOpen] = useState(false)
  const [createRuleOpen, setCreateRuleOpen] = useState(false)
  const [createVersionOpen, setCreateVersionOpen] = useState(false)
  const [contractForm, setContractForm] = useState(emptyContract)
  const [itemForm, setItemForm] = useState(emptyItem)
  const [ruleForm, setRuleForm] = useState({
    rule_code: '',
    rule_name: '',
    description: '',
  })
  const [versionForm, setVersionForm] = useState(emptyVersion)
  const [tiers, setTiers] = useState<TierDraft[]>([
    { lower_bound: '0', upper_bound: '', unit_price: '' },
  ])
  const [editingContract, setEditingContract] = useState<Contract>()
  const [editingItem, setEditingItem] = useState<ContractItem>()

  const updateContractMutation = useMutation({
    mutationFn: ({
      id,
      version,
      input,
    }: {
      id: string
      version: number
      input: Parameters<typeof updateContract>[2]
    }) => updateContract(id, version, input),
    onSuccess: async () => {
      toast.success('合同已更新')
      setEditingContract(undefined)
      await queryClient.invalidateQueries({ queryKey: ['contracts'] })
    },
    onError: (error) => {
      const problem = problemFrom(error)
      toast.error(problem.detail ?? problem.title ?? '更新合同失败')
    },
  })
  const updateItemMutation = useMutation({
    mutationFn: ({
      id,
      version,
      input,
    }: {
      id: string
      version: number
      input: Parameters<typeof updateContractItem>[2]
    }) => updateContractItem(id, version, input),
    onSuccess: async () => {
      toast.success('计费项已更新')
      setEditingItem(undefined)
      await queryClient.invalidateQueries({ queryKey: ['contract-items'] })
    },
    onError: (error) => {
      const problem = problemFrom(error)
      toast.error(problem.detail ?? problem.title ?? '更新计费项失败')
    },
  })
  const [renderOpen, setRenderOpen] = useState(false)
  const [renderTemplateId, setRenderTemplateId] = useState('')
  const [renderEntityId, setRenderEntityId] = useState('')
  const renderContract = useMutation({
    mutationFn: () =>
      renderContractDocument(selectedContract!.id, {
        templateId: renderTemplateId || undefined,
        billingEntityId: renderEntityId || undefined,
      }),
    onSuccess: async (file) => {
      toast.success('合同文档已生成')
      setRenderOpen(false)
      await downloadFile(file.id, file.filename)
    },
    onError: (error) => {
      const problem = problemFrom(error)
      toast.error(problem.detail ?? problem.title ?? '生成合同失败')
    },
  })

  const selectedContract =
    contracts.data?.find((row) => row.id === selectedContractId) ??
    contracts.data?.[0]
  const selectedRule =
    pricing.data?.find((row) => row.id === selectedRuleId) ?? pricing.data?.[0]
  const contractItems = useQuery(contractItemsQuery(selectedContract?.id))
  const pricingDetail = useQuery(pricingRuleDetailQuery(selectedRule?.id))
  const availableServices =
    services.data?.filter(
      (service) =>
        service.customer_id === selectedContract?.customer_id &&
        service.company_id === selectedContract.company_id
    ) ?? []

  const createContractMutation = useMutation({
    mutationFn: () => {
      const company = companies.data?.find(
        (row) => row.id === contractForm.company_id
      )
      if (!company) throw new Error('请选择签约公司')
      return createContract({
        ...contractForm,
        customer_id: company.customer_id,
        billing_day: Number(contractForm.billing_day),
        payment_terms_days: Number(contractForm.payment_terms_days),
      })
    },
    onSuccess: async (created) => {
      toast.success('合同草稿已创建')
      setSelectedContractId(created.id)
      setCreateContractOpen(false)
      setContractForm(emptyContract)
      await queryClient.invalidateQueries({ queryKey: ['contracts'] })
    },
  })
  const activateMutation = useMutation({
    mutationFn: activateContract,
    onSuccess: async (updated) => {
      toast.success('合同已激活')
      setSelectedContractId(updated.id)
      await queryClient.invalidateQueries({ queryKey: ['contracts'] })
    },
  })
  const createItemMutation = useMutation({
    mutationFn: () =>
      createContractItem(selectedContract!.id, {
        ...itemForm,
        effective_from: toIso(itemForm.effective_from),
        effective_to: itemForm.effective_to
          ? toIso(itemForm.effective_to)
          : undefined,
        sort_order: Number(itemForm.sort_order),
      }),
    onSuccess: async () => {
      toast.success('合同计费项已创建')
      setCreateItemOpen(false)
      setItemForm(emptyItem)
      await queryClient.invalidateQueries({
        queryKey: ['contract-items', selectedContract?.id],
      })
    },
  })
  const createRuleMutation = useMutation({
    mutationFn: () => createPricingRule(ruleForm),
    onSuccess: async (created) => {
      toast.success('价格规则已创建')
      setSelectedRuleId(created.id)
      setCreateRuleOpen(false)
      setRuleForm({ rule_code: '', rule_name: '', description: '' })
      await queryClient.invalidateQueries({ queryKey: ['pricing-rules'] })
    },
  })
  const createVersionMutation = useMutation({
    mutationFn: () =>
      createPricingVersion(selectedRule!.id, {
        ...versionForm,
        effective_from: toIso(versionForm.effective_from),
        effective_to: versionForm.effective_to
          ? toIso(versionForm.effective_to)
          : undefined,
        rounding_scale:
          versionForm.rounding_mode === 'DECIMAL_SCALE'
            ? Number(versionForm.rounding_scale)
            : undefined,
        tiers: isTiered(versionForm.billing_type) ? tiers : [],
      }),
    onSuccess: async () => {
      toast.success('价格草稿版本已创建，请校验后发布')
      setCreateVersionOpen(false)
      setVersionForm(emptyVersion)
      setTiers([{ lower_bound: '0', upper_bound: '', unit_price: '' }])
      await Promise.all([
        queryClient.invalidateQueries({
          queryKey: ['pricing-rule', selectedRule?.id],
        }),
        queryClient.invalidateQueries({ queryKey: ['pricing-rules'] }),
      ])
    },
  })
  const validateMutation = useMutation({
    mutationFn: validatePricingVersion,
    onSuccess: () => toast.success('价格版本校验通过'),
  })
  const publishMutation = useMutation({
    mutationFn: publishPricingVersion,
    onSuccess: async () => {
      toast.success('价格版本已发布并设为当前版本')
      await Promise.all([
        queryClient.invalidateQueries({
          queryKey: ['pricing-rule', selectedRule?.id],
        }),
        queryClient.invalidateQueries({ queryKey: ['pricing-rules'] }),
      ])
    },
  })

  return (
    <>
      <ConsoleHeader label='contracts' />
      <Main className='space-y-7'>
        <PageHeading
          eyebrow='合同与价格'
          title='合同计费控制台'
          description='合同、合同计费项与价格规则保持独立；已发布价格版本不可原地修改，跨有效期账单由计费引擎自动拆段。'
        />
        <Tabs defaultValue='contracts'>
          <TabsList>
            <TabsTrigger value='contracts'>
              <BookOpenCheck />
              合同
            </TabsTrigger>
            <TabsTrigger value='pricing'>
              <GitBranch />
              价格规则
            </TabsTrigger>
          </TabsList>
          <TabsContent value='contracts'>
            <div className='grid gap-5 xl:grid-cols-[1.2fr_.8fr]'>
              <Card className='gap-0 overflow-hidden py-0 shadow-none'>
                <CardHeader className='flex flex-col items-start gap-3 border-b py-5 sm:flex-row sm:items-center sm:justify-between'>
                  <div>
                    <CardTitle className='text-base'>合同台账</CardTitle>
                    <CardDescription className='mt-1'>
                      选择合同后，在右侧维护计费项并执行激活。
                    </CardDescription>
                  </div>
                  <Button size='sm' onClick={() => setCreateContractOpen(true)}>
                    <Plus /> 新建合同
                  </Button>
                </CardHeader>
                <CardContent className='p-0'>
                  {contracts.isLoading ? (
                    <Loading />
                  ) : !contracts.data?.length ? (
                    <Empty
                      icon={<CalendarRange />}
                      text='尚无合同。先准备客户和公司，再创建第一份合同草稿。'
                    />
                  ) : (
                    <Table>
                      <TableHeader>
                        <TableRow className='bg-muted/30'>
                          <TableHead>合同</TableHead>
                          <TableHead>有效期</TableHead>
                          <TableHead>周期 / 币种</TableHead>
                          <TableHead>状态</TableHead>
                        </TableRow>
                      </TableHeader>
                      <TableBody>
                        {contracts.data.map((contract) => (
                          <TableRow
                            key={contract.id}
                            className='cursor-pointer'
                            data-state={
                              contract.id === selectedContract?.id
                                ? 'selected'
                                : undefined
                            }
                            onClick={() => setSelectedContractId(contract.id)}
                          >
                            <TableCell>
                              <p className='font-medium'>
                                {contract.contract_name}
                              </p>
                              <p className='mt-1 font-mono text-[11px] text-muted-foreground'>
                                {contract.contract_no} · v{contract.version}
                              </p>
                            </TableCell>
                            <TableCell className='font-mono text-xs'>
                              {contract.effective_from} →{' '}
                              {contract.effective_to ?? '长期'}
                            </TableCell>
                            <TableCell>
                              <p className='font-mono text-xs'>
                                {contract.billing_cycle}
                              </p>
                              <p className='mt-1 text-xs text-muted-foreground'>
                                {contract.currency_code}
                              </p>
                            </TableCell>
                            <TableCell>
                              <State value={contract.status} />
                            </TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  )}
                </CardContent>
              </Card>
              <ContractInspector
                contract={selectedContract}
                items={contractItems.data ?? []}
                loading={contractItems.isLoading}
                onAddItem={() => {
                  setItemForm({
                    ...emptyItem,
                    effective_from: selectedContract
                      ? `${selectedContract.effective_from}T00:00`
                      : localDateTime(),
                  })
                  setCreateItemOpen(true)
                }}
                onActivate={() =>
                  selectedContract && activateMutation.mutate(selectedContract)
                }
                activating={activateMutation.isPending}
                onEditContract={(contract) => setEditingContract(contract)}
                onEditItem={(item) => setEditingItem(item)}
                onRenderDocument={() => {
                  setRenderTemplateId('')
                  setRenderEntityId('')
                  setRenderOpen(true)
                }}
                rendering={renderContract.isPending}
              />
              <RenderContractDialog
                open={renderOpen}
                contract={selectedContract}
                templateId={renderTemplateId}
                entityId={renderEntityId}
                onTemplateChange={setRenderTemplateId}
                onEntityChange={setRenderEntityId}
                pending={renderContract.isPending}
                onClose={() => setRenderOpen(false)}
                onRender={() => renderContract.mutate()}
              />
            </div>
          </TabsContent>
          <TabsContent value='pricing'>
            <div className='grid gap-5 xl:grid-cols-[.75fr_1.25fr]'>
              <Card className='gap-0 overflow-hidden py-0 shadow-none'>
                <CardHeader className='flex flex-col items-start gap-3 border-b py-5 sm:flex-row sm:items-center sm:justify-between'>
                  <div>
                    <CardTitle className='text-base'>价格规则</CardTitle>
                    <CardDescription className='mt-1'>
                      规则是版本容器，不直接保存可计费金额。
                    </CardDescription>
                  </div>
                  <Button
                    size='sm'
                    variant='outline'
                    onClick={() => setCreateRuleOpen(true)}
                  >
                    <Plus /> 新建规则
                  </Button>
                </CardHeader>
                <CardContent className='space-y-2 p-3'>
                  {pricing.isLoading ? (
                    <Loading />
                  ) : !pricing.data?.length ? (
                    <Empty icon={<Tags />} text='尚无价格规则。' />
                  ) : (
                    pricing.data.map((rule) => (
                      <button
                        key={rule.id}
                        className='w-full rounded-lg border p-4 text-left transition-colors hover:bg-muted/30 data-[selected=true]:border-primary/40 data-[selected=true]:bg-primary/5'
                        data-selected={rule.id === selectedRule?.id}
                        onClick={() => setSelectedRuleId(rule.id)}
                      >
                        <div className='flex items-center justify-between gap-3'>
                          <p className='font-medium'>{rule.rule_name}</p>
                          <State value={rule.status} />
                        </div>
                        <p className='mt-2 font-mono text-[11px] text-muted-foreground'>
                          {rule.rule_code} · 当前版本{' '}
                          {rule.current_version_id?.slice(0, 8) ?? '未发布'}
                        </p>
                      </button>
                    ))
                  )}
                </CardContent>
              </Card>
              <PricingTimeline
                ruleName={selectedRule?.rule_name}
                versions={pricingDetail.data?.versions ?? []}
                loading={pricingDetail.isLoading}
                onCreate={() => setCreateVersionOpen(true)}
                onValidate={(id) => validateMutation.mutate(id)}
                onPublish={(id) => publishMutation.mutate(id)}
                busy={validateMutation.isPending || publishMutation.isPending}
              />
            </div>
          </TabsContent>
        </Tabs>
      </Main>

      <Dialog open={createContractOpen} onOpenChange={setCreateContractOpen}>
        <DialogContent className='max-h-[calc(100svh-2rem)] overflow-y-auto sm:max-w-2xl'>
          <DialogHeader>
            <DialogTitle>创建合同草稿</DialogTitle>
            <DialogDescription>
              公司决定客户归属；合同创建后再逐项绑定业务和价格规则。
            </DialogDescription>
          </DialogHeader>
          <div className='grid gap-4 sm:grid-cols-2'>
            <Field label='合同编号'>
              <Input
                value={contractForm.contract_no}
                onChange={(event) =>
                  setContractForm((current) => ({
                    ...current,
                    contract_no: event.target.value.toUpperCase(),
                  }))
                }
              />
            </Field>
            <Field label='合同名称'>
              <Input
                value={contractForm.contract_name}
                onChange={(event) =>
                  setContractForm((current) => ({
                    ...current,
                    contract_name: event.target.value,
                  }))
                }
              />
            </Field>
            <Field label='签约公司' className='sm:col-span-2'>
              <Select
                value={contractForm.company_id}
                onValueChange={(company_id) =>
                  setContractForm((current) => ({ ...current, company_id }))
                }
              >
                <SelectTrigger className='w-full'>
                  <SelectValue placeholder='选择同一客户下的签约主体' />
                </SelectTrigger>
                <SelectContent>
                  {companies.data?.map((company) => {
                    const customer = customers.data?.data.find(
                      (row) => row.id === company.customer_id
                    )
                    return (
                      <SelectItem key={company.id} value={company.id}>
                        {company.company_name} ·{' '}
                        {customer?.customer_name ?? company.company_code}
                      </SelectItem>
                    )
                  })}
                </SelectContent>
              </Select>
            </Field>
            <Field label='生效日期'>
              <Input
                type='date'
                value={contractForm.effective_from}
                onChange={(event) =>
                  setContractForm((current) => ({
                    ...current,
                    effective_from: event.target.value,
                  }))
                }
              />
            </Field>
            <Field label='结束日期（不含）'>
              <Input
                type='date'
                value={contractForm.effective_to}
                onChange={(event) =>
                  setContractForm((current) => ({
                    ...current,
                    effective_to: event.target.value,
                  }))
                }
              />
            </Field>
            <Field label='账单周期'>
              <Select
                value={contractForm.billing_cycle}
                onValueChange={(billing_cycle) =>
                  setContractForm((current) => ({
                    ...current,
                    billing_cycle,
                  }))
                }
              >
                <SelectTrigger className='w-full'>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value='MONTHLY'>MONTHLY</SelectItem>
                  <SelectItem value='QUARTERLY'>QUARTERLY</SelectItem>
                  <SelectItem value='ANNUAL'>ANNUAL</SelectItem>
                  <SelectItem value='ONE_TIME'>ONE_TIME</SelectItem>
                </SelectContent>
              </Select>
            </Field>
            <Field label='账单日（1-28）'>
              <Input
                type='number'
                min='1'
                max='28'
                value={contractForm.billing_day}
                onChange={(event) =>
                  setContractForm((current) => ({
                    ...current,
                    billing_day: event.target.value,
                  }))
                }
              />
            </Field>
            <Field label='币种'>
              <CurrencySelect
                value={contractForm.currency_code}
                onChange={(currency_code) =>
                  setContractForm((current) => ({
                    ...current,
                    currency_code,
                  }))
                }
              />
            </Field>
            <Field label='付款期限（天）'>
              <Input
                type='number'
                min='0'
                value={contractForm.payment_terms_days}
                onChange={(event) =>
                  setContractForm((current) => ({
                    ...current,
                    payment_terms_days: event.target.value,
                  }))
                }
              />
            </Field>
            <Field label='税率（0-1）'>
              <Input
                inputMode='decimal'
                value={contractForm.tax_rate}
                onChange={(event) =>
                  setContractForm((current) => ({
                    ...current,
                    tax_rate: event.target.value,
                  }))
                }
              />
            </Field>
            <div className='flex items-center justify-between rounded-lg border p-3'>
              <Label>含税价格</Label>
              <Switch
                checked={contractForm.tax_inclusive}
                onCheckedChange={(tax_inclusive) =>
                  setContractForm((current) => ({
                    ...current,
                    tax_inclusive,
                  }))
                }
              />
            </div>
            <div className='flex items-center justify-between rounded-lg border p-3'>
              <Label>自动续约</Label>
              <Switch
                checked={contractForm.auto_renew}
                onCheckedChange={(auto_renew) =>
                  setContractForm((current) => ({
                    ...current,
                    auto_renew,
                  }))
                }
              />
            </div>
            <Field label='备注' className='sm:col-span-2'>
              <Textarea
                value={contractForm.notes}
                onChange={(event) =>
                  setContractForm((current) => ({
                    ...current,
                    notes: event.target.value,
                  }))
                }
              />
            </Field>
          </div>
          <DialogFooter>
            <Button
              variant='outline'
              onClick={() => setCreateContractOpen(false)}
            >
              取消
            </Button>
            <Button
              disabled={
                createContractMutation.isPending ||
                !contractForm.contract_no ||
                !contractForm.contract_name ||
                !contractForm.company_id ||
                !contractForm.effective_from
              }
              onClick={() => createContractMutation.mutate()}
            >
              创建草稿
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={createItemOpen} onOpenChange={setCreateItemOpen}>
        <DialogContent className='max-h-[calc(100svh-2rem)] overflow-y-auto sm:max-w-2xl'>
          <DialogHeader>
            <DialogTitle>新增合同计费项</DialogTitle>
            <DialogDescription>
              计费项是自动出账最小单位。业务必须属于合同的客户和公司。
            </DialogDescription>
          </DialogHeader>
          <div className='grid gap-4 sm:grid-cols-2'>
            <Field label='计费项编号'>
              <Input
                value={itemForm.contract_item_no}
                onChange={(event) =>
                  setItemForm((current) => ({
                    ...current,
                    contract_item_no: event.target.value.toUpperCase(),
                  }))
                }
              />
            </Field>
            <Field label='显示名称'>
              <Input
                value={itemForm.item_name}
                onChange={(event) =>
                  setItemForm((current) => ({
                    ...current,
                    item_name: event.target.value,
                  }))
                }
              />
            </Field>
            <Field label='关联业务' className='sm:col-span-2'>
              <Select
                value={itemForm.service_id}
                onValueChange={(service_id) =>
                  setItemForm((current) => ({ ...current, service_id }))
                }
              >
                <SelectTrigger className='w-full'>
                  <SelectValue placeholder='选择合同主体下的业务' />
                </SelectTrigger>
                <SelectContent>
                  {availableServices.map((service) => (
                    <SelectItem key={service.id} value={service.id}>
                      {service.service_no} · {service.service_name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </Field>
            <Field label='价格规则' className='sm:col-span-2'>
              <Select
                value={itemForm.pricing_rule_id}
                onValueChange={(pricing_rule_id) =>
                  setItemForm((current) => ({
                    ...current,
                    pricing_rule_id,
                  }))
                }
              >
                <SelectTrigger className='w-full'>
                  <SelectValue placeholder='选择已准备版本的价格规则' />
                </SelectTrigger>
                <SelectContent>
                  {pricing.data?.map((rule) => (
                    <SelectItem key={rule.id} value={rule.id}>
                      {rule.rule_code} · {rule.rule_name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </Field>
            <Field label='计费类型'>
              <BillingTypeSelect
                value={itemForm.billing_type}
                onChange={(billing_type) =>
                  setItemForm((current) => ({ ...current, billing_type }))
                }
              />
            </Field>
            <Field label='单位'>
              <Input
                value={itemForm.unit}
                onChange={(event) =>
                  setItemForm((current) => ({
                    ...current,
                    unit: event.target.value,
                  }))
                }
              />
            </Field>
            <Field label='默认数量'>
              <Input
                inputMode='decimal'
                value={itemForm.default_quantity}
                onChange={(event) =>
                  setItemForm((current) => ({
                    ...current,
                    default_quantity: event.target.value,
                  }))
                }
              />
            </Field>
            <Field label='税务分类'>
              <Input
                value={itemForm.tax_category}
                onChange={(event) =>
                  setItemForm((current) => ({
                    ...current,
                    tax_category: event.target.value,
                  }))
                }
              />
            </Field>
            <Field label='生效时刻'>
              <Input
                type='datetime-local'
                value={itemForm.effective_from}
                onChange={(event) =>
                  setItemForm((current) => ({
                    ...current,
                    effective_from: event.target.value,
                  }))
                }
              />
            </Field>
            <Field label='结束时刻（不含）'>
              <Input
                type='datetime-local'
                value={itemForm.effective_to}
                onChange={(event) =>
                  setItemForm((current) => ({
                    ...current,
                    effective_to: event.target.value,
                  }))
                }
              />
            </Field>
            <div className='flex items-center justify-between rounded-lg border p-3'>
              <Label>自动出账</Label>
              <Switch
                checked={itemForm.auto_bill}
                onCheckedChange={(auto_bill) =>
                  setItemForm((current) => ({ ...current, auto_bill }))
                }
              />
            </div>
            <div className='flex items-center justify-between rounded-lg border p-3'>
              <Label>账单可见</Label>
              <Switch
                checked={itemForm.visible_on_invoice}
                onCheckedChange={(visible_on_invoice) =>
                  setItemForm((current) => ({
                    ...current,
                    visible_on_invoice,
                  }))
                }
              />
            </div>
          </div>
          {!availableServices.length && (
            <p className='rounded-lg border border-dashed p-3 text-sm text-muted-foreground'>
              当前合同主体下没有业务，请先在“业务与服务资源”页面创建业务。
            </p>
          )}
          <DialogFooter>
            <Button
              disabled={
                createItemMutation.isPending ||
                !itemForm.contract_item_no ||
                !itemForm.item_name ||
                !itemForm.service_id ||
                !itemForm.pricing_rule_id ||
                !itemForm.effective_from
              }
              onClick={() => createItemMutation.mutate()}
            >
              创建计费项
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={createRuleOpen} onOpenChange={setCreateRuleOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>创建价格规则</DialogTitle>
            <DialogDescription>
              创建后继续添加不可变版本；规则本身不产生金额。
            </DialogDescription>
          </DialogHeader>
          <Field label='规则代码'>
            <Input
              value={ruleForm.rule_code}
              onChange={(event) =>
                setRuleForm((current) => ({
                  ...current,
                  rule_code: event.target.value.toUpperCase(),
                }))
              }
            />
          </Field>
          <Field label='规则名称'>
            <Input
              value={ruleForm.rule_name}
              onChange={(event) =>
                setRuleForm((current) => ({
                  ...current,
                  rule_name: event.target.value,
                }))
              }
            />
          </Field>
          <Field label='说明'>
            <Textarea
              value={ruleForm.description}
              onChange={(event) =>
                setRuleForm((current) => ({
                  ...current,
                  description: event.target.value,
                }))
              }
            />
          </Field>
          <DialogFooter>
            <Button
              disabled={
                createRuleMutation.isPending ||
                ruleForm.rule_code.length < 3 ||
                !ruleForm.rule_name
              }
              onClick={() => createRuleMutation.mutate()}
            >
              创建规则
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={createVersionOpen} onOpenChange={setCreateVersionOpen}>
        <DialogContent className='max-h-[calc(100svh-2rem)] overflow-y-auto sm:max-w-3xl'>
          <DialogHeader>
            <DialogTitle>创建价格版本</DialogTitle>
            <DialogDescription>
              有效期使用半开区间。发布后版本不可编辑，变更必须创建新版本。
            </DialogDescription>
          </DialogHeader>
          <div className='grid gap-4 sm:grid-cols-2'>
            <Field label='计费类型'>
              <BillingTypeSelect
                value={versionForm.billing_type}
                onChange={(billing_type) =>
                  setVersionForm((current) => ({
                    ...current,
                    billing_type,
                  }))
                }
              />
            </Field>
            <Field label='币种'>
              <CurrencySelect
                value={versionForm.currency_code}
                onChange={(currency_code) =>
                  setVersionForm((current) => ({
                    ...current,
                    currency_code,
                  }))
                }
              />
            </Field>
            <Field label='生效时刻'>
              <Input
                type='datetime-local'
                value={versionForm.effective_from}
                onChange={(event) =>
                  setVersionForm((current) => ({
                    ...current,
                    effective_from: event.target.value,
                  }))
                }
              />
            </Field>
            <Field label='结束时刻（不含）'>
              <Input
                type='datetime-local'
                value={versionForm.effective_to}
                onChange={(event) =>
                  setVersionForm((current) => ({
                    ...current,
                    effective_to: event.target.value,
                  }))
                }
              />
            </Field>
            <Field label='单位'>
              <Input
                value={versionForm.unit}
                onChange={(event) =>
                  setVersionForm((current) => ({
                    ...current,
                    unit: event.target.value,
                  }))
                }
              />
            </Field>
            <Field label='折算方式'>
              <Select
                value={versionForm.proration_mode}
                onValueChange={(proration_mode) =>
                  setVersionForm((current) => ({
                    ...current,
                    proration_mode,
                  }))
                }
              >
                <SelectTrigger className='w-full'>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value='ACTUAL_DAYS'>按实际天数</SelectItem>
                  <SelectItem value='THIRTY_DAYS'>固定 30 天</SelectItem>
                  <SelectItem value='NO_PRORATION'>不折算</SelectItem>
                  <SelectItem value='FULL_MONTH_IF_ACTIVE'>
                    有效即整月
                  </SelectItem>
                </SelectContent>
              </Select>
            </Field>
          </div>

          <Separator />
          <div className='grid gap-4 sm:grid-cols-2'>
            {versionForm.billing_type === 'FIXED_FEE' && (
              <DecimalInput
                label='固定费用'
                value={versionForm.base_fee}
                onChange={(base_fee) =>
                  setVersionForm((current) => ({ ...current, base_fee }))
                }
              />
            )}
            {['QUANTITY', 'TOTAL_TRAFFIC'].includes(
              versionForm.billing_type
            ) && (
              <DecimalInput
                label='单位价格'
                value={versionForm.unit_price}
                onChange={(unit_price) =>
                  setVersionForm((current) => ({ ...current, unit_price }))
                }
              />
            )}
            {versionForm.billing_type === 'TOTAL_TRAFFIC' && (
              <>
                <DecimalInput
                  label='基础费用（可选）'
                  value={versionForm.base_fee}
                  onChange={(base_fee) =>
                    setVersionForm((current) => ({ ...current, base_fee }))
                  }
                />
                <DecimalInput
                  label='免费额度（可选）'
                  value={versionForm.free_allowance}
                  onChange={(free_allowance) =>
                    setVersionForm((current) => ({
                      ...current,
                      free_allowance,
                    }))
                  }
                />
              </>
            )}
            {versionForm.billing_type === 'COMMITTED_PLUS_OVERAGE' && (
              <>
                <DecimalInput
                  label='基础费用'
                  value={versionForm.base_fee}
                  onChange={(base_fee) =>
                    setVersionForm((current) => ({ ...current, base_fee }))
                  }
                />
                <DecimalInput
                  label='保底数量'
                  value={versionForm.committed_quantity}
                  onChange={(committed_quantity) =>
                    setVersionForm((current) => ({
                      ...current,
                      committed_quantity,
                    }))
                  }
                />
                <DecimalInput
                  label='超量单价'
                  value={versionForm.overage_unit_price}
                  onChange={(overage_unit_price) =>
                    setVersionForm((current) => ({
                      ...current,
                      overage_unit_price,
                    }))
                  }
                />
              </>
            )}
          </div>

          {isTiered(versionForm.billing_type) && (
            <div className='space-y-3 rounded-xl border bg-muted/10 p-4'>
              <div className='flex items-center justify-between'>
                <div>
                  <p className='font-medium'>阶梯价格</p>
                  <p className='mt-1 text-xs text-muted-foreground'>
                    从 0 开始连续，只有最后一档可以不填上限。
                  </p>
                </div>
                <Button
                  size='sm'
                  variant='outline'
                  onClick={() =>
                    setTiers((current) => [
                      ...current,
                      {
                        lower_bound:
                          current[current.length - 1]?.upper_bound ?? '',
                        upper_bound: '',
                        unit_price: '',
                      },
                    ])
                  }
                >
                  <Plus /> 加一档
                </Button>
              </div>
              {tiers.map((tier, index) => (
                <div
                  key={index}
                  className='grid gap-3 rounded-lg border bg-background p-3 sm:grid-cols-[1fr_1fr_1fr_auto]'
                >
                  <DecimalInput
                    label={`第 ${index + 1} 档起点`}
                    value={tier.lower_bound}
                    onChange={(lower_bound) =>
                      updateTier(setTiers, index, { lower_bound })
                    }
                  />
                  <DecimalInput
                    label='上限（不含）'
                    value={tier.upper_bound}
                    onChange={(upper_bound) =>
                      updateTier(setTiers, index, { upper_bound })
                    }
                  />
                  <DecimalInput
                    label='单位价格'
                    value={tier.unit_price}
                    onChange={(unit_price) =>
                      updateTier(setTiers, index, { unit_price })
                    }
                  />
                  <Button
                    className='self-end'
                    size='icon'
                    variant='ghost'
                    disabled={tiers.length === 1}
                    onClick={() =>
                      setTiers((current) =>
                        current.filter((_, tierIndex) => tierIndex !== index)
                      )
                    }
                  >
                    <Trash2 />
                  </Button>
                </div>
              ))}
            </div>
          )}

          <div className='grid gap-4 sm:grid-cols-2'>
            <DecimalInput
              label='最低消费（可选）'
              value={versionForm.minimum_charge}
              onChange={(minimum_charge) =>
                setVersionForm((current) => ({
                  ...current,
                  minimum_charge,
                }))
              }
            />
            <DecimalInput
              label='封顶金额（可选）'
              value={versionForm.maximum_charge}
              onChange={(maximum_charge) =>
                setVersionForm((current) => ({
                  ...current,
                  maximum_charge,
                }))
              }
            />
            <DecimalInput
              label='折扣率（0-1）'
              value={versionForm.discount_rate}
              onChange={(discount_rate) =>
                setVersionForm((current) => ({ ...current, discount_rate }))
              }
            />
            <DecimalInput
              label='税率（0-1）'
              value={versionForm.tax_rate}
              onChange={(tax_rate) =>
                setVersionForm((current) => ({ ...current, tax_rate }))
              }
            />
            <Field label='取整方式'>
              <Select
                value={versionForm.rounding_mode}
                onValueChange={(rounding_mode) =>
                  setVersionForm((current) => ({
                    ...current,
                    rounding_mode,
                  }))
                }
              >
                <SelectTrigger className='w-full'>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value='NONE'>不取整</SelectItem>
                  <SelectItem value='DECIMAL_SCALE'>小数位</SelectItem>
                  <SelectItem value='HALF_UP_INTEGER'>
                    四舍五入到整数
                  </SelectItem>
                  <SelectItem value='CEIL_INTEGER'>向上到整数</SelectItem>
                  <SelectItem value='CEIL_STEP'>向上到步长</SelectItem>
                </SelectContent>
              </Select>
            </Field>
            {versionForm.rounding_mode === 'DECIMAL_SCALE' && (
              <Field label='小数位'>
                <Input
                  type='number'
                  min='0'
                  value={versionForm.rounding_scale}
                  onChange={(event) =>
                    setVersionForm((current) => ({
                      ...current,
                      rounding_scale: event.target.value,
                    }))
                  }
                />
              </Field>
            )}
            {versionForm.rounding_mode === 'CEIL_STEP' && (
              <DecimalInput
                label='取整步长'
                value={versionForm.rounding_step}
                onChange={(rounding_step) =>
                  setVersionForm((current) => ({ ...current, rounding_step }))
                }
              />
            )}
            <Field label='变更说明' className='sm:col-span-2'>
              <Textarea
                value={versionForm.change_note}
                onChange={(event) =>
                  setVersionForm((current) => ({
                    ...current,
                    change_note: event.target.value,
                  }))
                }
                placeholder='说明定价依据、审批单或生效原因'
              />
            </Field>
          </div>
          <DialogFooter>
            <Button
              disabled={
                createVersionMutation.isPending ||
                !selectedRule ||
                !versionReady(versionForm, tiers)
              }
              onClick={() => createVersionMutation.mutate()}
            >
              创建草稿版本
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
      <ContractEditDialog
        key={editingContract?.id ?? 'closed'}
        contract={editingContract}
        pending={updateContractMutation.isPending}
        onClose={() => setEditingContract(undefined)}
        onSubmit={(id, version, input) =>
          updateContractMutation.mutate({ id, version, input })
        }
      />
      <ItemEditDialog
        key={editingItem?.id ?? 'closed'}
        item={editingItem}
        pending={updateItemMutation.isPending}
        onClose={() => setEditingItem(undefined)}
        onSubmit={(id, version, input) =>
          updateItemMutation.mutate({ id, version, input })
        }
      />
    </>
  )
}

function ContractInspector({
  contract,
  items,
  loading,
  onAddItem,
  onActivate,
  activating,
  onEditContract,
  onEditItem,
  onRenderDocument,
  rendering,
}: {
  contract?: Contract
  items: ContractItem[]
  loading: boolean
  onAddItem: () => void
  onActivate: () => void
  activating: boolean
  onEditContract: (contract: Contract) => void
  onEditItem: (item: ContractItem) => void
  onRenderDocument: () => void
  rendering: boolean
}) {
  if (!contract) {
    return (
      <Card className='shadow-none'>
        <Empty icon={<Layers3 />} text='选择合同查看计费项。' />
      </Card>
    )
  }
  return (
    <Card className='shadow-none'>
      <CardHeader>
        <div className='flex items-start justify-between gap-4'>
          <div>
            <CardTitle className='text-base'>
              {contract.contract_name}
            </CardTitle>
            <CardDescription className='mt-1 font-mono'>
              {contract.contract_no} · v{contract.version}
            </CardDescription>
          </div>
          <div className='flex shrink-0 flex-wrap items-center justify-end gap-2'>
            <State value={contract.status} />
            <Button
              size='sm'
              variant='outline'
              onClick={() => onEditContract(contract)}
            >
              <Pencil /> 编辑
            </Button>
            <Button
              size='sm'
              disabled={rendering}
              onClick={onRenderDocument}
            >
              <FileText /> 生成合同
            </Button>
          </div>
        </div>
      </CardHeader>
      <CardContent className='space-y-5'>
        <div className='grid grid-cols-2 gap-3 text-sm'>
          <Fact label='账期' value={contract.billing_cycle} />
          <Fact label='付款期限' value={`${contract.payment_terms_days} 天`} />
          <Fact label='币种' value={contract.currency_code} />
          <Fact
            label='税务'
            value={`${contract.tax_rate ?? '0'} · ${contract.tax_inclusive ? '含税' : '未税'}`}
          />
        </div>
        <Separator />
        <div className='flex items-center justify-between'>
          <div>
            <p className='font-medium'>合同计费项</p>
            <p className='mt-1 text-xs text-muted-foreground'>
              自动出账最小单位，共 {items.length} 项。
            </p>
          </div>
          <Button size='sm' variant='outline' onClick={onAddItem}>
            <Plus /> 新增计费项
          </Button>
        </div>
        {loading ? (
          <Loading />
        ) : !items.length ? (
          <p className='rounded-lg border border-dashed p-4 text-sm text-muted-foreground'>
            尚无计费项。合同可以先保存为草稿，再按业务逐项补齐。
          </p>
        ) : (
          <div className='space-y-2'>
            {items.map((item) => (
              <div key={item.id} className='rounded-lg border p-3'>
                <div className='flex items-center justify-between gap-3'>
                  <p className='font-medium'>{item.item_name}</p>
                  <div className='flex shrink-0 items-center gap-2'>
                    <State value={item.status} />
                    <Button
                      size='icon'
                      variant='ghost'
                      aria-label='编辑计费项'
                      onClick={() => onEditItem(item)}
                    >
                      <Pencil className='size-3.5' />
                    </Button>
                  </div>
                </div>
                <p className='mt-2 font-mono text-[11px] text-muted-foreground'>
                  {item.contract_item_no} · {item.billing_type}
                </p>
                <p className='mt-1 font-mono text-[11px] text-muted-foreground'>
                  {item.effective_from} → {item.effective_to ?? '长期'}
                </p>
              </div>
            ))}
          </div>
        )}
        {['DRAFT', 'PENDING_APPROVAL'].includes(contract.status) && (
          <Button className='w-full' disabled={activating} onClick={onActivate}>
            <CheckCircle2 /> 激活合同
          </Button>
        )}
      </CardContent>
    </Card>
  )
}

function RenderContractDialog({
  open,
  contract,
  templateId,
  entityId,
  onTemplateChange,
  onEntityChange,
  pending,
  onClose,
  onRender,
}: {
  open: boolean
  contract?: Contract
  templateId: string
  entityId: string
  onTemplateChange: (id: string) => void
  onEntityChange: (id: string) => void
  pending: boolean
  onClose: () => void
  onRender: () => void
}) {
  const contractTemplates = useQuery(documentTemplatesQuery('CONTRACT_DOCX'))
  const entities = useQuery(billingEntitiesQuery)
  const companies = useQuery(companiesQuery)
  const partyB = companies.data?.find(
    (company) => company.id === contract?.company_id
  )
  const partyA = entities.data?.find((entity) => entity.id === entityId)
  const defaultEntity = entities.data?.find(
    (entity) => entity.status === 'ACTIVE'
  )
  const templates = (contractTemplates.data ?? []).filter(
    (template) => template.status === 'ACTIVE'
  )
  return (
    <Dialog open={open} onOpenChange={(next) => !next && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>生成合同 · {contract?.contract_no}</DialogTitle>
          <DialogDescription>
            甲方取自公司抬头(出账主体)，乙方取自客户公司；模板来自模板中心，字段用
            {'{{变量名}}'} 自动替换。
          </DialogDescription>
        </DialogHeader>
        <div className='grid gap-4'>
          <div className='grid gap-3 sm:grid-cols-2'>
            <div className='rounded-lg border bg-muted/40 p-3'>
              <p className='text-[11px] text-muted-foreground'>甲方</p>
              <p className='mt-1 font-medium'>
                {partyA?.entity_name ?? defaultEntity?.entity_name ?? '未配置'}
              </p>
              <p className='mt-1 font-mono text-[11px] text-muted-foreground'>
                {partyA?.entity_code ??
                  defaultEntity?.entity_code ??
                  '请先在公司抬头维护主体'}
              </p>
            </div>
            <div className='rounded-lg border bg-muted/40 p-3'>
              <p className='text-[11px] text-muted-foreground'>乙方</p>
              <p className='mt-1 font-medium'>
                {partyB?.company_name ?? '未指定'}
              </p>
              <p className='mt-1 font-mono text-[11px] text-muted-foreground'>
                {partyB?.invoice_title ?? partyB?.company_code ?? ''}
              </p>
            </div>
          </div>
          <div className='space-y-2'>
            <Label>甲方(出账主体)</Label>
            <select
              value={entityId || (defaultEntity?.id ?? '')}
              onChange={(event) => onEntityChange(event.target.value)}
              className='h-9 w-full rounded-md border bg-background px-3 text-sm'
            >
              {!entityId && !defaultEntity && (
                <option value=''>请先配置公司抬头</option>
              )}
              {(entities.data ?? [])
                .filter((entity) => entity.status === 'ACTIVE')
                .map((entity) => (
                  <option key={entity.id} value={entity.id}>
                    {entity.entity_name}({entity.entity_code})
                  </option>
                ))}
            </select>
          </div>
          <div className='space-y-2'>
            <Label>合同模板(模板中心)</Label>
            <select
              value={templateId}
              onChange={(event) => onTemplateChange(event.target.value)}
              className='h-9 w-full rounded-md border bg-background px-3 text-sm'
            >
              <option value=''>选择 Word 合同模板</option>
              {templates.map((template) => (
                <option key={template.id} value={template.id}>
                  {template.template_name}({template.template_code})
                </option>
              ))}
            </select>
            {!templates.length && (
              <p className='text-xs text-muted-foreground'>
                模板中心暂无启用的合同模板，请先上传 Word 模板。
              </p>
            )}
          </div>
        </div>
        <DialogFooter>
          <Button variant='outline' onClick={onClose}>
            取消
          </Button>
          <Button disabled={pending || !templateId} onClick={onRender}>
            {pending ? '生成中…' : '生成合同'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function PricingTimeline({
  ruleName,
  versions,
  loading,
  onCreate,
  onValidate,
  onPublish,
  busy,
}: {
  ruleName?: string
  versions: PricingVersion[]
  loading: boolean
  onCreate: () => void
  onValidate: (id: string) => void
  onPublish: (id: string) => void
  busy: boolean
}) {
  return (
    <Card className='shadow-none'>
      <CardHeader className='flex flex-col items-start gap-3 sm:flex-row sm:justify-between'>
        <div>
          <CardTitle className='text-base'>
            {ruleName ? `${ruleName} · 版本时间轴` : '版本时间轴'}
          </CardTitle>
          <CardDescription className='mt-1'>
            发布时后端校验有效期重叠、必填参数和阶梯连续性。
          </CardDescription>
        </div>
        <Button size='sm' disabled={!ruleName} onClick={onCreate}>
          <Plus /> 新建版本
        </Button>
      </CardHeader>
      <CardContent>
        {loading ? (
          <Loading />
        ) : !versions.length ? (
          <Empty
            icon={<CircleDollarSign />}
            text='尚无价格版本。创建草稿、校验后再发布。'
          />
        ) : (
          <div className='relative space-y-4 before:absolute before:top-3 before:bottom-3 before:left-[7px] before:w-px before:bg-border'>
            {versions.map((version) => (
              <div key={version.id} className='relative pl-8'>
                <span
                  className={`absolute top-2 left-0 size-[15px] rounded-full border-4 border-background ${
                    version.status === 'PUBLISHED'
                      ? 'bg-emerald-500'
                      : version.status === 'RETIRED'
                        ? 'bg-muted-foreground'
                        : 'bg-amber-500'
                  }`}
                />
                <div className='rounded-xl border p-4'>
                  <div className='flex flex-wrap items-start justify-between gap-3'>
                    <div>
                      <p className='font-medium'>
                        v{version.version_no} · {version.billing_type}
                      </p>
                      <p className='mt-1 font-mono text-[11px] text-muted-foreground'>
                        {version.effective_from} →{' '}
                        {version.effective_to ?? '长期'}
                      </p>
                    </div>
                    <State value={version.status} />
                  </div>
                  <div className='mt-4 grid gap-2 text-xs sm:grid-cols-3'>
                    <Fact label='价格' value={priceSummary(version)} />
                    <Fact
                      label='最低 / 封顶'
                      value={`${version.minimum_charge ?? '—'} / ${version.maximum_charge ?? '—'}`}
                    />
                    <Fact label='取整' value={version.rounding_mode} />
                  </div>
                  {version.tiers.length > 0 && (
                    <div className='mt-3 flex flex-wrap gap-2'>
                      {version.tiers.map((tier, index) => (
                        <Badge key={index} variant='outline'>
                          [{tier.lower_bound}, {tier.upper_bound ?? '∞'}) →{' '}
                          {tier.unit_price}
                        </Badge>
                      ))}
                    </div>
                  )}
                  {version.change_note && (
                    <p className='mt-3 text-sm text-muted-foreground'>
                      {version.change_note}
                    </p>
                  )}
                  {version.status === 'DRAFT' && (
                    <div className='mt-4 flex justify-end gap-2'>
                      <Button
                        size='sm'
                        variant='outline'
                        disabled={busy}
                        onClick={() => onValidate(version.id)}
                      >
                        校验
                      </Button>
                      <Button
                        size='sm'
                        disabled={busy}
                        onClick={() => onPublish(version.id)}
                      >
                        发布 <ArrowRight />
                      </Button>
                    </div>
                  )}
                </div>
              </div>
            ))}
          </div>
        )}
      </CardContent>
    </Card>
  )
}

function Field({
  label,
  children,
  className,
}: {
  label: string
  children: ReactNode
  className?: string
}) {
  return (
    <div className={['space-y-2', className].filter(Boolean).join(' ')}>
      <Label>{label}</Label>
      {children}
    </div>
  )
}

function DecimalInput({
  label,
  value,
  onChange,
}: {
  label: string
  value: string
  onChange: (value: string) => void
}) {
  return (
    <Field label={label}>
      <Input
        inputMode='decimal'
        value={value}
        onChange={(event) => onChange(event.target.value)}
      />
    </Field>
  )
}

function BillingTypeSelect({
  value,
  onChange,
}: {
  value: string
  onChange: (value: string) => void
}) {
  return (
    <Select value={value} onValueChange={onChange}>
      <SelectTrigger className='w-full'>
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        {billingTypes.map(([type, label]) => (
          <SelectItem key={type} value={type}>
            {label} · {type}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  )
}

function CurrencySelect({
  value,
  onChange,
}: {
  value: string
  onChange: (value: string) => void
}) {
  return (
    <Select value={value} onValueChange={onChange}>
      <SelectTrigger className='w-full'>
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        {['CNY', 'USD', 'HKD', 'JPY'].map((currency) => (
          <SelectItem key={currency} value={currency}>
            {currency}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  )
}

function Fact({ label, value }: { label: string; value: string }) {
  return (
    <div className='rounded-lg bg-muted/40 p-3'>
      <p className='text-[11px] text-muted-foreground'>{label}</p>
      <p className='mt-1 font-mono text-xs'>{value}</p>
    </div>
  )
}

function State({ value }: { value: string }) {
  return (
    <Badge
      variant={
        ['ACTIVE', 'PUBLISHED'].includes(value)
          ? 'default'
          : ['DRAFT', 'PENDING_APPROVAL'].includes(value)
            ? 'outline'
            : 'secondary'
      }
    >
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

function Empty({ icon, text }: { icon: ReactNode; text: string }) {
  return (
    <div className='grid place-items-center py-16 text-center'>
      <span className='text-muted-foreground'>{icon}</span>
      <p className='mt-4 text-sm text-muted-foreground'>{text}</p>
    </div>
  )
}

function isTiered(type: string) {
  return ['GRADUATED', 'VOLUME'].includes(type)
}

function versionReady(form: typeof emptyVersion, draftTiers: TierDraft[]) {
  if (!form.effective_from || !form.change_note) return false
  if (form.rounding_mode === 'CEIL_STEP' && !form.rounding_step) return false
  if (form.billing_type === 'FIXED_FEE') return Boolean(form.base_fee)
  if (['QUANTITY', 'TOTAL_TRAFFIC'].includes(form.billing_type)) {
    return Boolean(form.unit_price)
  }
  if (form.billing_type === 'COMMITTED_PLUS_OVERAGE') {
    return Boolean(
      form.base_fee && form.committed_quantity && form.overage_unit_price
    )
  }
  return draftTiers.every(
    (tier, index) =>
      tier.lower_bound !== '' &&
      tier.unit_price !== '' &&
      (index === draftTiers.length - 1 || tier.upper_bound !== '')
  )
}

function updateTier(
  setTiers: React.Dispatch<React.SetStateAction<TierDraft[]>>,
  index: number,
  patch: Partial<TierDraft>
) {
  setTiers((current) =>
    current.map((tier, tierIndex) =>
      tierIndex === index ? { ...tier, ...patch } : tier
    )
  )
}

function priceSummary(version: PricingVersion) {
  if (version.billing_type === 'FIXED_FEE') {
    return `${version.currency_code} ${version.base_fee ?? '—'}`
  }
  if (version.billing_type === 'COMMITTED_PLUS_OVERAGE') {
    return `${version.base_fee ?? '—'} + ${version.overage_unit_price ?? '—'}/${version.unit ?? 'unit'}`
  }
  if (isTiered(version.billing_type)) {
    return `${version.tiers.length} 档 · ${version.currency_code}`
  }
  return `${version.currency_code} ${version.unit_price ?? '—'}/${version.unit ?? 'unit'}`
}

function today() {
  return new Date().toISOString().slice(0, 10)
}

function localDateTime() {
  const value = new Date(Date.now() - new Date().getTimezoneOffset() * 60_000)
  return value.toISOString().slice(0, 16)
}

function toIso(value: string) {
  return new Date(value).toISOString()
}

function ContractEditDialog({
  contract,
  pending,
  onClose,
  onSubmit,
}: {
  contract?: Contract
  pending: boolean
  onClose: () => void
  onSubmit: (
    id: string,
    version: number,
    input: {
      contract_name?: string
      effective_from?: string
      effective_to?: string
      auto_renew?: boolean
      billing_day?: number
      payment_terms_days?: number
      tax_rate?: string
      tax_inclusive?: boolean
      notes?: string
      reason: string
    }
  ) => void
}) {
  const [name, setName] = useState(contract?.contract_name ?? '')
  const [effectiveFrom, setEffectiveFrom] = useState(
    contract?.effective_from ?? ''
  )
  const [effectiveTo, setEffectiveTo] = useState(contract?.effective_to ?? '')
  const [billingDay, setBillingDay] = useState(
    String(contract?.billing_day ?? 1)
  )
  const [paymentTerms, setPaymentTerms] = useState(
    String(contract?.payment_terms_days ?? 30)
  )
  const [taxRate, setTaxRate] = useState(contract?.tax_rate ?? '')
  const [taxInclusive, setTaxInclusive] = useState(
    contract?.tax_inclusive ?? false
  )
  const [notes, setNotes] = useState(contract?.notes ?? '')
  if (!contract) return null

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>编辑合同 · {contract.contract_no}</DialogTitle>
          <DialogDescription>
            税率与账期变化会影响之后的预览计算,已审批内容自动失效。
          </DialogDescription>
        </DialogHeader>
        <div className='grid gap-4 sm:grid-cols-2'>
          <div className='space-y-2 sm:col-span-2'>
            <Label>合同名称</Label>
            <Input value={name} onChange={(e) => setName(e.target.value)} />
          </div>
          <div className='space-y-2'>
            <Label>生效日期</Label>
            <Input
              type='date'
              value={effectiveFrom}
              onChange={(e) => setEffectiveFrom(e.target.value)}
            />
          </div>
          <div className='space-y-2'>
            <Label>结束日期(可空)</Label>
            <Input
              type='date'
              value={effectiveTo}
              onChange={(e) => setEffectiveTo(e.target.value)}
            />
          </div>
          <div className='space-y-2'>
            <Label>出账日(1-28)</Label>
            <Input
              className='font-mono'
              value={billingDay}
              onChange={(e) => setBillingDay(e.target.value)}
            />
          </div>
          <div className='space-y-2'>
            <Label>付款期限(天)</Label>
            <Input
              className='font-mono'
              value={paymentTerms}
              onChange={(e) => setPaymentTerms(e.target.value)}
            />
          </div>
          <div className='space-y-2'>
            <Label>税率(如 0.06)</Label>
            <Input
              className='font-mono'
              value={taxRate}
              onChange={(e) => setTaxRate(e.target.value)}
            />
          </div>
          <div className='space-y-2'>
            <Label>含税</Label>
            <select
              value={taxInclusive ? 'yes' : 'no'}
              onChange={(e) => setTaxInclusive(e.target.value === 'yes')}
              className='h-9 w-full rounded-md border bg-background px-3 text-sm'
            >
              <option value='no'>未税</option>
              <option value='yes'>含税</option>
            </select>
          </div>
          <div className='space-y-2 sm:col-span-2'>
            <Label>备注</Label>
            <Input value={notes} onChange={(e) => setNotes(e.target.value)} />
          </div>
        </div>
        <DialogFooter>
          <Button variant='outline' onClick={onClose}>
            取消
          </Button>
          <Button
            disabled={pending || name.trim().length < 2}
            onClick={() =>
              onSubmit(contract.id, contract.version, {
                contract_name: name.trim(),
                effective_from: effectiveFrom || undefined,
                effective_to: effectiveTo || undefined,
                billing_day: Number(billingDay) || undefined,
                payment_terms_days: Number(paymentTerms) || undefined,
                tax_rate: taxRate.trim() || undefined,
                tax_inclusive: taxInclusive,
                notes: notes.trim() || undefined,
                reason: '在合同页编辑合同',
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

function ItemEditDialog({
  item,
  pending,
  onClose,
  onSubmit,
}: {
  item?: ContractItem
  pending: boolean
  onClose: () => void
  onSubmit: (
    id: string,
    version: number,
    input: {
      item_name?: string
      default_quantity?: string
      auto_bill?: boolean
      visible_on_invoice?: boolean
      sort_order?: number
      status?: string
      reason: string
    }
  ) => void
}) {
  const [name, setName] = useState(item?.item_name ?? '')
  const [quantity, setQuantity] = useState(
    item?.default_quantity?.toString() ?? ''
  )
  const [autoBill, setAutoBill] = useState(item?.auto_bill ?? true)
  const [visible, setVisible] = useState(item?.visible_on_invoice ?? true)
  const [sortOrder, setSortOrder] = useState(String(item?.sort_order ?? 0))
  const [status, setStatus] = useState(item?.status ?? 'ACTIVE')
  if (!item) return null

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>编辑计费项 · {item.contract_item_no}</DialogTitle>
          <DialogDescription>
            价格内容不可在此修改;调价请新建价格版本。
          </DialogDescription>
        </DialogHeader>
        <div className='grid gap-4 sm:grid-cols-2'>
          <div className='space-y-2 sm:col-span-2'>
            <Label>计费项名称</Label>
            <Input value={name} onChange={(e) => setName(e.target.value)} />
          </div>
          <div className='space-y-2'>
            <Label>默认数量</Label>
            <Input
              className='font-mono'
              value={quantity}
              onChange={(e) => setQuantity(e.target.value)}
            />
          </div>
          <div className='space-y-2'>
            <Label>排序</Label>
            <Input
              className='font-mono'
              value={sortOrder}
              onChange={(e) => setSortOrder(e.target.value)}
            />
          </div>
          <div className='space-y-2'>
            <Label>自动出账</Label>
            <select
              value={autoBill ? 'yes' : 'no'}
              onChange={(e) => setAutoBill(e.target.value === 'yes')}
              className='h-9 w-full rounded-md border bg-background px-3 text-sm'
            >
              <option value='yes'>是</option>
              <option value='no'>否</option>
            </select>
          </div>
          <div className='space-y-2'>
            <Label>账单上可见</Label>
            <select
              value={visible ? 'yes' : 'no'}
              onChange={(e) => setVisible(e.target.value === 'yes')}
              className='h-9 w-full rounded-md border bg-background px-3 text-sm'
            >
              <option value='yes'>是</option>
              <option value='no'>否</option>
            </select>
          </div>
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
        </div>
        <DialogFooter>
          <Button variant='outline' onClick={onClose}>
            取消
          </Button>
          <Button
            disabled={pending || name.trim().length < 2}
            onClick={() =>
              onSubmit(item.id, item.version, {
                item_name: name.trim(),
                default_quantity: quantity.trim() || undefined,
                auto_bill: autoBill,
                visible_on_invoice: visible,
                sort_order: Number(sortOrder) || 0,
                status,
                reason: '在合同页编辑计费项',
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
