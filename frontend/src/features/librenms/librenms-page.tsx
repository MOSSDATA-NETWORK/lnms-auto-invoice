import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Cable,
  DatabaseZap,
  History,
  Link2,
  Pencil,
  Plus,
  Radar,
  ShieldAlert,
} from 'lucide-react'
import { toast } from 'sonner'
import {
  contractItemsQuery,
  contractsQuery,
  createLibrenmsInstance,
  createLibrenmsMapping,
  discoverBills,
  discoveredBillsQuery,
  librenmsInstancesQuery,
  librenmsMappingsQuery,
  servicesQuery,
  syncLibrenmsHistory,
  updateLibrenmsInstance,
  usageSnapshotsQuery,
  verifyLibrenms,
  type LibrenmsInstance,
  type LibrenmsMapping,
} from '@/api/operations'
import { problemFrom } from '@/api/http'
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
import { Main } from '@/components/layout/main'
import { ConsoleHeader } from '@/features/shell/console-header'
import { PageHeading } from '@/features/shell/page-heading'

export function LibrenmsPage() {
  const queryClient = useQueryClient()
  const instances = useQuery(librenmsInstancesQuery)
  const [selectedId, setSelectedId] = useState<string>()
  const effectiveId = selectedId ?? instances.data?.[0]?.id
  const selected = instances.data?.find((item) => item.id === effectiveId)
  const discovered = useQuery(discoveredBillsQuery(effectiveId))
  const mappings = useQuery(librenmsMappingsQuery(effectiveId))
  const snapshots = useQuery(usageSnapshotsQuery)
  const services = useQuery(servicesQuery)
  const contracts = useQuery(contractsQuery)
  const [mappingBillId, setMappingBillId] = useState<number>()
  const [serviceId, setServiceId] = useState('')
  const selectedService = services.data?.find((item) => item.id === serviceId)
  const [contractId, setContractId] = useState('')
  const contractItems = useQuery(contractItemsQuery(contractId || undefined))
  const [contractItemId, setContractItemId] = useState('')
  const [direction, setDirection] = useState('AGGREGATE')
  const [sourceUnit, setSourceUnit] = useState('bps')
  const [syncMapping, setSyncMapping] = useState<LibrenmsMapping>()
  const [addOpen, setAddOpen] = useState(false)
  const [editInstance, setEditInstance] = useState<LibrenmsInstance>()
  const emptyInstance = {
    instance_name: '',
    instance_code: '',
    base_url: '',
    api_token: '',
    timezone: 'Asia/Shanghai',
  }
  const [newInstance, setNewInstance] = useState(emptyInstance)
  const defaultPeriod = previousMonthPeriod()
  const [periodStart, setPeriodStart] = useState(defaultPeriod.start)
  const [periodEnd, setPeriodEnd] = useState(defaultPeriod.end)
  const action = useMutation({
    mutationFn: ({
      instance,
      type,
    }: {
      instance: LibrenmsInstance
      type: 'verify' | 'discover'
    }) =>
      type === 'verify' ? verifyLibrenms(instance) : discoverBills(instance),
    onSuccess: async (result, variables) => {
      toast.success(
        `${variables.type === 'verify' ? '连接测试' : 'Bill 发现'}任务已入队：${result.job_id.slice(0, 8)}`
      )
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['jobs'] }),
        queryClient.invalidateQueries({
          queryKey: ['librenms-discovered-bills'],
        }),
      ])
    },
  })
  const createMapping = useMutation({
    mutationFn: () =>
      createLibrenmsMapping(effectiveId!, {
        librenms_bill_id: mappingBillId!,
        customer_id: selectedService!.customer_id,
        company_id: selectedService!.company_id,
        service_id: selectedService!.id,
        contract_item_id: contractItemId,
        billing_direction: direction,
        source_unit: sourceUnit || undefined,
        effective_from: new Date().toISOString(),
        reason: '在 LibreNMS 映射工作台确认 Bill 与合同计费项',
      }),
    onSuccess: async () => {
      toast.success('Bill 映射已确认')
      setMappingBillId(undefined)
      setServiceId('')
      setContractId('')
      setContractItemId('')
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['librenms-mappings'] }),
        queryClient.invalidateQueries({
          queryKey: ['librenms-discovered-bills'],
        }),
      ])
    },
  })
  const syncHistory = useMutation({
    mutationFn: () =>
      syncLibrenmsHistory(
        effectiveId!,
        syncMapping!.id,
        new Date(`${periodStart}T00:00:00Z`).toISOString(),
        new Date(`${periodEnd}T00:00:00Z`).toISOString()
      ),
    onSuccess: async (result) => {
      toast.success(`History 同步任务已入队：${result.job_id.slice(0, 8)}`)
      setSyncMapping(undefined)
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['jobs'] }),
        queryClient.invalidateQueries({ queryKey: ['usage-snapshots'] }),
      ])
    },
  })
  const createInstance = useMutation({
    mutationFn: () =>
      createLibrenmsInstance({
        instance_name: newInstance.instance_name.trim(),
        instance_code: newInstance.instance_code.trim(),
        base_url: newInstance.base_url.trim().replace(/\/+$/, ''),
        api_token: newInstance.api_token.trim(),
        timezone: newInstance.timezone.trim(),
      }),
    onSuccess: async (created) => {
      toast.success(`数据源 ${created.instance_name} 已创建`)
      setAddOpen(false)
      setNewInstance(emptyInstance)
      setSelectedId(created.id)
      await queryClient.invalidateQueries({ queryKey: ['librenms-instances'] })
    },
    onError: (error) => {
      const problem = problemFrom(error)
      toast.error(problem.detail ?? problem.title ?? '创建数据源失败')
    },
  })
  const editInstanceMutation = useMutation({
    mutationFn: (input: {
      id: string
      version: number
      instance_name?: string
      base_url?: string
      api_token?: string
      timezone?: string
      connect_timeout_ms?: number
      read_timeout_ms?: number
      max_concurrency?: number
      status?: string
    }) =>
      updateLibrenmsInstance(input.id, input.version, {
        instance_name: input.instance_name,
        base_url: input.base_url,
        api_token: input.api_token,
        timezone: input.timezone,
        connect_timeout_ms: input.connect_timeout_ms,
        read_timeout_ms: input.read_timeout_ms,
        max_concurrency: input.max_concurrency,
        status: input.status,
        reason: '在 LibreNMS 工作台修改数据源',
      }),
    onSuccess: async (updated) => {
      toast.success(`数据源 ${updated.instance_name} 已更新`)
      setEditInstance(undefined)
      await queryClient.invalidateQueries({ queryKey: ['librenms-instances'] })
    },
    onError: (error) => {
      const problem = problemFrom(error)
      toast.error(problem.detail ?? problem.title ?? '更新数据源失败')
    },
  })
  const newInstanceValid =
    newInstance.instance_name.trim().length > 0 &&
    /^[A-Z0-9][A-Z0-9_-]{2,99}$/.test(newInstance.instance_code.trim()) &&
    /^https?:\/\/[^\s/?#]+(:\d{1,5})?$/.test(
      newInstance.base_url.trim().replace(/\/+$/, '')
    ) &&
    newInstance.api_token.trim().length > 0 &&
    newInstance.timezone.trim().length > 0
  return (
    <>
      <ConsoleHeader label='librenms' />
      <Main className='space-y-7'>
        <PageHeading
          eyebrow='用量证据'
          title='LibreNMS 数据源与 Bill History'
          description='当前观察值只用于运营提示；出账使用不可变 History 快照和归档证据。Aggregate 95 必须来自 LibreNMS 聚合结果。'
        />
        <div className='grid min-w-0 gap-4 xl:grid-cols-[minmax(0,.8fr)_minmax(0,1.2fr)]'>
          <Card className='min-w-0 shadow-none'>
            <CardHeader className='flex flex-row items-start justify-between gap-3'>
              <div className='space-y-1.5'>
                <CardTitle className='flex items-center gap-2 text-base'>
                  <Cable className='size-4' />
                  数据源
                </CardTitle>
                <CardDescription>
                  Token 仅在服务端解密，浏览器永远看不到。
                </CardDescription>
              </div>
              <Button size='sm' onClick={() => setAddOpen(true)}>
                <Plus />
                新增数据源
              </Button>
            </CardHeader>
            <CardContent className='space-y-3'>
              {instances.isLoading ? (
                <Loading count={3} />
              ) : !instances.data?.length ? (
                <p className='py-8 text-center text-sm text-muted-foreground'>
                  尚未配置 LibreNMS 实例。
                </p>
              ) : (
                instances.data.map((instance) => (
                  <div
                    key={instance.id}
                    role='button'
                    tabIndex={0}
                    onClick={() => setSelectedId(instance.id)}
                    onKeyDown={(event) => event.key === 'Enter' && setSelectedId(instance.id)}
                    className={`w-full min-w-0 cursor-pointer rounded-lg border p-4 text-left transition-colors ${effectiveId === instance.id ? 'border-emerald-500 bg-emerald-50/50 dark:bg-emerald-950/20' : 'hover:bg-muted/40'}`}
                  >
                    <div className='flex items-start justify-between gap-3'>
                      <div className='min-w-0'>
                        <p className='font-medium break-words'>
                          {instance.instance_name}
                        </p>
                        <p className='mt-1 font-mono text-xs break-all text-muted-foreground'>
                          {instance.instance_code}
                        </p>
                      </div>
                      <div className='flex shrink-0 items-center gap-1'>
                        <Badge
                          variant={
                            instance.status === 'ACTIVE'
                              ? 'default'
                              : 'destructive'
                          }
                        >
                          {instance.status}
                        </Badge>
                        <Button
                          size='icon'
                          variant='ghost'
                          aria-label='编辑数据源'
                          onClick={(event) => {
                            event.stopPropagation()
                            setEditInstance(instance)
                          }}
                        >
                          <Pencil className='size-3.5' />
                        </Button>
                      </div>
                    </div>
                    <p className='mt-3 truncate text-xs text-muted-foreground'>
                      {instance.base_url}
                    </p>
                    <div className='mt-3 flex flex-wrap items-center justify-between gap-2 text-[11px] text-muted-foreground'>
                      <span>{instance.timezone}</span>
                      <span>连续失败 {instance.consecutive_failures}</span>
                    </div>
                  </div>
                ))
              )}
            </CardContent>
          </Card>
          <Card className='min-w-0 shadow-none'>
            <CardHeader>
              <CardTitle className='flex items-center gap-2 text-base'>
                <Radar className='size-4' />
                发现与映射对照
              </CardTitle>
              <CardDescription>
                {selected
                  ? `${selected.instance_name} 的 Bill 列表`
                  : '选择数据源后查看'}
              </CardDescription>
            </CardHeader>
            <CardContent>
              {selected && (
                <div className='mb-4 flex flex-wrap gap-2'>
                  <Button
                    size='sm'
                    variant='outline'
                    disabled={action.isPending}
                    onClick={() =>
                      action.mutate({ instance: selected, type: 'verify' })
                    }
                  >
                    <DatabaseZap />
                    测试连接
                  </Button>
                  <Button
                    size='sm'
                    disabled={action.isPending}
                    onClick={() =>
                      action.mutate({ instance: selected, type: 'discover' })
                    }
                  >
                    <Radar />
                    发现 Bills
                  </Button>
                </div>
              )}
              {discovered.isLoading ? (
                <Loading count={5} />
              ) : !discovered.data?.length ? (
                <div className='grid place-items-center py-12 text-center'>
                  <ShieldAlert className='size-6 text-muted-foreground' />
                  <p className='mt-3 text-sm font-medium'>尚无发现记录</p>
                  <p className='mt-1 text-xs text-muted-foreground'>
                    运行 Bill 发现任务后，才能建立到合同计费项的确认映射。
                  </p>
                </div>
              ) : (
                <div className='max-h-[430px] max-w-full min-w-0 overflow-x-auto overflow-y-auto rounded-lg border'>
                  <Table>
                    <TableHeader>
                      <TableRow className='bg-muted/30'>
                        <TableHead>Bill</TableHead>
                        <TableHead>客户/引用</TableHead>
                        <TableHead>类型</TableHead>
                        <TableHead>映射</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {discovered.data.map((bill) => (
                        <TableRow key={bill.id}>
                          <TableCell>
                            <p className='max-w-80 font-medium break-words whitespace-normal'>
                              {bill.bill_name ??
                                `Bill #${bill.librenms_bill_id}`}
                            </p>
                            <p className='mt-1 max-w-64 font-mono text-[11px] break-all whitespace-normal text-muted-foreground'>
                              ID {bill.librenms_bill_id}
                            </p>
                          </TableCell>
                          <TableCell>
                            <p className='text-xs'>{bill.bill_custid ?? '—'}</p>
                            <p className='mt-1 font-mono text-[11px] text-muted-foreground'>
                              {bill.bill_ref ?? '无引用'}
                            </p>
                          </TableCell>
                          <TableCell className='font-mono text-xs'>
                            {bill.bill_type ?? '—'}
                          </TableCell>
                          <TableCell>
                            {bill.mapped ? (
                              <Badge>已确认</Badge>
                            ) : (
                              <Button
                                size='sm'
                                variant='outline'
                                onClick={() =>
                                  setMappingBillId(bill.librenms_bill_id)
                                }
                              >
                                <Link2 />
                                建立映射
                              </Button>
                            )}
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </div>
              )}
            </CardContent>
          </Card>
        </div>
        <Card className='min-w-0 gap-0 overflow-hidden py-0 shadow-none'>
          <CardHeader className='border-b py-5'>
            <CardTitle className='flex items-center gap-2 text-base'>
              <Link2 className='size-4' />
              已确认 Bill 映射
            </CardTitle>
            <CardDescription>
              每次同步都使用映射中冻结的方向、单位和有效期，不从 Bill 名称猜测。
            </CardDescription>
          </CardHeader>
          <CardContent className='p-0'>
            {mappings.isLoading ? (
              <Loading count={3} />
            ) : !mappings.data?.length ? (
              <p className='p-10 text-center text-sm text-muted-foreground'>
                尚无确认映射。
              </p>
            ) : (
              <Table>
                <TableHeader>
                  <TableRow className='bg-muted/30'>
                    <TableHead>Bill</TableHead>
                    <TableHead>业务</TableHead>
                    <TableHead>合同计费项</TableHead>
                    <TableHead>方向 / 单位</TableHead>
                    <TableHead>状态</TableHead>
                    <TableHead />
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {mappings.data.map((mapping) => (
                    <TableRow key={mapping.id}>
                      <TableCell>
                        <p className='font-medium'>
                          {mapping.observed_bill_name ??
                            `Bill #${mapping.librenms_bill_id}`}
                        </p>
                        <p className='mt-1 font-mono text-[11px] text-muted-foreground'>
                          #{mapping.librenms_bill_id}
                        </p>
                      </TableCell>
                      <TableCell>
                        <p>{mapping.service_name}</p>
                        <p className='mt-1 font-mono text-[11px] text-muted-foreground'>
                          {mapping.service_no}
                        </p>
                      </TableCell>
                      <TableCell>
                        <p>{mapping.item_name}</p>
                        <p className='mt-1 font-mono text-[11px] text-muted-foreground'>
                          {mapping.contract_item_no}
                        </p>
                      </TableCell>
                      <TableCell className='font-mono text-xs'>
                        {mapping.billing_direction} /{' '}
                        {mapping.source_unit ?? '—'}
                      </TableCell>
                      <TableCell>
                        <Badge variant='outline'>{mapping.status}</Badge>
                      </TableCell>
                      <TableCell>
                        <Button
                          size='sm'
                          disabled={mapping.status !== 'ACTIVE'}
                          onClick={() => setSyncMapping(mapping)}
                        >
                          <History />
                          同步账期
                        </Button>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
          </CardContent>
        </Card>
        <Card className='min-w-0 gap-0 overflow-hidden py-0 shadow-none'>
          <CardHeader className='border-b py-5'>
            <CardTitle className='flex items-center gap-2 text-base'>
              <History className='size-4' />
              不可变用量快照
            </CardTitle>
          </CardHeader>
          <CardContent className='p-0'>
            {snapshots.isLoading ? (
              <Loading count={4} />
            ) : !snapshots.data?.length ? (
              <p className='p-12 text-center text-sm text-muted-foreground'>
                尚无 History 快照。完成映射后按账期发起同步。
              </p>
            ) : (
              <Table>
                <TableHeader>
                  <TableRow className='bg-muted/30'>
                    <TableHead>账期</TableHead>
                    <TableHead>Bill / History</TableHead>
                    <TableHead>方向</TableHead>
                    <TableHead>原始 → 计费</TableHead>
                    <TableHead>覆盖率</TableHead>
                    <TableHead>证据哈希</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {snapshots.data.map((snapshot) => (
                    <TableRow key={snapshot.id}>
                      <TableCell className='font-mono text-xs'>
                        {snapshot.period_start.slice(0, 10)} →{' '}
                        {snapshot.period_end.slice(0, 10)}
                      </TableCell>
                      <TableCell className='font-mono text-xs'>
                        #{snapshot.librenms_bill_id} /{' '}
                        {snapshot.bill_history_id ?? '—'}
                      </TableCell>
                      <TableCell>
                        <Badge variant='outline'>
                          {snapshot.billing_direction}
                        </Badge>
                      </TableCell>
                      <TableCell className='font-mono text-xs'>
                        {snapshot.raw_usage ?? '—'} →{' '}
                        {snapshot.billing_usage ??
                          snapshot.converted_usage ??
                          '—'}{' '}
                        {snapshot.unit ?? ''}
                      </TableCell>
                      <TableCell className='font-mono text-xs'>
                        {snapshot.sample_coverage ?? '—'}
                      </TableCell>
                      <TableCell
                        className='max-w-44 truncate font-mono text-[11px]'
                        title={snapshot.data_hash}
                      >
                        {snapshot.data_hash}
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
        open={mappingBillId !== undefined}
        onOpenChange={(open) => !open && setMappingBillId(undefined)}
      >
        <DialogContent className='sm:max-w-2xl'>
          <DialogHeader>
            <DialogTitle>确认 Bill #{mappingBillId} 映射</DialogTitle>
            <DialogDescription>
              只允许映射到同一客户、公司和业务下的合同计费项。Aggregate 95
              必须明确选择 AGGREGATE 或 LIBRENMS_FINAL。
            </DialogDescription>
          </DialogHeader>
          <div className='grid gap-4 sm:grid-cols-2'>
            <Field label='业务'>
              <select
                value={serviceId}
                onChange={(event) => {
                  setServiceId(event.target.value)
                  setContractId('')
                  setContractItemId('')
                }}
                className='h-9 w-full rounded-md border bg-background px-3 text-sm'
              >
                <option value=''>请选择业务</option>
                {services.data?.map((service) => (
                  <option key={service.id} value={service.id}>
                    {service.service_no} · {service.service_name}
                  </option>
                ))}
              </select>
            </Field>
            <Field label='合同'>
              <select
                value={contractId}
                onChange={(event) => {
                  setContractId(event.target.value)
                  setContractItemId('')
                }}
                className='h-9 w-full rounded-md border bg-background px-3 text-sm'
              >
                <option value=''>请选择合同</option>
                {contracts.data
                  ?.filter(
                    (contract) =>
                      contract.customer_id === selectedService?.customer_id &&
                      contract.company_id === selectedService?.company_id
                  )
                  .map((contract) => (
                    <option key={contract.id} value={contract.id}>
                      {contract.contract_no} · {contract.contract_name}
                    </option>
                  ))}
              </select>
            </Field>
            <Field label='合同计费项' className='sm:col-span-2'>
              <select
                value={contractItemId}
                onChange={(event) => setContractItemId(event.target.value)}
                className='h-9 w-full rounded-md border bg-background px-3 text-sm'
              >
                <option value=''>请选择计费项</option>
                {contractItems.data
                  ?.filter((item) => item.service_id === serviceId)
                  .map((item) => (
                    <option key={item.id} value={item.id}>
                      {item.contract_item_no} · {item.item_name} ·{' '}
                      {item.billing_type}
                    </option>
                  ))}
              </select>
            </Field>
            <Field label='计费方向'>
              <select
                value={direction}
                onChange={(event) => setDirection(event.target.value)}
                className='h-9 w-full rounded-md border bg-background px-3 text-sm'
              >
                <option value='AGGREGATE'>AGGREGATE</option>
                <option value='LIBRENMS_FINAL'>LIBRENMS_FINAL</option>
                <option value='MAX'>MAX</option>
                <option value='INBOUND'>INBOUND</option>
                <option value='OUTBOUND'>OUTBOUND</option>
              </select>
            </Field>
            <Field label='源单位'>
              <Input
                value={sourceUnit}
                onChange={(event) => setSourceUnit(event.target.value)}
                className='font-mono'
              />
            </Field>
          </div>
          <DialogFooter>
            <Button
              variant='outline'
              onClick={() => setMappingBillId(undefined)}
            >
              取消
            </Button>
            <Button
              disabled={
                createMapping.isPending ||
                !selectedService ||
                !contractId ||
                !contractItemId
              }
              onClick={() => createMapping.mutate()}
            >
              确认映射
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
      <Dialog
        open={Boolean(syncMapping)}
        onOpenChange={(open) => !open && setSyncMapping(undefined)}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>同步 Bill History</DialogTitle>
            <DialogDescription>
              账期使用半开区间 [period_start,
              period_end)。关键字段缺失会阻断，绝不默认为零。
            </DialogDescription>
          </DialogHeader>
          <div className='grid gap-4 sm:grid-cols-2'>
            <Field label='账期开始'>
              <Input
                type='date'
                value={periodStart}
                onChange={(event) => setPeriodStart(event.target.value)}
              />
            </Field>
            <Field label='账期结束（不含）'>
              <Input
                type='date'
                value={periodEnd}
                onChange={(event) => setPeriodEnd(event.target.value)}
              />
            </Field>
          </div>
          <DialogFooter>
            <Button variant='outline' onClick={() => setSyncMapping(undefined)}>
              取消
            </Button>
            <Button
              disabled={
                syncHistory.isPending ||
                !periodStart ||
                !periodEnd ||
                periodStart >= periodEnd
              }
              onClick={() => syncHistory.mutate()}
            >
              创建同步任务
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
      <Dialog open={addOpen} onOpenChange={setAddOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>新增 LibreNMS 数据源</DialogTitle>
            <DialogDescription>
              支持配置多个 LibreNMS 实例。地址仅允许协议 +
              主机（可选端口），不能带路径；白名单外的地址会被服务端拒绝。
            </DialogDescription>
          </DialogHeader>
          <div className='grid gap-4 sm:grid-cols-2'>
            <Field label='名称'>
              <Input
                placeholder='上海机房 NMS'
                value={newInstance.instance_name}
                onChange={(event) =>
                  setNewInstance({
                    ...newInstance,
                    instance_name: event.target.value,
                  })
                }
              />
            </Field>
            <Field label='编码（大写字母/数字）'>
              <Input
                placeholder='SH-NMS-01'
                className='font-mono'
                value={newInstance.instance_code}
                onChange={(event) =>
                  setNewInstance({
                    ...newInstance,
                    instance_code: event.target.value.toUpperCase(),
                  })
                }
              />
            </Field>
            <Field label='API 地址' className='sm:col-span-2'>
              <Input
                placeholder='http://192.168.1.10 或 https://nms.example.com'
                className='font-mono'
                value={newInstance.base_url}
                onChange={(event) =>
                  setNewInstance({
                    ...newInstance,
                    base_url: event.target.value,
                  })
                }
              />
            </Field>
            <Field label='API Token' className='sm:col-span-2'>
              <Input
                type='password'
                placeholder='LibreNMS → Manage API Tokens'
                className='font-mono'
                value={newInstance.api_token}
                onChange={(event) =>
                  setNewInstance({
                    ...newInstance,
                    api_token: event.target.value,
                  })
                }
              />
            </Field>
            <Field label='时区'>
              <Input
                className='font-mono'
                value={newInstance.timezone}
                onChange={(event) =>
                  setNewInstance({
                    ...newInstance,
                    timezone: event.target.value,
                  })
                }
              />
            </Field>
          </div>
          <DialogFooter>
            <Button variant='outline' onClick={() => setAddOpen(false)}>
              取消
            </Button>
            <Button
              disabled={createInstance.isPending || !newInstanceValid}
              onClick={() => createInstance.mutate()}
            >
              创建数据源
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
      <EditInstanceDialog
        key={editInstance?.id ?? 'closed'}
        instance={editInstance}
        pending={editInstanceMutation.isPending}
        onClose={() => setEditInstance(undefined)}
        onSubmit={(input) => editInstanceMutation.mutate(input)}
      />
    </>
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

function previousMonthPeriod() {
  const now = new Date()
  const end = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), 1))
  const start = new Date(
    Date.UTC(end.getUTCFullYear(), end.getUTCMonth() - 1, 1)
  )
  return {
    start: start.toISOString().slice(0, 10),
    end: end.toISOString().slice(0, 10),
  }
}

function Loading({ count }: { count: number }) {
  return (
    <div className='space-y-3 py-3'>
      {Array.from({ length: count }).map((_, index) => (
        <Skeleton key={index} className='h-12 w-full' />
      ))}
    </div>
  )
}

function EditInstanceDialog({
  instance,
  pending,
  onClose,
  onSubmit,
}: {
  instance?: LibrenmsInstance
  pending: boolean
  onClose: () => void
  onSubmit: (input: {
    id: string
    version: number
    instance_name?: string
    base_url?: string
    api_token?: string
    timezone?: string
    connect_timeout_ms?: number
    read_timeout_ms?: number
    max_concurrency?: number
    status?: string
  }) => void
}) {
  const [name, setName] = useState(instance?.instance_name ?? '')
  const [baseUrl, setBaseUrl] = useState(instance?.base_url ?? '')
  const [apiToken, setApiToken] = useState('')
  const [timezone, setTimezone] = useState(instance?.timezone ?? 'Asia/Shanghai')
  const [connectTimeout, setConnectTimeout] = useState(
    String(instance?.connect_timeout_ms ?? 5000)
  )
  const [readTimeout, setReadTimeout] = useState(
    String(instance?.read_timeout_ms ?? 30000)
  )
  const [maxConcurrency, setMaxConcurrency] = useState(
    String(instance?.max_concurrency ?? 4)
  )
  const [status, setStatus] = useState(instance?.status ?? 'ACTIVE')
  if (!instance) return null
  const baseUrlValid =
    /^https?:\/\/[^\s/?#]+(:\d{1,5})?$/.test(baseUrl.trim().replace(/\/+$/, ''))
  const valid =
    name.trim().length > 0 && baseUrlValid && timezone.trim().length > 0

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>
            编辑数据源 · {instance.instance_code}
          </DialogTitle>
          <DialogDescription>
            Token 留空表示不更换；停用后同步与发现任务不再选择该实例。
          </DialogDescription>
        </DialogHeader>
        <div className='grid gap-4 sm:grid-cols-2'>
          <Field label='名称'>
            <Input value={name} onChange={(e) => setName(e.target.value)} />
          </Field>
          <Field label='时区'>
            <Input
              className='font-mono'
              value={timezone}
              onChange={(e) => setTimezone(e.target.value)}
            />
          </Field>
          <Field label='API 地址' className='sm:col-span-2'>
            <Input
              className='font-mono'
              value={baseUrl}
              onChange={(e) => setBaseUrl(e.target.value)}
            />
          </Field>
          <Field label='新 API Token(留空不换)' className='sm:col-span-2'>
            <Input
              type='password'
              className='font-mono'
              placeholder='留空保持现有 Token'
              value={apiToken}
              onChange={(e) => setApiToken(e.target.value)}
            />
          </Field>
          <Field label='连接超时(ms)'>
            <Input
              className='font-mono'
              value={connectTimeout}
              onChange={(e) => setConnectTimeout(e.target.value)}
            />
          </Field>
          <Field label='读取超时(ms)'>
            <Input
              className='font-mono'
              value={readTimeout}
              onChange={(e) => setReadTimeout(e.target.value)}
            />
          </Field>
          <Field label='并发数'>
            <Input
              className='font-mono'
              value={maxConcurrency}
              onChange={(e) => setMaxConcurrency(e.target.value)}
            />
          </Field>
          <Field label='状态'>
            <select
              value={status}
              onChange={(e) => setStatus(e.target.value)}
              className='h-9 w-full rounded-md border bg-background px-3 text-sm'
            >
              <option value='ACTIVE'>ACTIVE</option>
              <option value='DISABLED'>DISABLED</option>
            </select>
          </Field>
        </div>
        <DialogFooter>
          <Button variant='outline' onClick={onClose}>
            取消
          </Button>
          <Button
            disabled={pending || !valid}
            onClick={() =>
              onSubmit({
                id: instance.id,
                version: instance.version,
                instance_name: name.trim(),
                base_url: baseUrl.trim().replace(/\/+$/, ''),
                api_token: apiToken.trim() || undefined,
                timezone: timezone.trim(),
                connect_timeout_ms: Number(connectTimeout) || undefined,
                read_timeout_ms: Number(readTimeout) || undefined,
                max_concurrency: Number(maxConcurrency) || undefined,
                status,
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
