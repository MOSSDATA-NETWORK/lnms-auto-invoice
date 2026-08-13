import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, type RenderResult } from 'vitest-browser-react'
import { userEvent } from 'vitest/browser'
import { sessionQuery } from '@/api/auth'
import type { PermissionCode, Session } from '@/api/types'
import { SearchProvider } from '@/context/search-provider'

const COMMAND_MENU_PLACEHOLDER = '搜索命令或页面…'
const mocks = vi.hoisted(() => ({ navigate: vi.fn(), setTheme: vi.fn() }))

vi.mock('@tanstack/react-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@tanstack/react-router')>()
  return { ...actual, useNavigate: () => mocks.navigate }
})
vi.mock('@/context/theme-provider', () => ({
  useTheme: () => ({ setTheme: mocks.setTheme }),
}))

const allPermissions: PermissionCode[] = [
  'customer.read',
  'customer.write',
  'contract.write',
  'pricing.publish',
  'usage.sync',
  'preview.generate',
  'preview.adjust',
  'preview.approve.business',
  'preview.approve.finance',
  'invoice.finalize',
  'invoice.send',
  'invoice.void',
  'payment.record',
  'template.publish',
  'audit.read',
  'system.admin',
]

function session(permissions = allPermissions): Session {
  return {
    user_id: 'user-1',
    tenant_id: 'tenant-1',
    tenant_code: 'DEMO',
    username: 'finance',
    display_name: '财务管理员',
    permissions,
    must_change_password: false,
    mfa_enabled: false,
  }
}

async function renderWithSearchProvider(activeSession = session()) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  client.setQueryData(sessionQuery.queryKey, activeSession)
  return await render(
    <QueryClientProvider client={client}>
      <SearchProvider>{null}</SearchProvider>
    </QueryClientProvider>
  )
}

type ShortcutModifier = 'Control' | 'Meta'
async function openCommandPalette(
  screen: RenderResult,
  modifier: ShortcutModifier = 'Control'
) {
  await vi.waitFor(
    async () => {
      if (
        document.querySelector(
          `[placeholder="${COMMAND_MENU_PLACEHOLDER}"]`
        ) === null
      )
        await userEvent.keyboard(`{${modifier}>}k{/${modifier}}`)
      await expect
        .element(screen.getByPlaceholder(COMMAND_MENU_PLACEHOLDER))
        .toBeInTheDocument()
    },
    { interval: 50, timeout: 5000 }
  )
}

describe('SearchProvider and CommandMenu', () => {
  beforeEach(() => vi.clearAllMocks())

  it('renders localized navigation and theme commands', async () => {
    const screen = await renderWithSearchProvider()
    await openCommandPalette(screen)
    await expect.element(screen.getByText('外观')).toBeInTheDocument()
    await expect.element(screen.getByText('浅色')).toBeInTheDocument()
    await expect.element(screen.getByText('深色')).toBeInTheDocument()
    await expect.element(screen.getByText('跟随系统')).toBeInTheDocument()
    await expect.element(screen.getByText('总览')).toBeInTheDocument()
  })

  it('does not show the dialog content while closed', async () => {
    const screen = await renderWithSearchProvider()
    await expect
      .element(screen.getByPlaceholder(COMMAND_MENU_PLACEHOLDER))
      .not.toBeInTheDocument()
  })

  it.each([
    ['Ctrl', 'Control'],
    ['Cmd', 'Meta'],
  ] as const)('opens the menu with %s + K', async (_label, modifier) => {
    const screen = await renderWithSearchProvider()
    await openCommandPalette(screen, modifier)
    await expect
      .element(screen.getByPlaceholder(COMMAND_MENU_PLACEHOLDER))
      .toBeInTheDocument()
  })

  it('navigates to an authorized route and closes the palette', async () => {
    const screen = await renderWithSearchProvider()
    await openCommandPalette(screen)
    await userEvent.click(screen.getByText('客户管理'))
    expect(mocks.navigate).toHaveBeenCalledWith({ to: '/customers' })
    await expect
      .element(screen.getByPlaceholder(COMMAND_MENU_PLACEHOLDER))
      .not.toBeInTheDocument()
  })

  it('does not expose commands outside the current session permissions', async () => {
    const screen = await renderWithSearchProvider(session(['customer.read']))
    await openCommandPalette(screen)
    await expect.element(screen.getByText('客户管理')).toBeInTheDocument()
    await expect.element(screen.getByText('系统管理')).not.toBeInTheDocument()
  })

  it('applies theme and closes the palette', async () => {
    const screen = await renderWithSearchProvider()
    await openCommandPalette(screen)
    await userEvent.click(screen.getByText('深色'))
    expect(mocks.setTheme).toHaveBeenCalledWith('dark')
    await expect
      .element(screen.getByPlaceholder(COMMAND_MENU_PLACEHOLDER))
      .not.toBeInTheDocument()
  })

  it('shows the localized empty state', async () => {
    const screen = await renderWithSearchProvider()
    await openCommandPalette(screen)
    await userEvent.fill(
      screen.getByPlaceholder(COMMAND_MENU_PLACEHOLDER),
      'zzzz-no-match-xxxx'
    )
    await expect
      .element(screen.getByText('没有匹配的页面。'))
      .toBeInTheDocument()
  })
})
