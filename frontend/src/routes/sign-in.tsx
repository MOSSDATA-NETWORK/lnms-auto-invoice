import { createFileRoute } from '@tanstack/react-router'
import { SignInPage } from '@/features/auth/sign-in-page'

type SignInSearch = { redirect?: string }

export const Route = createFileRoute('/sign-in')({
  validateSearch: (search: Record<string, unknown>): SignInSearch => ({
    redirect: typeof search.redirect === 'string' ? search.redirect : undefined,
  }),
  component: SignInPage,
})
