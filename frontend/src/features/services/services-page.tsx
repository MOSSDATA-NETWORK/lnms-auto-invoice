import { useMemo, useState, type ReactNode } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Boxes,
  CirclePause,
  Cpu,
  Network,
  PackagePlus,
  Pencil,
  Plus,
  RadioTower,
} from 'lucide-react'
import { toast } from 'sonner'
import { customersQuery } from '@/api/customers'
import { problemFrom } from '@/api/http'
import {
  companiesQuery,
  createProduct,
  createService,
  createServiceResource,
  productsQuery,
  serviceResourcesQuery,
  servicesQuery,
  updateService,
  type Service,
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

const serviceTypes = [
  'BANDWIDTH_95',
  'FIXED_BANDWIDTH',
  'TOTAL_TRAFFIC',
  'COLOCATION',
  'SERVER',
  'RACK',
  'POWER',
  'IP',
  'BGP',
  'DEDICATED_LINE',
  'CROSS_CONNECT',
  'INSTALLATION',
  'MANAGED_SERVICE',
  'CUSTOM',
]

const emptyService = {
  service_no: '',
  company_id: '',
  product_id: '',
  service_name: '',
  service_type: 'BANDWIDTH_95',
  region: '',
  datacenter: '',
  line_name: '',
  activated_on: today(),
  deactivated_on: '',
  status: 'ACTIVE',
  notes: '',
}

const emptyProduct = {
  product_code: '',
  product_name: '',
  service_type: 'BANDWIDTH_95',
  default_unit: 'Mbps',
}

const emptyResource = {
  resource_type: 'PORT',
  resource_ref: '',
  display_name: '',
  effective_from: localDateTime(),
  effective_to: '',
}

export function ServicesPage() {
  const queryClient = useQueryClient()
  const services = useQuery(servicesQuery)
  const companies = useQuery(companiesQuery)
  const customers = useQuery(customersQuery())
  const products = useQuery(productsQuery)
  const [selectedServiceId, setSelectedServiceId] = useState<string>()
  const [createServiceOpen, setCreateServiceOpen] = useState(false)
  const [createProductOpen, setCreateProductOpen] = useState(false)
  const [createResourceOpen, setCreateResourceOpen] = useState(false)
  const [serviceForm, setServiceForm] = useState(emptyService)
  const [productForm, setProductForm] = useState(emptyProduct)
  const [resourceForm, setResourceForm] = useState(emptyResource)
  const [editingService, setEditingService] = useState<Service>()

  const updateServiceMutation = useMutation({
    mutationFn: ({
      id,
      version,
      input,
    }: {
      id: string
      version: number
      input: Parameters<typeof updateService>[2]
    }) => updateService(id, version, input),
    onSuccess: async (updated) => {
      toast.success(`业务 ${updated.service_name} 已更新`)
      setEditingService(undefined)
      await queryClient.invalidateQueries({ queryKey: ['services'] })
    },
    onError: showMutationError,
  })

  const selectedService =
    services.data?.find((row) => row.id === selectedServiceId) ??
    services.data?.[0]
  const resources = useQuery(serviceResourcesQuery(selectedService?.id))
  const counts = useMemo(
    () => ({
      active:
        services.data?.filter((row) => row.status === 'ACTIVE').length ?? 0,
      pending:
        services.data?.filter((row) => row.status === 'PENDING').length ?? 0,
      stopped:
        services.data?.filter((row) =>
          ['SUSPENDED', 'ENDED', 'CANCELLED'].includes(row.status)
        ).length ?? 0,
    }),
    [services.data]
  )

  const createProductMutation = useMutation({
    mutationFn: () => createProduct(productForm),
    onSuccess: async (created) => {
      toast.success('产品类型已创建')
      setProductForm(emptyProduct)
      setCreateProductOpen(false)
      setServiceForm((current) => ({
        ...current,
        product_id: created.id,
        service_type: created.service_type,
      }))
      await queryClient.invalidateQueries({ queryKey: ['products'] })
    },
    onError: showMutationError,
  })
  const createServiceMutation = useMutation({
    mutationFn: () => {
      const company = companies.data?.find(
        (row) => row.id === serviceForm.company_id
      )
      if (!company) throw new Error('请选择客户公司')
      return createService({
        ...serviceForm,
        customer_id: company.customer_id,
      })
    },
    onSuccess: async (created) => {
      toast.success('业务已创建')
      setSelectedServiceId(created.id)
      setServiceForm(emptyService)
      setCreateServiceOpen(false)
      await queryClient.invalidateQueries({ queryKey: ['services'] })
    },
    onError: showMutationError,
  })
  const createResourceMutation = useMutation({
    mutationFn: () =>
      createServiceResource(selectedService!.id, {
        ...resourceForm,
        effective_from: resourceForm.effective_from
          ? toIso(resourceForm.effective_from)
          : undefined,
        effective_to: resourceForm.effective_to
          ? toIso(resourceForm.effective_to)
          : undefined,
      }),
    onSuccess: async () => {
      toast.success('服务资源已登记')
      setResourceForm(emptyResource)
      setCreateResourceOpen(false)
      await queryClient.invalidateQueries({
        queryKey: ['service-resources', selectedService?.id],
      })
    },
    onError: showMutationError,
  })

  return (
    <>
      <ConsoleHeader label='services' />
      <Main className='space-y-7'>
        <PageHeading
          eyebrow='业务资产'
          title='业务与服务资源'
          description='业务是可交付服务；端口、IP、机柜和线路等资源继续作为独立证据对象关联，停用不会改写历史账单。'
        />
        <div className='grid [grid-template-columns:repeat(auto-fit,minmax(min(100%,13rem),1fr))] gap-4'>
          <Signal icon={<RadioTower />} label='运行中' value={counts.active} />
          <Signal icon={<Network />} label='待开通' value={counts.pending} />
          <Signal
            icon={<CirclePause />}
            label='暂停或结束'
            value={counts.stopped}
          />
        </div>
        <div className='grid gap-5 xl:grid-cols-[1.2fr_.8fr]'>
          <Card className='gap-0 overflow-hidden py-0 shadow-none'>
            <CardHeader className='flex flex-col items-start gap-3 border-b py-5 sm:flex-row sm:items-center sm:justify-between'>
              <div>
                <CardTitle className='text-base'>业务台账</CardTitle>
                <CardDescription className='mt-1'>
                  产品定义标准售卖类型，业务记录客户实际开通实例。
                </CardDescription>
              </div>
              <div className='flex flex-wrap gap-2'>
                <Button
                  size='sm'
                  variant='outline'
                  onClick={() => setCreateProductOpen(true)}
                >
                  <PackagePlus /> 产品类型
                </Button>
                <Button size='sm' onClick={() => setCreateServiceOpen(true)}>
                  <Plus /> 新建业务
                </Button>
              </div>
            </CardHeader>
            <CardContent className='p-0'>
              {services.isLoading ? (
                <Loading />
              ) : !services.data?.length ? (
                <Empty
                  icon={<Boxes />}
                  title='尚无业务数据'
                  text='先创建产品类型和客户业务，也可在系统管理中批量导入。'
                />
              ) : (
                <Table>
                  <TableHeader>
                    <TableRow className='bg-muted/30'>
                      <TableHead>业务</TableHead>
                      <TableHead>区域与机房</TableHead>
                      <TableHead>生命周期</TableHead>
                      <TableHead>状态</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {services.data.map((service) => (
                      <TableRow
                        key={service.id}
                        className='cursor-pointer'
                        data-state={
                          service.id === selectedService?.id
                            ? 'selected'
                            : undefined
                        }
                        onClick={() => setSelectedServiceId(service.id)}
                      >
                        <TableCell>
                          <p className='font-medium'>{service.service_name}</p>
                          <p className='mt-1 font-mono text-[11px] text-muted-foreground'>
                            {service.service_no} · {service.service_type} · v
                            {service.version}
                          </p>
                        </TableCell>
                        <TableCell>
                          <p className='text-sm'>{service.region ?? '—'}</p>
                          <p className='mt-1 text-xs text-muted-foreground'>
                            {service.datacenter ??
                              service.line_name ??
                              '未配置资源位置'}
                          </p>
                        </TableCell>
                        <TableCell className='font-mono text-xs'>
                          {service.activated_on ?? '未开通'} →{' '}
                          {service.deactivated_on ?? '持续有效'}
                        </TableCell>
                        <TableCell>
                          <State value={service.status} />
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              )}
            </CardContent>
          </Card>
          <ResourceInspector
            service={selectedService}
            resources={resources.data ?? []}
            loading={resources.isLoading}
            onCreate={() => setCreateResourceOpen(true)}
            onEdit={(service) => setEditingService(service)}
          />
        </div>
      </Main>

      <Dialog open={createProductOpen} onOpenChange={setCreateProductOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>创建产品类型</DialogTitle>
            <DialogDescription>
              产品是标准售卖定义，不等于客户实际开通的业务实例。
            </DialogDescription>
          </DialogHeader>
          <div className='grid gap-4 sm:grid-cols-2'>
            <Field label='产品代码'>
              <Input
                value={productForm.product_code}
                onChange={(event) =>
                  setProductForm((current) => ({
                    ...current,
                    product_code: event.target.value.toUpperCase(),
                  }))
                }
              />
            </Field>
            <Field label='产品名称'>
              <Input
                value={productForm.product_name}
                onChange={(event) =>
                  setProductForm((current) => ({
                    ...current,
                    product_name: event.target.value,
                  }))
                }
              />
            </Field>
            <Field label='业务类型'>
              <ServiceTypeSelect
                value={productForm.service_type}
                onChange={(service_type) =>
                  setProductForm((current) => ({
                    ...current,
                    service_type,
                  }))
                }
              />
            </Field>
            <Field label='默认单位'>
              <Input
                value={productForm.default_unit}
                onChange={(event) =>
                  setProductForm((current) => ({
                    ...current,
                    default_unit: event.target.value,
                  }))
                }
              />
            </Field>
          </div>
          <DialogFooter>
            <Button
              disabled={
                createProductMutation.isPending ||
                productForm.product_code.length < 3 ||
                !productForm.product_name
              }
              onClick={() => createProductMutation.mutate()}
            >
              创建产品
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={createServiceOpen} onOpenChange={setCreateServiceOpen}>
        <DialogContent className='max-h-[calc(100svh-2rem)] overflow-y-auto sm:max-w-2xl'>
          <DialogHeader>
            <DialogTitle>创建客户业务</DialogTitle>
            <DialogDescription>
              客户归属从所选公司推导，浏览器不能自行提交租户上下文。
            </DialogDescription>
          </DialogHeader>
          <div className='grid gap-4 sm:grid-cols-2'>
            <Field label='业务编号'>
              <Input
                value={serviceForm.service_no}
                onChange={(event) =>
                  setServiceForm((current) => ({
                    ...current,
                    service_no: event.target.value.toUpperCase(),
                  }))
                }
              />
            </Field>
            <Field label='业务名称'>
              <Input
                value={serviceForm.service_name}
                onChange={(event) =>
                  setServiceForm((current) => ({
                    ...current,
                    service_name: event.target.value,
                  }))
                }
              />
            </Field>
            <Field label='客户公司' className='sm:col-span-2'>
              <Select
                value={serviceForm.company_id}
                onValueChange={(company_id) =>
                  setServiceForm((current) => ({ ...current, company_id }))
                }
              >
                <SelectTrigger className='w-full'>
                  <SelectValue placeholder='选择业务归属公司' />
                </SelectTrigger>
                <SelectContent>
                  {companies.data?.map((company) => {
                    const customer = customers.data?.data.find(
                      (row) => row.id === company.customer_id
                    )
                    return (
                      <SelectItem key={company.id} value={company.id}>
                        {customer?.customer_name ?? company.company_code} ·{' '}
                        {company.company_name}
                      </SelectItem>
                    )
                  })}
                </SelectContent>
              </Select>
            </Field>
            <Field label='产品类型'>
              <Select
                value={serviceForm.product_id}
                onValueChange={(product_id) => {
                  const product = products.data?.find(
                    (row) => row.id === product_id
                  )
                  setServiceForm((current) => ({
                    ...current,
                    product_id,
                    service_type: product?.service_type ?? current.service_type,
                  }))
                }}
              >
                <SelectTrigger className='w-full'>
                  <SelectValue placeholder='可选；选择标准产品' />
                </SelectTrigger>
                <SelectContent>
                  {products.data?.map((product) => (
                    <SelectItem key={product.id} value={product.id}>
                      {product.product_code} · {product.product_name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </Field>
            <Field label='业务类型'>
              <ServiceTypeSelect
                value={serviceForm.service_type}
                onChange={(service_type) =>
                  setServiceForm((current) => ({
                    ...current,
                    service_type,
                  }))
                }
              />
            </Field>
            <Field label='区域'>
              <Input
                value={serviceForm.region}
                onChange={(event) =>
                  setServiceForm((current) => ({
                    ...current,
                    region: event.target.value,
                  }))
                }
              />
            </Field>
            <Field label='机房'>
              <Input
                value={serviceForm.datacenter}
                onChange={(event) =>
                  setServiceForm((current) => ({
                    ...current,
                    datacenter: event.target.value,
                  }))
                }
              />
            </Field>
            <Field label='线路名称'>
              <Input
                value={serviceForm.line_name}
                onChange={(event) =>
                  setServiceForm((current) => ({
                    ...current,
                    line_name: event.target.value,
                  }))
                }
              />
            </Field>
            <Field label='状态'>
              <Select
                value={serviceForm.status}
                onValueChange={(status) =>
                  setServiceForm((current) => ({ ...current, status }))
                }
              >
                <SelectTrigger className='w-full'>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value='PENDING'>PENDING</SelectItem>
                  <SelectItem value='ACTIVE'>ACTIVE</SelectItem>
                  <SelectItem value='SUSPENDED'>SUSPENDED</SelectItem>
                </SelectContent>
              </Select>
            </Field>
            <Field label='开通日期'>
              <Input
                type='date'
                value={serviceForm.activated_on}
                onChange={(event) =>
                  setServiceForm((current) => ({
                    ...current,
                    activated_on: event.target.value,
                  }))
                }
              />
            </Field>
            <Field label='停用日期'>
              <Input
                type='date'
                value={serviceForm.deactivated_on}
                onChange={(event) =>
                  setServiceForm((current) => ({
                    ...current,
                    deactivated_on: event.target.value,
                  }))
                }
              />
            </Field>
            <Field label='备注' className='sm:col-span-2'>
              <Textarea
                value={serviceForm.notes}
                onChange={(event) =>
                  setServiceForm((current) => ({
                    ...current,
                    notes: event.target.value,
                  }))
                }
              />
            </Field>
          </div>
          <DialogFooter>
            <Button
              disabled={
                createServiceMutation.isPending ||
                !serviceForm.service_no ||
                !serviceForm.service_name ||
                !serviceForm.company_id
              }
              onClick={() => createServiceMutation.mutate()}
            >
              创建业务
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={createResourceOpen} onOpenChange={setCreateResourceOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>登记服务资源</DialogTitle>
            <DialogDescription>
              资源引用用于端口、IP、机柜和线路证据，不直接代替业务或计费项。
            </DialogDescription>
          </DialogHeader>
          <div className='grid gap-4 sm:grid-cols-2'>
            <Field label='资源类型'>
              <Select
                value={resourceForm.resource_type}
                onValueChange={(resource_type) =>
                  setResourceForm((current) => ({
                    ...current,
                    resource_type,
                  }))
                }
              >
                <SelectTrigger className='w-full'>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {[
                    'PORT',
                    'DEVICE',
                    'IP_PREFIX',
                    'SERVER',
                    'RACK',
                    'U_POSITION',
                    'POWER_FEED',
                    'CIRCUIT',
                    'OTHER',
                  ].map((type) => (
                    <SelectItem key={type} value={type}>
                      {type}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </Field>
            <Field label='资源引用'>
              <Input
                value={resourceForm.resource_ref}
                onChange={(event) =>
                  setResourceForm((current) => ({
                    ...current,
                    resource_ref: event.target.value,
                  }))
                }
                placeholder='设备/端口/IP/机柜编号'
              />
            </Field>
            <Field label='显示名称' className='sm:col-span-2'>
              <Input
                value={resourceForm.display_name}
                onChange={(event) =>
                  setResourceForm((current) => ({
                    ...current,
                    display_name: event.target.value,
                  }))
                }
              />
            </Field>
            <Field label='生效时刻'>
              <Input
                type='datetime-local'
                value={resourceForm.effective_from}
                onChange={(event) =>
                  setResourceForm((current) => ({
                    ...current,
                    effective_from: event.target.value,
                  }))
                }
              />
            </Field>
            <Field label='结束时刻（不含）'>
              <Input
                type='datetime-local'
                value={resourceForm.effective_to}
                onChange={(event) =>
                  setResourceForm((current) => ({
                    ...current,
                    effective_to: event.target.value,
                  }))
                }
              />
            </Field>
          </div>
          <DialogFooter>
            <Button
              disabled={
                createResourceMutation.isPending ||
                !selectedService ||
                !resourceForm.resource_ref
              }
              onClick={() => createResourceMutation.mutate()}
            >
              登记资源
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
      <ServiceEditDialog
        key={editingService?.id ?? 'closed'}
        service={editingService}
        pending={updateServiceMutation.isPending}
        onClose={() => setEditingService(undefined)}
        onSubmit={(id, version, input) =>
          updateServiceMutation.mutate({ id, version, input })
        }
      />
    </>
  )
}

function ResourceInspector({
  service,
  resources,
  loading,
  onCreate,
  onEdit,
}: {
  service?: Service
  resources: Array<{
    id: string
    resource_type: string
    resource_ref: string
    display_name: string | null
    effective_from: string | null
    effective_to: string | null
    status: string
  }>
  loading: boolean
  onCreate: () => void
  onEdit: (service: Service) => void
}) {
  return (
    <Card className='shadow-none'>
      <CardHeader>
        <div className='flex items-start justify-between gap-4'>
          <div>
            <CardTitle className='text-base'>
              {service?.service_name ?? '服务资源'}
            </CardTitle>
            <CardDescription className='mt-1 font-mono'>
              {service
                ? `${service.service_no} · ${service.service_type}`
                : '选择业务查看证据对象'}
            </CardDescription>
          </div>
          <div className='flex shrink-0 items-center gap-2'>
            {service && <State value={service.status} />}
            {service && (
              <Button
                size='sm'
                variant='outline'
                onClick={() => onEdit(service)}
              >
                <Pencil /> 编辑
              </Button>
            )}
          </div>
        </div>
      </CardHeader>
      <CardContent className='space-y-5'>
        {service && (
          <>
            <div className='grid grid-cols-2 gap-3'>
              <Fact label='区域' value={service.region ?? '—'} />
              <Fact label='机房' value={service.datacenter ?? '—'} />
              <Fact label='线路' value={service.line_name ?? '—'} />
              <Fact
                label='有效期'
                value={`${service.activated_on ?? '未开通'} → ${service.deactivated_on ?? '长期'}`}
              />
            </div>
            <Separator />
            <div className='flex items-center justify-between'>
              <div>
                <p className='font-medium'>资源引用</p>
                <p className='mt-1 text-xs text-muted-foreground'>
                  已登记 {resources.length} 个端口、地址或物理资源。
                </p>
              </div>
              <Button size='sm' variant='outline' onClick={onCreate}>
                <Plus /> 登记资源
              </Button>
            </div>
          </>
        )}
        {!service ? (
          <Empty
            icon={<Cpu />}
            title='未选择业务'
            text='从左侧选择一项业务。'
          />
        ) : loading ? (
          <Loading />
        ) : !resources.length ? (
          <p className='rounded-lg border border-dashed p-4 text-sm text-muted-foreground'>
            尚无服务资源。带宽业务建议登记设备与端口，托管业务登记服务器与机柜位置。
          </p>
        ) : (
          <div className='space-y-2'>
            {resources.map((resource) => (
              <div key={resource.id} className='rounded-lg border p-3'>
                <div className='flex items-center justify-between gap-3'>
                  <p className='font-medium'>
                    {resource.display_name ?? resource.resource_ref}
                  </p>
                  <State value={resource.status} />
                </div>
                <p className='mt-2 font-mono text-[11px] text-muted-foreground'>
                  {resource.resource_type} · {resource.resource_ref}
                </p>
                <p className='mt-1 font-mono text-[11px] text-muted-foreground'>
                  {resource.effective_from ?? '未限定'} →{' '}
                  {resource.effective_to ?? '长期'}
                </p>
              </div>
            ))}
          </div>
        )}
      </CardContent>
    </Card>
  )
}

function Signal({
  icon,
  label,
  value,
}: {
  icon: ReactNode
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

function ServiceTypeSelect({
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
        {serviceTypes.map((type) => (
          <SelectItem key={type} value={type}>
            {type}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
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
        value === 'ACTIVE'
          ? 'default'
          : value === 'PENDING'
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
      {Array.from({ length: 6 }).map((_, index) => (
        <Skeleton key={index} className='h-12' />
      ))}
    </div>
  )
}

function Empty({
  icon,
  title,
  text,
}: {
  icon: ReactNode
  title: string
  text: string
}) {
  return (
    <div className='grid place-items-center py-16 text-center'>
      <span className='text-muted-foreground'>{icon}</span>
      <p className='mt-4 font-semibold'>{title}</p>
      <p className='mt-2 max-w-sm text-sm text-muted-foreground'>{text}</p>
    </div>
  )
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

function showMutationError(error: unknown) {
  if (error instanceof Error && !('response' in error)) {
    toast.error(error.message)
    return
  }
  const problem = problemFrom(error)
  toast.error(problem.detail ?? problem.title ?? '操作失败')
}

function ServiceEditDialog({
  service,
  pending,
  onClose,
  onSubmit,
}: {
  service?: Service
  pending: boolean
  onClose: () => void
  onSubmit: (
    id: string,
    version: number,
    input: {
      service_name?: string
      region?: string
      datacenter?: string
      line_name?: string
      activated_on?: string
      deactivated_on?: string
      status?: string
      notes?: string
      reason: string
    }
  ) => void
}) {
  const [name, setName] = useState(service?.service_name ?? '')
  const [region, setRegion] = useState(service?.region ?? '')
  const [datacenter, setDatacenter] = useState(service?.datacenter ?? '')
  const [lineName, setLineName] = useState(service?.line_name ?? '')
  const [activatedOn, setActivatedOn] = useState(service?.activated_on ?? '')
  const [deactivatedOn, setDeactivatedOn] = useState(
    service?.deactivated_on ?? ''
  )
  const [status, setStatus] = useState(service?.status ?? 'ACTIVE')
  const [notes, setNotes] = useState(service?.notes ?? '')
  if (!service) return null

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>编辑业务 · {service.service_no}</DialogTitle>
          <DialogDescription>
            停用不会改写历史账单;有效期变化影响折算。
          </DialogDescription>
        </DialogHeader>
        <div className='grid gap-4 sm:grid-cols-2'>
          <div className='space-y-2 sm:col-span-2'>
            <Label>业务名称</Label>
            <Input value={name} onChange={(e) => setName(e.target.value)} />
          </div>
          <div className='space-y-2'>
            <Label>区域</Label>
            <Input value={region} onChange={(e) => setRegion(e.target.value)} />
          </div>
          <div className='space-y-2'>
            <Label>机房</Label>
            <Input
              value={datacenter}
              onChange={(e) => setDatacenter(e.target.value)}
            />
          </div>
          <div className='space-y-2 sm:col-span-2'>
            <Label>线路</Label>
            <Input
              value={lineName}
              onChange={(e) => setLineName(e.target.value)}
            />
          </div>
          <div className='space-y-2'>
            <Label>开通日期</Label>
            <Input
              type='date'
              value={activatedOn}
              onChange={(e) => setActivatedOn(e.target.value)}
            />
          </div>
          <div className='space-y-2'>
            <Label>停用日期</Label>
            <Input
              type='date'
              value={deactivatedOn}
              onChange={(e) => setDeactivatedOn(e.target.value)}
            />
          </div>
          <div className='space-y-2'>
            <Label>状态</Label>
            <select
              value={status}
              onChange={(e) => setStatus(e.target.value)}
              className='h-9 w-full rounded-md border bg-background px-3 text-sm'
            >
              <option value='PENDING'>PENDING</option>
              <option value='ACTIVE'>ACTIVE</option>
              <option value='SUSPENDED'>SUSPENDED</option>
              <option value='ENDED'>ENDED</option>
            </select>
          </div>
          <div className='space-y-2'>
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
              onSubmit(service.id, service.version, {
                service_name: name.trim(),
                region: region.trim() || undefined,
                datacenter: datacenter.trim() || undefined,
                line_name: lineName.trim() || undefined,
                activated_on: activatedOn || undefined,
                deactivated_on: deactivatedOn || undefined,
                status,
                notes: notes.trim() || undefined,
                reason: '在业务管理页编辑业务',
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
