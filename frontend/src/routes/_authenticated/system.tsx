import { createFileRoute } from '@tanstack/react-router'
import { requireAnyPermission } from '@/auth/route-guard'
import { SystemPage } from '@/features/system/system-page'

export const Route = createFileRoute('/_authenticated/system')({
  beforeLoad: ({ context }) =>
    requireAnyPermission(context.session, ['system.admin']),
  component: SystemPage,
})
