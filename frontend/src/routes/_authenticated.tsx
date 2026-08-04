import { createFileRoute, redirect } from '@tanstack/react-router'
import { sessionQuery } from '@/api/auth'
import { AuthenticatedLayout } from '@/components/layout/authenticated-layout'

export const Route = createFileRoute('/_authenticated')({
  beforeLoad: async ({ context, location }) => {
    try {
      const session = await context.queryClient.ensureQueryData(sessionQuery)
      if (session.must_change_password) {
        throw redirect({
          to: '/sign-in',
          search: { redirect: location.href },
        })
      }
      return { session }
    } catch {
      throw redirect({ to: '/sign-in', search: { redirect: location.href } })
    }
  },
  component: AuthenticatedLayout,
})
