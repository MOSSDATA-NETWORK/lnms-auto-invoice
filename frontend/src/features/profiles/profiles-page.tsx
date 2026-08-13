import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CalendarRange, Pencil, Play, ShieldCheck } from 'lucide-react'
import { toast } from 'sonner'
import { problemFrom } from '@/api/http'
import {
  billingEntitiesQuery,
  generatePreview,
  profilesQuery,
  updateInvoiceProfile,
  type InvoiceProfile,
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

export function ProfilesPage() {
  const queryClient = useQueryClient()
  const profiles = useQuery(profilesQuery)
  const [selected, setSelected] = useState<InvoiceProfile>()
  const [editing, setEditing] = useState<InvoiceProfile>()
  const updateMutation = useMutation({
    mutationFn: ({
      id,
      version,
      input,
    }: {
      id: string
      version: number
      input: Parameters<typeof updateInvoiceProfile>[2]
    }) => updateInvoiceProfile(id, version, input),
    onSuccess: async () => {
      toast.success('账单配置已更新')
      setEditing(undefined)
      await queryClient.invalidateQueries({ queryKey: ['invoice-profiles'] })
    },
    onError: (error) => {
      const problem = problemFrom(error)
      toast.error(problem.detail ?? problem.title ?? '更新配置失败')
    },
  })
  const active =
    profiles.data?.filter((profile) => profile.status === 'ACTIVE').length ?? 0
  const auto =
    profiles.data?.filter((profile) => profile.auto_generate).length ?? 0
  return (
    <>
      <ConsoleHeader label='profiles' />
      <Main className='space-y-7'>
        <PageHeading
          eyebrow='出账编排'
          title='账单配置与计费项归属'
          description='每张自动账单由独立配置确定客户、公司、币种、模板、审批流程和计费项归属。多账单必须显式分摊或 DISPLAY_ONLY。'
        />
        <div className='grid [grid-template-columns:repeat(auto-fit,minmax(min(100%,13rem),1fr))] gap-4'>
          <Signal icon={<ShieldCheck />} label='有效配置' value={active} />
          <Signal icon={<Play />} label='自动生成已开' value={auto} />
          <Signal icon={<CalendarRange />} label='默认周期' value='MONTHLY' />
        </div>
        <div className='overflow-hidden rounded-xl border bg-card'>
          {profiles.isLoading ? (
            <Loading />
          ) : !profiles.data?.length ? (
            <div className='p-14 text-center text-sm text-muted-foreground'>
              暂无账单配置。请先发布模板、建立合同计费项，再创建配置。
            </div>
          ) : (
            <Table>
              <TableHeader>
                <TableRow className='bg-muted/30'>
                  <TableHead>配置</TableHead>
                  <TableHead>结算策略</TableHead>
                  <TableHead>自动化</TableHead>
                  <TableHead>状态</TableHead>
                  <TableHead className='text-right'>操作</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {profiles.data.map((profile) => (
                  <TableRow key={profile.id}>
                    <TableCell>
                      <p className='font-medium'>{profile.profile_name}</p>
                      <p className='mt-1 font-mono text-xs text-muted-foreground'>
                        {profile.profile_code}
                      </p>
                    </TableCell>
                    <TableCell>
                      <p className='font-mono text-xs'>
                        {profile.currency_code} · {profile.timezone}
                      </p>
                      <p className='mt-1 text-xs text-muted-foreground'>
                        {profile.billing_cycle} / {profile.payment_terms_days}{' '}
                        天账期
                      </p>
                    </TableCell>
                    <TableCell>
                      <div className='flex flex-wrap gap-1.5'>
                        {profile.auto_generate && (
                          <Badge variant='outline'>生成</Badge>
                        )}
                        {profile.auto_submit_review && (
                          <Badge variant='outline'>提交审核</Badge>
                        )}
                        {profile.auto_send && (
                          <Badge variant='outline'>发送</Badge>
                        )}
                      </div>
                    </TableCell>
                    <TableCell>
                      <Badge
                        variant={
                          profile.status === 'ACTIVE' ? 'default' : 'secondary'
                        }
                      >
                        {profile.status}
                      </Badge>
                    </TableCell>
                    <TableCell className='text-right'>
                      <div className='flex justify-end gap-2'>
                        <Button
                          size='sm'
                          variant='outline'
                          onClick={() => setEditing(profile)}
                        >
                          <Pencil />
                          编辑
                        </Button>
                        <Button
                          size='sm'
                          disabled={profile.status !== 'ACTIVE'}
                          onClick={() => setSelected(profile)}
                        >
                          <Play />
                          生成预览
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
      <GenerateDialog
        profile={selected}
        onClose={() => setSelected(undefined)}
      />
      <ProfileEditDialog
        key={editing?.id ?? 'closed'}
        profile={editing}
        pending={updateMutation.isPending}
        onClose={() => setEditing(undefined)}
        onSubmit={(id, version, input) =>
          updateMutation.mutate({ id, version, input })
        }
      />
    </>
  )
}

function GenerateDialog({
  profile,
  onClose,
}: {
  profile?: InvoiceProfile
  onClose: () => void
}) {
  const queryClient = useQueryClient()
  const now = new Date()
  const defaultStart = new Date(now.getFullYear(), now.getMonth() - 1, 1)
  const defaultEnd = new Date(now.getFullYear(), now.getMonth(), 1)
  const [start, setStart] = useState(toLocalInput(defaultStart))
  const [end, setEnd] = useState(toLocalInput(defaultEnd))
  const mutation = useMutation({
    mutationFn: () => {
      if (!profile) throw new Error('未选择账单配置')
      return generatePreview(
        profile.id,
        new Date(start).toISOString(),
        new Date(end).toISOString()
      )
    },
    onSuccess: async (result) => {
      toast.success(`预览任务已进入队列：${result.job_id.slice(0, 8)}`)
      onClose()
      await queryClient.invalidateQueries({ queryKey: ['jobs'] })
    },
  })
  return (
    <Dialog open={Boolean(profile)} onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>生成预览账单</DialogTitle>
          <DialogDescription>
            {profile?.profile_name} · 账期使用半开区间 [开始,
            结束)，结束时刻不计入本期。
          </DialogDescription>
        </DialogHeader>
        <div className='grid gap-4 sm:grid-cols-2'>
          <div className='space-y-2'>
            <Label>账期开始</Label>
            <Input
              type='datetime-local'
              value={start}
              onChange={(event) => setStart(event.target.value)}
            />
          </div>
          <div className='space-y-2'>
            <Label>账期结束</Label>
            <Input
              type='datetime-local'
              value={end}
              onChange={(event) => setEnd(event.target.value)}
            />
          </div>
        </div>
        <div className='rounded-lg border border-amber-300/60 bg-amber-50/50 p-4 text-xs leading-5 text-amber-950 dark:border-amber-900 dark:bg-amber-950/20 dark:text-amber-200'>
          用量型计费项必须已有完全匹配该账期的 LibreNMS Bill History
          快照；关键字段缺失不会按零处理。
        </div>
        <DialogFooter>
          <Button variant='outline' onClick={onClose}>
            取消
          </Button>
          <Button
            disabled={
              mutation.isPending ||
              !start ||
              !end ||
              new Date(start) >= new Date(end)
            }
            onClick={() => mutation.mutate()}
          >
            {mutation.isPending ? '正在入队…' : '确认生成'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function Signal({
  icon,
  label,
  value,
}: {
  icon: React.ReactNode
  label: string
  value: string | number
}) {
  return (
    <Card className='py-5 shadow-none'>
      <CardContent className='flex items-center gap-4'>
        <span className='grid size-10 place-items-center rounded-lg bg-emerald-50 text-emerald-700 dark:bg-emerald-950 dark:text-emerald-300'>
          {icon}
        </span>
        <div>
          <p className='text-xs text-muted-foreground'>{label}</p>
          <p className='mt-1 font-mono text-xl font-semibold'>{value}</p>
        </div>
      </CardContent>
    </Card>
  )
}
function Loading() {
  return (
    <div className='space-y-3 p-6'>
      {Array.from({ length: 4 }).map((_, index) => (
        <Skeleton key={index} className='h-12' />
      ))}
    </div>
  )
}
function toLocalInput(value: Date) {
  const offset = value.getTimezoneOffset() * 60_000
  return new Date(value.getTime() - offset).toISOString().slice(0, 16)
}

function ProfileEditDialog({
  profile,
  pending,
  onClose,
  onSubmit,
}: {
  profile?: InvoiceProfile
  pending: boolean
  onClose: () => void
  onSubmit: (
    id: string,
    version: number,
    input: {
      profile_name?: string
      billing_entity_id?: string
      language?: string
      timezone?: string
      billing_day?: number
      payment_terms_days?: number
      invoice_number_rule?: string
      auto_generate?: boolean
      auto_submit_review?: boolean
      auto_send?: boolean
      status?: string
      notes?: string
      reason: string
    }
  ) => void
}) {
  const entities = useQuery(billingEntitiesQuery)
  const [name, setName] = useState(profile?.profile_name ?? '')
  const [entityId, setEntityId] = useState(profile?.billing_entity_id ?? '')
  const [timezone, setTimezone] = useState(profile?.timezone ?? 'Asia/Shanghai')
  const [billingDay, setBillingDay] = useState(
    String(profile?.billing_day ?? 1)
  )
  const [paymentTerms, setPaymentTerms] = useState(
    String(profile?.payment_terms_days ?? 30)
  )
  const [autoGenerate, setAutoGenerate] = useState(
    profile?.auto_generate ?? false
  )
  const [autoSubmit, setAutoSubmit] = useState(
    profile?.auto_submit_review ?? false
  )
  const [autoSend, setAutoSend] = useState(profile?.auto_send ?? false)
  const [status, setStatus] = useState(profile?.status ?? 'DRAFT')
  if (!profile) return null

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>编辑账单配置 · {profile.profile_code}</DialogTitle>
          <DialogDescription>
            自动化开关影响调度器;停用后不再自动生成预览。
          </DialogDescription>
        </DialogHeader>
        <div className='grid gap-4 sm:grid-cols-2'>
          <div className='space-y-2 sm:col-span-2'>
            <Label>配置名称</Label>
            <Input value={name} onChange={(e) => setName(e.target.value)} />
          </div>
          <div className='space-y-2 sm:col-span-2'>
            <Label>出账主体(切换开票/收款公司)</Label>
            <select
              value={entityId}
              onChange={(e) => setEntityId(e.target.value)}
              className='h-9 w-full rounded-md border bg-background px-3 text-sm'
            >
              <option value=''>未指定</option>
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
            <Label>时区</Label>
            <Input
              className='font-mono'
              value={timezone}
              onChange={(e) => setTimezone(e.target.value)}
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
            <Label>状态</Label>
            <select
              value={status}
              onChange={(e) => setStatus(e.target.value)}
              className='h-9 w-full rounded-md border bg-background px-3 text-sm'
            >
              <option value='DRAFT'>DRAFT</option>
              <option value='ACTIVE'>ACTIVE</option>
              <option value='DISABLED'>DISABLED</option>
            </select>
          </div>
          <div className='space-y-2'>
            <Label>自动生成预览</Label>
            <select
              value={autoGenerate ? 'yes' : 'no'}
              onChange={(e) => setAutoGenerate(e.target.value === 'yes')}
              className='h-9 w-full rounded-md border bg-background px-3 text-sm'
            >
              <option value='no'>关</option>
              <option value='yes'>开</option>
            </select>
          </div>
          <div className='space-y-2'>
            <Label>自动提交审核</Label>
            <select
              value={autoSubmit ? 'yes' : 'no'}
              onChange={(e) => setAutoSubmit(e.target.value === 'yes')}
              className='h-9 w-full rounded-md border bg-background px-3 text-sm'
            >
              <option value='no'>关</option>
              <option value='yes'>开</option>
            </select>
          </div>
          <div className='space-y-2'>
            <Label>自动发送</Label>
            <select
              value={autoSend ? 'yes' : 'no'}
              onChange={(e) => setAutoSend(e.target.value === 'yes')}
              className='h-9 w-full rounded-md border bg-background px-3 text-sm'
            >
              <option value='no'>关</option>
              <option value='yes'>开</option>
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
              onSubmit(profile.id, profile.version, {
                profile_name: name.trim(),
                billing_entity_id: entityId || undefined,
                timezone: timezone.trim(),
                billing_day: Number(billingDay) || undefined,
                payment_terms_days: Number(paymentTerms) || undefined,
                auto_generate: autoGenerate,
                auto_submit_review: autoSubmit,
                auto_send: autoSend,
                status,
                reason: '在账单配置页编辑配置',
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
