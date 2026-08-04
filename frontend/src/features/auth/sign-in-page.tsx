import { useState } from 'react'
import { z } from 'zod'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import { safeRedirectTarget } from '@/auth/safe-redirect'
import { Route } from '@/routes/sign-in'
import {
  ArrowRight,
  BadgeCheck,
  KeyRound,
  LockKeyhole,
  Network,
  ReceiptText,
} from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { Logo } from '@/assets/logo'
import {
  changePassword,
  sessionQuery,
  signIn,
  signOut,
  verifyMfa,
} from '@/api/auth'
import { problemFrom } from '@/api/http'
import type { Session } from '@/api/types'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { PasswordInput } from '@/components/password-input'

const credentialsSchema = z.object({
  tenant_code: z.string().trim().min(2, '请输入租户代码'),
  username: z.string().trim().min(2, '请输入用户名'),
  password: z.string().min(8, '密码至少 8 位'),
})

const mfaSchema = z.object({
  code: z.string().regex(/^\d{6}$/, '请输入六位动态验证码'),
})
const passwordChangeSchema = z
  .object({
    current_password: z.string().min(1, '请输入当前临时密码'),
    new_password: z
      .string()
      .min(12, '新密码至少 12 位')
      .max(200, '新密码最多 200 位'),
    confirm_password: z.string().min(1, '请再次输入新密码'),
  })
  .refine((value) => value.new_password === value.confirm_password, {
    path: ['confirm_password'],
    message: '两次输入的新密码不一致',
  })
type Credentials = z.infer<typeof credentialsSchema>
type MfaInput = z.infer<typeof mfaSchema>
type PasswordChangeInput = z.infer<typeof passwordChangeSchema>

export function SignInPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { redirect } = Route.useSearch()
  const [mfaRequired, setMfaRequired] = useState(false)
  const [pendingSession, setPendingSession] = useState<Session>()
  const [problem, setProblem] = useState<string>()
  const existingSession = useQuery(sessionQuery)
  const credentials = useForm<Credentials>({
    resolver: zodResolver(credentialsSchema),
    defaultValues: { tenant_code: '', username: '', password: '' },
  })
  const mfa = useForm<MfaInput>({
    resolver: zodResolver(mfaSchema),
    defaultValues: { code: '' },
  })
  const passwordChange = useForm<PasswordChangeInput>({
    resolver: zodResolver(passwordChangeSchema),
    defaultValues: {
      current_password: '',
      new_password: '',
      confirm_password: '',
    },
  })
  const activeSession = pendingSession ?? existingSession.data
  const passwordChangeRequired = Boolean(activeSession?.must_change_password)

  const completeSignIn = async (session: Session) => {
    queryClient.setQueryData(sessionQuery.queryKey, session)
    toast.success(`欢迎回来，${session.display_name}`)
    await navigate({
      to: safeRedirectTarget(redirect),
      replace: true,
    })
  }

  const handleAuthenticatedSession = async (session: Session) => {
    queryClient.setQueryData(sessionQuery.queryKey, session)
    if (session.must_change_password) {
      setPendingSession(session)
      setMfaRequired(false)
      setProblem(undefined)
      passwordChange.reset({
        current_password: credentials.getValues('password'),
        new_password: '',
        confirm_password: '',
      })
      return
    }
    await completeSignIn(session)
  }

  const credentialsMutation = useMutation({
    mutationFn: signIn,
    onSuccess: async (result) => {
      setProblem(undefined)
      if (result.mfa_required) setMfaRequired(true)
      else if (result.session) await handleAuthenticatedSession(result.session)
    },
    onError: (error) =>
      setProblem(
        problemFrom(error).detail ?? '登录失败，请核对租户、用户和密码。'
      ),
  })
  const mfaMutation = useMutation({
    mutationFn: ({ code }: MfaInput) => verifyMfa(code),
    onSuccess: handleAuthenticatedSession,
    onError: (error) =>
      setProblem(problemFrom(error).detail ?? '验证码无效或已过期。'),
  })
  const passwordChangeMutation = useMutation({
    mutationFn: (value: PasswordChangeInput) =>
      changePassword({
        current_password: value.current_password,
        new_password: value.new_password,
      }),
    onSuccess: async (session) => {
      setPendingSession(undefined)
      passwordChange.reset()
      await completeSignIn(session)
    },
    onError: (error) =>
      setProblem(
        problemFrom(error).detail ?? '密码更换失败，请核对当前密码和密码策略。'
      ),
  })
  const signOutMutation = useMutation({
    mutationFn: signOut,
    onSuccess: () => {
      queryClient.clear()
      setPendingSession(undefined)
      setMfaRequired(false)
      setProblem(undefined)
      credentials.reset()
      mfa.reset()
      passwordChange.reset()
    },
  })

  return (
    <main className='relative min-h-svh overflow-x-hidden bg-[#07110f] text-white'>
      <div className='absolute inset-0 [background-image:linear-gradient(rgba(255,255,255,.05)_1px,transparent_1px),linear-gradient(90deg,rgba(255,255,255,.05)_1px,transparent_1px)] [background-size:44px_44px] opacity-35' />
      <div className='absolute top-[-12rem] -right-40 size-[34rem] rounded-full bg-emerald-400/15 blur-3xl' />
      <div className='relative grid min-h-svh lg:grid-cols-[1.15fr_.85fr]'>
        <section className='hidden flex-col justify-between border-white/10 px-7 py-8 lg:flex lg:border-e lg:px-14 lg:py-12'>
          <div className='flex items-center gap-3'>
            <span className='grid size-10 place-items-center rounded-lg border border-emerald-300/25 bg-emerald-300/10 text-emerald-300'>
              <Logo />
            </span>
            <div>
              <p className='font-semibold tracking-wide'>AUTO INVOICE</p>
              <p className='font-mono text-[10px] tracking-[0.24em] text-emerald-200/65'>
                CONTROL PLANE
              </p>
            </div>
          </div>
          <div className='max-w-2xl py-16 lg:py-0'>
            <p className='font-mono text-xs tracking-[0.25em] text-emerald-300'>
              {t('auth.eyebrow')}
            </p>
            <h1 className='mt-5 max-w-xl text-4xl leading-[1.08] font-semibold tracking-[-0.035em] sm:text-6xl'>
              每一笔金额，都能回到它的合同与证据。
            </h1>
            <p className='mt-6 max-w-xl text-base leading-7 text-white/60'>
              {t('auth.description')}
            </p>
            <div className='mt-10 grid gap-3 sm:grid-cols-3'>
              {[
                ['用量证据', Network],
                ['审批留痕', BadgeCheck],
                ['正式冻结', ReceiptText],
              ].map(([label, Icon]) => (
                <div
                  key={String(label)}
                  className='flex items-center gap-3 border-t border-white/15 pt-4 text-sm text-white/70'
                >
                  <Icon className='size-4 text-emerald-300' />
                  {String(label)}
                </div>
              ))}
            </div>
          </div>
          <p className='font-mono text-[10px] tracking-[0.2em] text-white/35'>
            SESSION COOKIE · CSRF · TOTP · RBAC
          </p>
        </section>

        <section className='flex min-h-svh items-start justify-center bg-white px-5 py-6 text-[#101b18] sm:px-8 sm:py-10 lg:min-h-0 lg:items-center lg:px-12 lg:py-12 dark:bg-[#0d1715] dark:text-white'>
          <div className='w-full max-w-md min-w-0'>
            <div className='mb-6 flex items-center gap-3 lg:hidden'>
              <span className='grid size-10 shrink-0 place-items-center rounded-lg border border-emerald-700/20 bg-emerald-700/10 text-emerald-700 dark:border-emerald-300/25 dark:bg-emerald-300/10 dark:text-emerald-300'>
                <Logo />
              </span>
              <div className='min-w-0'>
                <p className='font-semibold tracking-wide'>AUTO INVOICE</p>
                <p className='font-mono text-[10px] tracking-[0.24em] text-emerald-700/65 dark:text-emerald-200/65'>
                  CONTROL PLANE
                </p>
              </div>
            </div>
            <div className='mb-6 flex items-start justify-between gap-4 sm:mb-8 sm:gap-6'>
              <div>
                <p className='font-mono text-xs tracking-[0.22em] text-emerald-700 dark:text-emerald-300'>
                  SECURE ACCESS
                </p>
                <h2 className='mt-2 text-2xl font-semibold tracking-tight sm:mt-3 sm:text-3xl'>
                  {passwordChangeRequired
                    ? '更换临时密码'
                    : mfaRequired
                      ? '完成双重验证'
                      : t('auth.title')}
                </h2>
              </div>
              <span className='grid size-10 shrink-0 place-items-center rounded-full border bg-muted/40'>
                <LockKeyhole className='size-4' />
              </span>
            </div>
            {problem && (
              <Alert variant='destructive' className='mb-6'>
                <KeyRound />
                <AlertTitle>验证未通过</AlertTitle>
                <AlertDescription>{problem}</AlertDescription>
              </Alert>
            )}
            {passwordChangeRequired ? (
              <form
                className='space-y-4 sm:space-y-5'
                onSubmit={passwordChange.handleSubmit((value) =>
                  passwordChangeMutation.mutate(value)
                )}
              >
                <div className='rounded-lg border border-amber-500/30 bg-amber-500/5 p-4 text-sm leading-6'>
                  <p className='font-medium text-amber-800 dark:text-amber-300'>
                    首次登录必须设置正式密码
                  </p>
                  <p className='mt-1 text-muted-foreground'>
                    在完成改密前，服务端仅允许查看会话、退出和提交本表单；其他业务接口均会被拒绝。
                  </p>
                </div>
                <Field
                  label='当前临时密码'
                  error={
                    passwordChange.formState.errors.current_password?.message
                  }
                >
                  <PasswordInput
                    autoComplete='current-password'
                    {...passwordChange.register('current_password')}
                  />
                </Field>
                <Field
                  label='新密码'
                  error={passwordChange.formState.errors.new_password?.message}
                >
                  <PasswordInput
                    autoComplete='new-password'
                    {...passwordChange.register('new_password')}
                  />
                </Field>
                <Field
                  label='确认新密码'
                  error={
                    passwordChange.formState.errors.confirm_password?.message
                  }
                >
                  <PasswordInput
                    autoComplete='new-password'
                    {...passwordChange.register('confirm_password')}
                  />
                </Field>
                <p className='text-xs leading-5 text-muted-foreground'>
                  使用 12–200 位字符；12–19 位至少包含三类字符，20
                  位以上长密码至少包含两类字符，且不得包含用户名。
                </p>
                <Button
                  className='h-11 w-full bg-[#0c6b57] text-white hover:bg-[#095947]'
                  disabled={passwordChangeMutation.isPending}
                >
                  {passwordChangeMutation.isPending
                    ? '正在更新密码…'
                    : '保存新密码并继续'}
                  <ArrowRight />
                </Button>
                <Button
                  type='button'
                  variant='ghost'
                  className='w-full'
                  disabled={signOutMutation.isPending}
                  onClick={() => signOutMutation.mutate()}
                >
                  退出登录
                </Button>
              </form>
            ) : !mfaRequired ? (
              <form
                className='space-y-4 sm:space-y-5'
                onSubmit={credentials.handleSubmit((value) =>
                  credentialsMutation.mutate(value)
                )}
              >
                <Field
                  label={t('auth.tenant')}
                  error={credentials.formState.errors.tenant_code?.message}
                >
                  <Input
                    autoComplete='organization'
                    {...credentials.register('tenant_code')}
                  />
                </Field>
                <Field
                  label={t('auth.username')}
                  error={credentials.formState.errors.username?.message}
                >
                  <Input
                    autoComplete='username'
                    {...credentials.register('username')}
                  />
                </Field>
                <Field
                  label={t('auth.password')}
                  error={credentials.formState.errors.password?.message}
                >
                  <PasswordInput
                    autoComplete='current-password'
                    {...credentials.register('password')}
                  />
                </Field>
                <Button
                  className='h-11 w-full bg-[#0c6b57] text-white hover:bg-[#095947]'
                  disabled={credentialsMutation.isPending}
                >
                  {credentialsMutation.isPending
                    ? t('auth.signingIn')
                    : t('auth.signIn')}
                  <ArrowRight />
                </Button>
              </form>
            ) : (
              <form
                className='space-y-4 sm:space-y-5'
                onSubmit={mfa.handleSubmit((value) =>
                  mfaMutation.mutate(value)
                )}
              >
                <p className='text-sm leading-6 text-muted-foreground'>
                  打开身份验证器，输入当前显示的六位验证码。验证码不会写入日志或浏览器存储。
                </p>
                <Field
                  label={t('auth.mfa')}
                  error={mfa.formState.errors.code?.message}
                >
                  <Input
                    inputMode='numeric'
                    autoComplete='one-time-code'
                    maxLength={6}
                    className='h-14 font-mono text-2xl tracking-[0.45em]'
                    {...mfa.register('code')}
                  />
                </Field>
                <Button
                  className='h-11 w-full bg-[#0c6b57] text-white hover:bg-[#095947]'
                  disabled={mfaMutation.isPending}
                >
                  {mfaMutation.isPending ? '正在验证…' : t('auth.verify')}
                  <ArrowRight />
                </Button>
                <Button
                  type='button'
                  variant='ghost'
                  className='w-full'
                  onClick={() => {
                    setMfaRequired(false)
                    setProblem(undefined)
                  }}
                >
                  返回账号登录
                </Button>
              </form>
            )}
            <p className='mt-6 border-t pt-4 text-xs leading-5 text-muted-foreground sm:mt-8 sm:pt-5'>
              登录即表示本次操作将受租户隔离、权限控制和审计策略约束。
            </p>
          </div>
        </section>
      </div>
    </main>
  )
}

function Field({
  label,
  error,
  children,
}: {
  label: string
  error?: string
  children: React.ReactNode
}) {
  return (
    <div className='space-y-2'>
      <Label>{label}</Label>
      {children}
      {error && <p className='text-xs text-destructive'>{error}</p>}
    </div>
  )
}
