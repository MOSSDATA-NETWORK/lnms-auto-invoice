import { createFileRoute } from '@tanstack/react-router'
import { requireAnyPermission } from '@/auth/route-guard'
import { TemplatesPage } from '@/features/templates/templates-page'

export const Route = createFileRoute('/_authenticated/templates')({
  beforeLoad: ({ context }) =>
    requireAnyPermission(context.session, ['template.publish']),
  component: TemplatesPage,
})
