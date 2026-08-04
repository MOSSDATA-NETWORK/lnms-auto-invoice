import { queryOptions } from '@tanstack/react-query'
import {
  session as fetchSession,
  signIn as requestSignIn,
  signOut as requestSignOut,
  verifyMfa as requestVerifyMfa,
} from './generated/auth-controller/auth-controller'
import type { SignInRequest } from './generated/model'
import { api, ensureCsrf, idempotencyKey } from './http'
import type { Session } from './types'

export const sessionQuery = queryOptions({
  queryKey: ['auth', 'session'],
  queryFn: async ({ signal }) => (await fetchSession(signal)) as Session,
  retry: false,
  staleTime: 30_000,
})

export async function signIn(
  input: SignInRequest
): Promise<{ mfa_required: boolean; session: Session | null }> {
  await ensureCsrf()
  return (await requestSignIn(input)) as {
    mfa_required: boolean
    session: Session | null
  }
}

export async function verifyMfa(code: string): Promise<Session> {
  await ensureCsrf()
  return (await requestVerifyMfa({ code })) as Session
}

export async function changePassword(input: {
  current_password: string
  new_password: string
}): Promise<Session> {
  await ensureCsrf()
  return (
    await api.post(
      '/auth/change-password',
      { ...input, reason: '用户首次登录后更换临时密码' },
      {
        headers: {
          'Idempotency-Key': idempotencyKey('change-password'),
        },
      }
    )
  ).data as Session
}

export async function signOut(): Promise<void> {
  await ensureCsrf()
  await requestSignOut()
}
