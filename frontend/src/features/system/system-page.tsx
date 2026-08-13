import { useState } from 'react'
import {
  useMutation,
  useQuery,
  useQueryClient,
  useSuspenseQuery,
} from '@tanstack/react-query'
import {
  Download,
  KeyRound,
  Send,
  ShieldCheck,
  ShieldOff,
  Siren,
  Upload,
  UsersRound,
  Webhook,
} from 'lucide-react'
import { toast } from 'sonner'
import { sessionQuery } from '@/api/auth'
import { problemFrom } from '@/api/http'
import {
  beginMfaEnrollment,
  confirmImport,
  confirmMfaEnrollment,
  createImport,
  createSystemRole,
  createSystemUser,
  createWebhookEndpoint,
  disableMfa,
  importsQuery,
  notificationLogsQuery,
  operationalStatusQuery,
  regenerateMfaRecoveryCodes,
  resetSystemUserPassword,
  systemRolesQuery,
  systemPermissionsQuery,
  systemUsersQuery,
  updateSystemRole,
  updateSystemUserRoles,
  updateSystemUserStatus,
  uploadImportFile,
  updateOperationalSettings,
  webhookEndpointsQuery,
  type SystemRole,
  type SystemUser,
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
import { PasswordInput } from '@/components/password-input'
import { ConsoleHeader } from '@/features/shell/console-header'
import { PageHeading } from '@/features/shell/page-heading'

export function SystemPage() {
  return (
    <>
      <ConsoleHeader label='system' />
      <Main className='space-y-7'>
        <PageHeading
          eyebrow='平台管理'
          title='身份、安全与运行入口'
          description='租户上下文只来自认证会话。这里集中管理用户角色、主数据导入、Webhook 投递与当前管理员的 MFA。'
        />
        <Tabs defaultValue='users'>
          <TabsList className='h-auto flex-wrap'>
            <TabsTrigger value='users'>
              <UsersRound />
              用户与角色
            </TabsTrigger>
            <TabsTrigger value='imports'>
              <Upload />
              主数据导入
            </TabsTrigger>
            <TabsTrigger value='webhooks'>
              <Webhook />
              Webhook
            </TabsTrigger>
            <TabsTrigger value='deliveries'>
              <Send />
              投递日志
            </TabsTrigger>
            <TabsTrigger value='security'>
              <KeyRound />
              MFA
            </TabsTrigger>
            <TabsTrigger value='operations'>
              <Siren />
              运维开关
            </TabsTrigger>
          </TabsList>
          <TabsContent value='users'>
            <UsersPanel />
          </TabsContent>
          <TabsContent value='imports'>
            <ImportsPanel />
          </TabsContent>
          <TabsContent value='webhooks'>
            <WebhooksPanel />
          </TabsContent>
          <TabsContent value='deliveries'>
            <DeliveriesPanel />
          </TabsContent>
          <TabsContent value='security'>
            <SecurityPanel />
          </TabsContent>
          <TabsContent value='operations'>
            <OperationsPanel />
          </TabsContent>
        </Tabs>
      </Main>
    </>
  )
}

function UsersPanel() {
  const queryClient = useQueryClient()
  const users = useQuery(systemUsersQuery)
  const roles = useQuery(systemRolesQuery)
  const permissions = useQuery(systemPermissionsQuery)
  const [createUserOpen, setCreateUserOpen] = useState(false)
  const [username, setUsername] = useState('')
  const [email, setEmail] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [temporaryPassword, setTemporaryPassword] = useState('')
  const [newUserRoleIds, setNewUserRoleIds] = useState<string[]>([])
  const [selectedUser, setSelectedUser] = useState<SystemUser>()
  const [selectedUserRoleIds, setSelectedUserRoleIds] = useState<string[]>([])
  const [passwordResetUser, setPasswordResetUser] = useState<SystemUser>()
  const [resetTemporaryPassword, setResetTemporaryPassword] = useState('')
  const [roleEditor, setRoleEditor] = useState<SystemRole | 'new'>()
  const [roleCode, setRoleCode] = useState('')
  const [roleName, setRoleName] = useState('')
  const [rolePermissions, setRolePermissions] = useState<string[]>([])
  const createUser = useMutation({
    mutationFn: () =>
      createSystemUser({
        username,
        email,
        display_name: displayName,
        temporary_password: temporaryPassword,
        role_ids: newUserRoleIds,
      }),
    onSuccess: async () => {
      toast.success('租户用户已创建')
      setCreateUserOpen(false)
      setUsername('')
      setEmail('')
      setDisplayName('')
      setTemporaryPassword('')
      setNewUserRoleIds([])
      await queryClient.invalidateQueries({ queryKey: ['system-users'] })
    },
  })
  const saveUserRoles = useMutation({
    mutationFn: () => updateSystemUserRoles(selectedUser!, selectedUserRoleIds),
    onSuccess: async () => {
      toast.success('用户角色已更新')
      setSelectedUser(undefined)
      await queryClient.invalidateQueries({ queryKey: ['system-users'] })
    },
  })
  const changeStatus = useMutation({
    mutationFn: (user: SystemUser) =>
      updateSystemUserStatus(
        user,
        user.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
      ),
    onSuccess: async () => {
      toast.success('用户状态已更新')
      await queryClient.invalidateQueries({ queryKey: ['system-users'] })
    },
  })
  const resetPassword = useMutation({
    mutationFn: () =>
      resetSystemUserPassword(passwordResetUser!, resetTemporaryPassword),
    onSuccess: async () => {
      toast.success('临时密码已重置，用户须在 24 小时内登录并改密')
      setPasswordResetUser(undefined)
      setResetTemporaryPassword('')
      await queryClient.invalidateQueries({ queryKey: ['system-users'] })
    },
  })
  const saveRole = useMutation({
    mutationFn: () =>
      roleEditor === 'new'
        ? createSystemRole({
            role_code: roleCode,
            role_name: roleName,
            permissions: rolePermissions,
          })
        : updateSystemRole(roleEditor!, roleName, rolePermissions),
    onSuccess: async () => {
      toast.success(roleEditor === 'new' ? '角色已创建' : '角色权限已更新')
      setRoleEditor(undefined)
      setRoleCode('')
      setRoleName('')
      setRolePermissions([])
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['system-roles'] }),
        queryClient.invalidateQueries({ queryKey: ['system-users'] }),
      ])
    },
  })
  return (
    <>
      <div className='grid gap-5 xl:grid-cols-[1.2fr_.8fr]'>
        <Card className='gap-0 overflow-hidden py-0 shadow-none'>
          <CardHeader className='flex flex-col items-start gap-3 border-b py-5 sm:flex-row sm:items-center sm:justify-between'>
            <div>
              <CardTitle className='text-base'>租户用户</CardTitle>
              <CardDescription className='mt-1'>
                角色只授予权限代码；菜单隐藏不替代服务端鉴权。
              </CardDescription>
            </div>
            <Button size='sm' onClick={() => setCreateUserOpen(true)}>
              新增用户
            </Button>
          </CardHeader>
          <CardContent className='p-0'>
            {users.isLoading ? (
              <Loading />
            ) : (
              <Table>
                <TableHeader>
                  <TableRow className='bg-muted/30'>
                    <TableHead>用户</TableHead>
                    <TableHead>角色</TableHead>
                    <TableHead>MFA</TableHead>
                    <TableHead>状态</TableHead>
                    <TableHead />
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {users.data?.map((user) => (
                    <TableRow key={user.id}>
                      <TableCell>
                        <p className='font-medium'>{user.display_name}</p>
                        <p className='mt-1 font-mono text-[11px] text-muted-foreground'>
                          {user.username} · {user.email} · v{user.version}
                        </p>
                        {user.must_change_password && (
                          <Badge variant='outline' className='mt-2'>
                            首次登录须改密
                          </Badge>
                        )}
                      </TableCell>
                      <TableCell className='max-w-64'>
                        <div className='flex flex-wrap gap-1'>
                          {user.roles.map((role) => (
                            <Badge key={role.id} variant='outline'>
                              {role.role_name}
                            </Badge>
                          ))}
                        </div>
                      </TableCell>
                      <TableCell>
                        <Badge
                          variant={user.mfa_enabled ? 'default' : 'secondary'}
                        >
                          {user.mfa_enabled ? '已启用' : '未启用'}
                        </Badge>
                      </TableCell>
                      <TableCell>
                        <Badge
                          variant={
                            user.status === 'ACTIVE' ? 'default' : 'destructive'
                          }
                        >
                          {user.status}
                        </Badge>
                      </TableCell>
                      <TableCell>
                        <div className='flex flex-wrap gap-2'>
                          <Button
                            size='sm'
                            variant='outline'
                            onClick={() => {
                              setSelectedUser(user)
                              setSelectedUserRoleIds(
                                user.roles.map((role) => role.id)
                              )
                            }}
                          >
                            角色
                          </Button>
                          <Button
                            size='sm'
                            variant='outline'
                            onClick={() => {
                              setPasswordResetUser(user)
                              setResetTemporaryPassword('')
                            }}
                          >
                            重置密码
                          </Button>
                          <Button
                            size='sm'
                            variant='ghost'
                            disabled={changeStatus.isPending}
                            onClick={() => changeStatus.mutate(user)}
                          >
                            {user.status === 'ACTIVE' ? '停用' : '启用'}
                          </Button>
                        </div>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
          </CardContent>
        </Card>
        <Card className='shadow-none'>
          <CardHeader className='flex flex-col items-start gap-3 sm:flex-row sm:items-center sm:justify-between'>
            <div>
              <CardTitle className='text-base'>角色权限</CardTitle>
              <CardDescription className='mt-1'>
                当前租户已配置 {roles.data?.length ?? 0} 个角色。
              </CardDescription>
            </div>
            <Button
              size='sm'
              variant='outline'
              onClick={() => {
                setRoleEditor('new')
                setRoleCode('')
                setRoleName('')
                setRolePermissions([])
              }}
            >
              新增角色
            </Button>
          </CardHeader>
          <CardContent className='space-y-3'>
            {roles.data?.map((role) => (
              <button
                key={role.id}
                className='w-full min-w-0 rounded-lg border p-4 text-left hover:bg-muted/30'
                onClick={() => {
                  setRoleEditor(role)
                  setRoleCode(role.role_code)
                  setRoleName(role.role_name)
                  setRolePermissions(role.permissions)
                }}
              >
                <div className='flex min-w-0 flex-col items-start gap-2 sm:flex-row sm:justify-between'>
                  <p className='min-w-0 font-medium break-words'>
                    {role.role_name}
                  </p>
                  <Badge
                    variant='outline'
                    className='max-w-full min-w-0 text-left break-all whitespace-normal sm:max-w-[55%]'
                  >
                    {role.role_code}
                  </Badge>
                </div>
                <p className='mt-2 text-xs text-muted-foreground'>
                  {role.permissions.length} 项权限 · v{role.version}
                </p>
              </button>
            ))}
          </CardContent>
        </Card>
      </div>
      <Dialog open={createUserOpen} onOpenChange={setCreateUserOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>创建租户用户</DialogTitle>
            <DialogDescription>
              临时密码 24 小时后失效，用户首次登录只能更换密码或退出。密码须为
              12–200 位，并满足字符组合策略。
            </DialogDescription>
          </DialogHeader>
          <div className='grid gap-4 sm:grid-cols-2'>
            <Field label='用户名'>
              <Input
                value={username}
                onChange={(event) => setUsername(event.target.value)}
              />
            </Field>
            <Field label='显示名称'>
              <Input
                value={displayName}
                onChange={(event) => setDisplayName(event.target.value)}
              />
            </Field>
            <Field label='邮箱' className='sm:col-span-2'>
              <Input
                type='email'
                value={email}
                onChange={(event) => setEmail(event.target.value)}
              />
            </Field>
            <Field label='临时密码' className='sm:col-span-2'>
              <PasswordInput
                autoComplete='new-password'
                value={temporaryPassword}
                onChange={(event) => setTemporaryPassword(event.target.value)}
              />
            </Field>
            <RoleChecks
              roles={roles.data ?? []}
              selected={newUserRoleIds}
              onChange={setNewUserRoleIds}
            />
          </div>
          <DialogFooter>
            <Button variant='outline' onClick={() => setCreateUserOpen(false)}>
              取消
            </Button>
            <Button
              disabled={
                createUser.isPending ||
                username.length < 3 ||
                !email.includes('@') ||
                displayName.length < 2 ||
                temporaryPassword.length < 12
              }
              onClick={() => createUser.mutate()}
            >
              创建用户
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
      <Dialog
        open={Boolean(passwordResetUser)}
        onOpenChange={(open) => {
          if (!open) {
            setPasswordResetUser(undefined)
            setResetTemporaryPassword('')
          }
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>
              重置 {passwordResetUser?.display_name} 的临时密码
            </DialogTitle>
            <DialogDescription>
              保存后旧会话立即失效。新临时密码仅 24
              小时有效，用户登录后必须先改密。
            </DialogDescription>
          </DialogHeader>
          <Field label='新临时密码'>
            <PasswordInput
              autoComplete='new-password'
              value={resetTemporaryPassword}
              onChange={(event) =>
                setResetTemporaryPassword(event.target.value)
              }
            />
          </Field>
          <p className='text-xs leading-5 text-muted-foreground'>
            使用 12–200 位字符；12–19 位至少包含三类字符，20
            位以上长密码至少包含两类字符，且不得包含用户名。
          </p>
          <DialogFooter className='gap-2 sm:gap-0'>
            <Button
              variant='outline'
              onClick={() => setPasswordResetUser(undefined)}
            >
              取消
            </Button>
            <Button
              disabled={
                resetPassword.isPending || resetTemporaryPassword.length < 12
              }
              onClick={() => resetPassword.mutate()}
            >
              重置并使旧会话失效
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
      <Dialog
        open={Boolean(selectedUser)}
        onOpenChange={(open) => !open && setSelectedUser(undefined)}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>更新 {selectedUser?.display_name} 的角色</DialogTitle>
            <DialogDescription>
              修改会增加用户版本并立即影响下一次服务端鉴权。
            </DialogDescription>
          </DialogHeader>
          <RoleChecks
            roles={roles.data ?? []}
            selected={selectedUserRoleIds}
            onChange={setSelectedUserRoleIds}
          />
          <DialogFooter>
            <Button
              disabled={saveUserRoles.isPending}
              onClick={() => saveUserRoles.mutate()}
            >
              保存角色
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
      <Dialog
        open={Boolean(roleEditor)}
        onOpenChange={(open) => !open && setRoleEditor(undefined)}
      >
        <DialogContent className='max-h-[calc(100svh-2rem)] overflow-y-auto sm:max-w-2xl'>
          <DialogHeader>
            <DialogTitle>
              {roleEditor === 'new' ? '创建角色' : '编辑角色权限'}
            </DialogTitle>
            <DialogDescription>
              权限代码由后端白名单验证，未知权限不能保存。
            </DialogDescription>
          </DialogHeader>
          <div className='grid gap-4 sm:grid-cols-2'>
            <Field label='角色代码'>
              <Input
                value={roleCode}
                disabled={roleEditor !== 'new'}
                onChange={(event) =>
                  setRoleCode(event.target.value.toUpperCase())
                }
              />
            </Field>
            <Field label='角色名称'>
              <Input
                value={roleName}
                onChange={(event) => setRoleName(event.target.value)}
              />
            </Field>
          </div>
          <div className='grid gap-2 sm:grid-cols-2'>
            {permissions.data?.map((permission) => (
              <label
                key={permission.permission_code}
                className='flex gap-3 rounded-lg border p-3 text-sm'
              >
                <input
                  type='checkbox'
                  checked={rolePermissions.includes(permission.permission_code)}
                  onChange={(event) =>
                    setRolePermissions((current) =>
                      event.target.checked
                        ? [...current, permission.permission_code]
                        : current.filter(
                            (item) => item !== permission.permission_code
                          )
                    )
                  }
                />
                <span>
                  <strong className='font-mono text-xs'>
                    {permission.permission_code}
                  </strong>
                  <span className='mt-1 block text-xs text-muted-foreground'>
                    {permission.description}
                  </span>
                </span>
              </label>
            ))}
          </div>
          <DialogFooter>
            <Button
              disabled={
                saveRole.isPending || roleCode.length < 3 || roleName.length < 2
              }
              onClick={() => saveRole.mutate()}
            >
              保存角色
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  )
}

function RoleChecks({
  roles,
  selected,
  onChange,
}: {
  roles: SystemRole[]
  selected: string[]
  onChange: (value: string[]) => void
}) {
  return (
    <div className='grid gap-2 sm:col-span-2 sm:grid-cols-2'>
      {roles.map((role) => (
        <label
          key={role.id}
          className='flex items-center gap-3 rounded-lg border p-3 text-sm'
        >
          <input
            type='checkbox'
            checked={selected.includes(role.id)}
            onChange={(event) =>
              onChange(
                event.target.checked
                  ? [...selected, role.id]
                  : selected.filter((item) => item !== role.id)
              )
            }
          />
          <span>{role.role_name}</span>
        </label>
      ))}
    </div>
  )
}

function ImportsPanel() {
  const queryClient = useQueryClient()
  const imports = useQuery(importsQuery)
  const [file, setFile] = useState<File>()
  const [type, setType] = useState('CUSTOMERS')
  const create = useMutation({
    mutationFn: async () => {
      const uploaded = await uploadImportFile(file!)
      return createImport(uploaded.id, type)
    },
    onSuccess: async (result) => {
      toast.success(`校验任务已入队：${result.job_id.slice(0, 8)}`)
      setFile(undefined)
      await queryClient.invalidateQueries({ queryKey: ['imports'] })
    },
  })
  const confirm = useMutation({
    mutationFn: confirmImport,
    onSuccess: async () => {
      toast.success('确认导入任务已入队')
      await queryClient.invalidateQueries({ queryKey: ['imports'] })
    },
  })
  return (
    <div className='space-y-5'>
      <Card className='shadow-none'>
        <CardHeader>
          <CardTitle className='text-base'>上传并校验</CardTitle>
          <CardDescription>
            顺序：客户 → 公司 → 业务 → 合同 →
            合同计费项。文件先进入暂存区，不直接写正式表。
          </CardDescription>
        </CardHeader>
        <CardContent className='grid gap-4 md:grid-cols-[220px_1fr_auto]'>
          <select
            value={type}
            onChange={(event) => setType(event.target.value)}
            className='h-9 rounded-md border bg-background px-3 text-sm'
          >
            <option value='CUSTOMERS'>客户</option>
            <option value='COMPANIES'>公司</option>
            <option value='SERVICES'>业务</option>
            <option value='CONTRACTS'>合同</option>
            <option value='CONTRACT_ITEMS'>合同计费项</option>
          </select>
          <Input
            type='file'
            accept='.csv,.xlsx'
            onChange={(event) => setFile(event.target.files?.[0])}
          />
          <Button
            disabled={!file || create.isPending}
            onClick={() => create.mutate()}
          >
            <Upload />
            {create.isPending ? '正在上传…' : '开始校验'}
          </Button>
        </CardContent>
      </Card>
      <Card className='gap-0 overflow-hidden py-0 shadow-none'>
        <CardContent className='p-0'>
          {imports.isLoading ? (
            <Loading />
          ) : (
            <Table>
              <TableHeader>
                <TableRow className='bg-muted/30'>
                  <TableHead>文件 / 类型</TableHead>
                  <TableHead>状态</TableHead>
                  <TableHead>总行</TableHead>
                  <TableHead>有效 / 错误</TableHead>
                  <TableHead>已导入</TableHead>
                  <TableHead>操作</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {imports.data?.map((job) => (
                  <TableRow key={job.id}>
                    <TableCell>
                      <p className='font-medium'>{job.source_filename}</p>
                      <p className='mt-1 font-mono text-[11px] text-muted-foreground'>
                        {job.import_type} · {job.id.slice(0, 8)}
                      </p>
                    </TableCell>
                    <TableCell>
                      <State value={job.status} />
                    </TableCell>
                    <TableCell className='font-mono'>
                      {job.total_rows}
                    </TableCell>
                    <TableCell className='font-mono'>
                      {job.valid_rows} /{' '}
                      <span
                        className={job.invalid_rows ? 'text-destructive' : ''}
                      >
                        {job.invalid_rows}
                      </span>
                    </TableCell>
                    <TableCell className='font-mono'>
                      {job.imported_rows}
                    </TableCell>
                    <TableCell>
                      <div className='flex gap-2'>
                        {job.error_file_id && (
                          <Button size='sm' variant='outline' asChild>
                            <a
                              href={`/api/v1/files/${job.error_file_id}/content`}
                            >
                              <Download />
                              错误文件
                            </a>
                          </Button>
                        )}
                        <Button
                          size='sm'
                          disabled={
                            job.status !== 'READY' ||
                            job.invalid_rows > 0 ||
                            confirm.isPending
                          }
                          onClick={() => confirm.mutate(job.id)}
                        >
                          确认导入
                        </Button>
                      </div>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>
    </div>
  )
}

function WebhooksPanel() {
  const queryClient = useQueryClient()
  const endpoints = useQuery(webhookEndpointsQuery)
  const [code, setCode] = useState('')
  const [name, setName] = useState('')
  const [url, setUrl] = useState('')
  const [secret, setSecret] = useState('')
  const create = useMutation({
    mutationFn: () =>
      createWebhookEndpoint({
        endpoint_code: code,
        endpoint_name: name,
        target_url: url,
        signing_secret: secret,
      }),
    onSuccess: async () => {
      toast.success('Webhook 端点已创建')
      setCode('')
      setName('')
      setUrl('')
      setSecret('')
      await queryClient.invalidateQueries({ queryKey: ['webhook-endpoints'] })
    },
  })
  return (
    <div className='grid gap-5 xl:grid-cols-[380px_1fr]'>
      <Card className='shadow-none'>
        <CardHeader>
          <CardTitle className='text-base'>新增端点</CardTitle>
          <CardDescription>
            保存时和投递时都会重新解析
            DNS，并阻断私网、环回、链路本地与云元数据地址。
          </CardDescription>
        </CardHeader>
        <CardContent className='space-y-4'>
          <Field label='端点代码'>
            <Input
              value={code}
              onChange={(event) => setCode(event.target.value.toUpperCase())}
              placeholder='ERP_FINANCE'
            />
          </Field>
          <Field label='名称'>
            <Input
              value={name}
              onChange={(event) => setName(event.target.value)}
            />
          </Field>
          <Field label='目标 URL'>
            <Input
              value={url}
              onChange={(event) => setUrl(event.target.value)}
              placeholder='https://example.com/webhooks/invoice'
            />
          </Field>
          <Field label='签名密钥'>
            <Input
              type='password'
              value={secret}
              onChange={(event) => setSecret(event.target.value)}
            />
          </Field>
          <Button
            className='w-full'
            disabled={
              create.isPending ||
              code.length < 3 ||
              name.length < 2 ||
              !url ||
              secret.length < 16
            }
            onClick={() => create.mutate()}
          >
            {create.isPending ? '正在创建…' : '创建端点'}
          </Button>
        </CardContent>
      </Card>
      <Card className='gap-0 overflow-hidden py-0 shadow-none'>
        <CardContent className='p-0'>
          {endpoints.isLoading ? (
            <Loading />
          ) : (
            <Table>
              <TableHeader>
                <TableRow className='bg-muted/30'>
                  <TableHead>端点</TableHead>
                  <TableHead>目标</TableHead>
                  <TableHead>状态</TableHead>
                  <TableHead>连续失败</TableHead>
                  <TableHead>最后成功</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {endpoints.data?.map((endpoint) => (
                  <TableRow key={endpoint.id}>
                    <TableCell>
                      <p className='font-medium'>{endpoint.endpoint_name}</p>
                      <p className='mt-1 font-mono text-[11px] text-muted-foreground'>
                        {endpoint.endpoint_code}
                      </p>
                    </TableCell>
                    <TableCell className='max-w-sm truncate text-xs'>
                      {endpoint.target_url}
                    </TableCell>
                    <TableCell>
                      <State value={endpoint.status} />
                    </TableCell>
                    <TableCell className='font-mono'>
                      {endpoint.consecutive_failures}
                    </TableCell>
                    <TableCell className='font-mono text-xs'>
                      {endpoint.last_success_at?.slice(0, 19) ?? '—'}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>
    </div>
  )
}

function DeliveriesPanel() {
  const logs = useQuery(notificationLogsQuery)
  return (
    <Card className='gap-0 overflow-hidden py-0 shadow-none'>
      <CardHeader className='border-b py-5'>
        <CardTitle className='text-base'>邮件与 Webhook 投递</CardTitle>
        <CardDescription>
          失败投递保留错误码、重试次数和持久任务，可在任务控制台对死信进行人工重试。
        </CardDescription>
      </CardHeader>
      <CardContent className='p-0'>
        {logs.isLoading ? (
          <Loading />
        ) : (
          <Table>
            <TableHeader>
              <TableRow className='bg-muted/30'>
                <TableHead>事件</TableHead>
                <TableHead>通道 / 收件方</TableHead>
                <TableHead>状态</TableHead>
                <TableHead>尝试</TableHead>
                <TableHead>错误</TableHead>
                <TableHead>创建时间</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {logs.data?.map((log) => (
                <TableRow key={log.id}>
                  <TableCell className='font-mono text-xs'>
                    {log.event_type}
                    <p className='mt-1 text-[11px] text-muted-foreground'>
                      {log.id.slice(0, 8)}
                    </p>
                  </TableCell>
                  <TableCell>
                    <p className='font-medium'>{log.channel}</p>
                    <p className='mt-1 max-w-64 truncate text-xs text-muted-foreground'>
                      {log.recipient}
                    </p>
                  </TableCell>
                  <TableCell>
                    <State value={log.status} />
                  </TableCell>
                  <TableCell className='font-mono'>
                    {log.attempt_count}
                  </TableCell>
                  <TableCell className='max-w-xs text-xs text-destructive'>
                    {log.last_error_code ?? '—'} {log.last_error_message ?? ''}
                  </TableCell>
                  <TableCell className='font-mono text-xs'>
                    {log.created_at.slice(0, 19)}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </CardContent>
    </Card>
  )
}

function OperationsPanel() {
  const queryClient = useQueryClient()
  const status = useQuery(operationalStatusQuery)
  const users = useQuery(systemUsersQuery)
  const [systemUserId, setSystemUserId] = useState<string>()
  const [autoGeneration, setAutoGeneration] = useState<boolean>()
  const [autoSend, setAutoSend] = useState<boolean>()
  const [emergencyStop, setEmergencyStop] = useState<boolean>()
  const [emergencyReason, setEmergencyReason] = useState<string>()
  const settings = status.data?.settings
  const effectiveSystemUser = systemUserId ?? settings?.system_user_id ?? ''
  const effectiveAutoGeneration =
    autoGeneration ?? settings?.auto_generation_enabled ?? false
  const effectiveAutoSend = autoSend ?? settings?.auto_send_enabled ?? false
  const effectiveEmergencyStop =
    emergencyStop ?? settings?.emergency_stop ?? false
  const effectiveEmergencyReason =
    emergencyReason ?? settings?.emergency_reason ?? ''
  const update = useMutation({
    mutationFn: () =>
      updateOperationalSettings(settings!, {
        system_user_id: effectiveSystemUser || null,
        auto_generation_enabled: effectiveAutoGeneration,
        auto_send_enabled: effectiveAutoSend,
        emergency_stop: effectiveEmergencyStop,
        emergency_reason: effectiveEmergencyStop
          ? effectiveEmergencyReason
          : null,
      }),
    onSuccess: async () => {
      toast.success(
        effectiveEmergencyStop ? '紧急停用已生效' : '运维开关已更新'
      )
      setSystemUserId(undefined)
      setAutoGeneration(undefined)
      setAutoSend(undefined)
      setEmergencyStop(undefined)
      setEmergencyReason(undefined)
      await queryClient.invalidateQueries({ queryKey: ['operational-status'] })
    },
  })
  if (status.isLoading || !status.data || !settings) return <Loading />
  return (
    <div className='grid gap-5 xl:grid-cols-[1fr_.9fr]'>
      <Card className='shadow-none'>
        <CardHeader>
          <CardTitle className='flex items-center gap-2 text-base'>
            <Siren className='size-4 text-destructive' />
            自动化与紧急停用
          </CardTitle>
          <CardDescription>
            自动生成和自动发送是独立开关；紧急停用优先级最高，不删除已持久化任务和审计记录。
          </CardDescription>
        </CardHeader>
        <CardContent className='space-y-5'>
          <Field label='自动任务执行用户'>
            <select
              value={effectiveSystemUser}
              onChange={(event) => setSystemUserId(event.target.value)}
              className='h-9 w-full rounded-md border bg-background px-3 text-sm'
            >
              <option value=''>请选择活动用户</option>
              {users.data
                ?.filter((user) => user.status === 'ACTIVE')
                .map((user) => (
                  <option key={user.id} value={user.id}>
                    {user.display_name} · {user.username}
                  </option>
                ))}
            </select>
          </Field>
          <ToggleRow
            label='自动生成预览'
            description='Quartz 按账单配置的账单日为上一完整月创建幂等持久任务。'
            checked={effectiveAutoGeneration}
            onCheckedChange={setAutoGeneration}
          />
          <ToggleRow
            label='自动发送正式账单'
            description='仅当租户开关和账单配置 auto_send 同时启用时，由 Outbox 触发。'
            checked={effectiveAutoSend}
            onCheckedChange={setAutoSend}
          />
          <ToggleRow
            label='紧急停用'
            description='立即阻止新的自动生成和自动发送；人工命令仍受权限与审计约束。'
            checked={effectiveEmergencyStop}
            onCheckedChange={setEmergencyStop}
            destructive
          />
          {effectiveEmergencyStop && (
            <Field label='紧急停用原因'>
              <Textarea
                value={effectiveEmergencyReason}
                onChange={(event) => setEmergencyReason(event.target.value)}
                placeholder='说明影响范围、值班负责人和恢复条件'
              />
            </Field>
          )}
          <Button
            disabled={
              update.isPending ||
              (effectiveAutoGeneration && !effectiveSystemUser) ||
              (effectiveEmergencyStop &&
                effectiveEmergencyReason.trim().length < 2)
            }
            onClick={() => update.mutate()}
          >
            保存运维设置
          </Button>
        </CardContent>
      </Card>
      <div className='grid content-start gap-4 sm:grid-cols-2'>
        <QueueMetric label='待处理任务' value={status.data.pending_jobs} />
        <QueueMetric label='任务死信' value={status.data.dead_jobs} bad />
        <QueueMetric
          label='待发布 Outbox'
          value={status.data.pending_outbox_events}
        />
        <QueueMetric
          label='Outbox 死信'
          value={status.data.dead_outbox_events}
          bad
        />
        <QueueMetric
          label='待发送通知'
          value={status.data.pending_notifications}
        />
        <QueueMetric
          label='失败通知'
          value={status.data.failed_notifications}
          bad
        />
        <QueueMetric
          label='正式化中账单'
          value={status.data.finalizing_invoices}
        />
        <QueueMetric
          label='最老任务'
          value={
            status.data.oldest_pending_job_at
              ? status.data.oldest_pending_job_at.slice(0, 16).replace('T', ' ')
              : '—'
          }
        />
      </div>
    </div>
  )
}

function ToggleRow({
  label,
  description,
  checked,
  onCheckedChange,
  destructive,
}: {
  label: string
  description: string
  checked: boolean
  onCheckedChange: (checked: boolean) => void
  destructive?: boolean
}) {
  return (
    <div className='flex items-center justify-between gap-4 rounded-lg border p-4'>
      <div>
        <p
          className={
            destructive ? 'font-medium text-destructive' : 'font-medium'
          }
        >
          {label}
        </p>
        <p className='mt-1 text-xs leading-5 text-muted-foreground'>
          {description}
        </p>
      </div>
      <Switch checked={checked} onCheckedChange={onCheckedChange} />
    </div>
  )
}

function QueueMetric({
  label,
  value,
  bad,
}: {
  label: string
  value: number | string
  bad?: boolean
}) {
  return (
    <Card className='py-5 shadow-none'>
      <CardContent>
        <p className='text-xs text-muted-foreground'>{label}</p>
        <p
          className={`mt-2 font-mono text-2xl font-semibold ${bad && Number(value) > 0 ? 'text-destructive' : ''}`}
        >
          {value}
        </p>
      </CardContent>
    </Card>
  )
}

function SecurityPanel() {
  const queryClient = useQueryClient()
  const { data: session } = useSuspenseQuery(sessionQuery)
  const [enrollment, setEnrollment] = useState<{
    secret: string
    otpauth_uri: string
    enrollment_proof: string
  }>()
  const [currentPassword, setCurrentPassword] = useState('')
  const [code, setCode] = useState('')
  const [recoveryCodes, setRecoveryCodes] = useState<string[]>()
  const [justEnabled, setJustEnabled] = useState(false)
  const [action, setAction] = useState<'regenerate' | 'disable'>()
  const [actionCode, setActionCode] = useState('')
  const mfaEnabled = Boolean(session.mfa_enabled) || justEnabled
  const begin = useMutation({
    mutationFn: () => beginMfaEnrollment(currentPassword),
    onSuccess: (value) => {
      setEnrollment(value)
      setCurrentPassword('')
      setJustEnabled(false)
      toast.success('MFA 注册密钥已生成')
    },
    onError: (error) => {
      const problem = problemFrom(error)
      toast.error(problem.detail ?? problem.title ?? '开始注册失败')
    },
  })
  const confirm = useMutation({
    mutationFn: () => confirmMfaEnrollment(code, enrollment!.enrollment_proof),
    onSuccess: async (value) => {
      setRecoveryCodes(value.recovery_codes)
      setEnrollment(undefined)
      setCode('')
      setJustEnabled(true)
      toast.success('MFA 已启用，请离线保存恢复码')
      await queryClient.invalidateQueries({ queryKey: ['session'] })
    },
    onError: (error) => {
      const problem = problemFrom(error)
      toast.error(problem.detail ?? problem.title ?? '确认失败')
    },
  })
  const manage = useMutation({
    mutationFn: async (): Promise<{
      recovery_codes?: string[]
      mfa_enabled?: boolean
      version: number
    }> =>
      action === 'regenerate'
        ? regenerateMfaRecoveryCodes(actionCode)
        : disableMfa(actionCode),
    onSuccess: async (value) => {
      if (action === 'regenerate' && value.recovery_codes) {
        setRecoveryCodes(value.recovery_codes)
        setJustEnabled(true)
        toast.success('新的恢复码已生成,旧恢复码全部失效')
      } else {
        setJustEnabled(false)
        setRecoveryCodes(undefined)
        toast.success('MFA 已禁用')
      }
      setAction(undefined)
      setActionCode('')
      await queryClient.invalidateQueries({ queryKey: ['session'] })
    },
    onError: (error) => {
      const problem = problemFrom(error)
      toast.error(problem.detail ?? problem.title ?? '操作失败')
    },
  })
  return (
    <div className='grid gap-5 lg:grid-cols-2'>
      <Card className='shadow-none'>
        <CardHeader>
          <CardTitle className='flex items-center gap-2 text-base'>
            <ShieldCheck className='size-4 text-emerald-700' />
            TOTP 多因素认证
          </CardTitle>
          <CardDescription>
            密钥只在注册响应中展示；服务端使用 AES-GCM 加密保存。
          </CardDescription>
        </CardHeader>
        <CardContent className='space-y-4'>
          {mfaEnabled ? (
            <div className='space-y-4'>
              <div className='rounded-lg border border-emerald-600/30 bg-emerald-500/5 p-4 text-sm leading-6'>
                <p className='font-medium text-emerald-800 dark:text-emerald-300'>
                  MFA 已启用
                </p>
                <p className='mt-1 text-muted-foreground'>
                  敏感操作会要求动态码。可在此重置恢复码或禁用 MFA。
                </p>
              </div>
              <div className='flex flex-wrap gap-2'>
                <Button
                  variant='outline'
                  onClick={() => setAction('regenerate')}
                >
                  <KeyRound /> 重新生成恢复码
                </Button>
                <Button
                  variant='destructive'
                  onClick={() => setAction('disable')}
                >
                  <ShieldOff /> 禁用 MFA
                </Button>
              </div>
            </div>
          ) : !enrollment ? (
            <div className='space-y-4'>
              <Field label='当前密码'>
                <PasswordInput
                  autoComplete='current-password'
                  value={currentPassword}
                  onChange={(event) => setCurrentPassword(event.target.value)}
                />
              </Field>
              <p className='text-xs leading-5 text-muted-foreground'>
                开始注册前须重新验证当前密码。密码和短时注册凭证只保存在本页内存中。
              </p>
              <Button
                className='w-full sm:w-auto'
                onClick={() => begin.mutate()}
                disabled={!currentPassword || begin.isPending}
              >
                开始注册
              </Button>
            </div>
          ) : (
            <>
              <div className='rounded-lg border bg-muted/20 p-4'>
                <p className='text-xs text-muted-foreground'>手工密钥</p>
                <p className='mt-2 font-mono text-sm break-all'>
                  {enrollment.secret}
                </p>
                <p className='mt-3 text-xs break-all text-muted-foreground'>
                  {enrollment.otpauth_uri}
                </p>
              </div>
              <Field label='认证器中的 6 位代码'>
                <Input
                  value={code}
                  onChange={(event) => setCode(event.target.value)}
                  inputMode='numeric'
                  maxLength={6}
                />
              </Field>
              <Button
                className='w-full sm:w-auto'
                disabled={!/^\d{6}$/.test(code) || confirm.isPending}
                onClick={() => confirm.mutate()}
              >
                确认并启用
              </Button>
            </>
          )}
        </CardContent>
      </Card>
      <Card className='shadow-none'>
        <CardHeader>
          <CardTitle className='text-base'>一次性恢复码</CardTitle>
          <CardDescription>
            每个恢复码登录成功后立即从数据库中原子移除。
          </CardDescription>
        </CardHeader>
        <CardContent>
          {recoveryCodes ? (
            <div className='grid grid-cols-1 gap-2 min-[360px]:grid-cols-2'>
              {recoveryCodes.map((item) => (
                <code
                  key={item}
                  className='rounded border bg-muted/20 p-2 text-center text-xs'
                >
                  {item}
                </code>
              ))}
            </div>
          ) : (
            <p className='text-sm leading-6 text-muted-foreground'>
              完成 TOTP
              确认后，恢复码只展示一次。不要复制到工单、日志或普通聊天中。
            </p>
          )}
        </CardContent>
      </Card>
      <Dialog
        open={Boolean(action)}
        onOpenChange={(open) => !open && setAction(undefined)}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>
              {action === 'regenerate' ? '重新生成恢复码' : '禁用 MFA'}
            </DialogTitle>
            <DialogDescription>
              {action === 'regenerate'
                ? '生成后旧恢复码立即全部失效。'
                : '禁用后敏感操作不再需要动态码。请输入当前认证器中的 6 位代码确认。'}
            </DialogDescription>
          </DialogHeader>
          <Field label='6 位动态码'>
            <Input
              value={actionCode}
              onChange={(event) => setActionCode(event.target.value)}
              inputMode='numeric'
              maxLength={6}
            />
          </Field>
          <DialogFooter>
            <Button variant='outline' onClick={() => setAction(undefined)}>
              取消
            </Button>
            <Button
              variant={action === 'disable' ? 'destructive' : 'default'}
              disabled={!/^\d{6}$/.test(actionCode) || manage.isPending}
              onClick={() => manage.mutate()}
            >
              确认
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}

function Field({
  label,
  children,
  className,
}: {
  label: string
  children: React.ReactNode
  className?: string
}) {
  return (
    <div className={['space-y-2', className].filter(Boolean).join(' ')}>
      <Label>{label}</Label>
      {children}
    </div>
  )
}
function State({ value }: { value: string }) {
  const bad = ['FAILED', 'DEAD', 'ERROR', 'DISABLED'].includes(value)
  const good = ['SUCCESS', 'SENT', 'ACTIVE', 'READY'].includes(value)
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
