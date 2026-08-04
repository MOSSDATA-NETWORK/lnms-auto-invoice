import { createFileRoute } from '@tanstack/react-router'
import { requireAnyPermission } from '@/auth/route-guard'
import { LibrenmsPage } from '@/features/librenms/librenms-page'

export const Route = createFileRoute('/_authenticated/librenms')({
  beforeLoad: ({ context }) =>
    requireAnyPermission(context.session, ['usage.sync']),
  component: LibrenmsPage,
})
