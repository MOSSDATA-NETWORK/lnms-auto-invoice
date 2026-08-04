import { createFileRoute } from '@tanstack/react-router'
import { requireAnyPermission } from '@/auth/route-guard'
import { ProfilesPage } from '@/features/profiles/profiles-page'

export const Route = createFileRoute('/_authenticated/profiles')({
  beforeLoad: ({ context }) =>
    requireAnyPermission(context.session, ['preview.generate']),
  component: ProfilesPage,
})
