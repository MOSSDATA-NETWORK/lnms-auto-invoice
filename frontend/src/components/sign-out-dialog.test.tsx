import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render } from 'vitest-browser-react'
import { userEvent } from 'vitest/browser'
import { SignOutDialog } from './sign-out-dialog'

const mocks = vi.hoisted(() => ({ navigate: vi.fn(), signOut: vi.fn() }))
const MOCK_HREF = '/customers?q=acme'

vi.mock('@/api/auth', () => ({ signOut: mocks.signOut }))
vi.mock('@tanstack/react-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@tanstack/react-router')>()
  return {
    ...actual,
    useNavigate: () => mocks.navigate,
    useLocation: () => ({ href: MOCK_HREF }),
  }
})

describe('SignOutDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.signOut.mockResolvedValue(undefined)
    mocks.navigate.mockResolvedValue(undefined)
  })

  it('revokes the server session, clears cached facts and preserves the redirect', async () => {
    const client = new QueryClient()
    const clear = vi.spyOn(client, 'clear')
    const { getByRole } = await render(
      <QueryClientProvider client={client}>
        <SignOutDialog open onOpenChange={vi.fn()} />
      </QueryClientProvider>
    )

    await userEvent.click(getByRole('button', { name: '退出登录' }))
    await vi.waitFor(() => expect(mocks.signOut).toHaveBeenCalledOnce())
    expect(clear).toHaveBeenCalledOnce()
    expect(mocks.navigate).toHaveBeenCalledWith({
      to: '/sign-in',
      search: { redirect: MOCK_HREF },
      replace: true,
    })
  })

  it('keeps the session untouched when cancel is clicked', async () => {
    const client = new QueryClient()
    const { getByRole } = await render(
      <QueryClientProvider client={client}>
        <SignOutDialog open onOpenChange={vi.fn()} />
      </QueryClientProvider>
    )

    await userEvent.click(getByRole('button', { name: '取消' }))
    expect(mocks.signOut).not.toHaveBeenCalled()
    expect(mocks.navigate).not.toHaveBeenCalled()
  })
})
