import { createFileRoute } from '@tanstack/react-router'
import { requireAnyPermission } from '@/auth/route-guard'
import { JobsPage } from '@/features/jobs/jobs-page'

export const Route = createFileRoute('/_authenticated/jobs')({
  beforeLoad: ({ context }) =>
    requireAnyPermission(context.session, ['audit.read', 'system.admin']),
  component: JobsPage,
})
