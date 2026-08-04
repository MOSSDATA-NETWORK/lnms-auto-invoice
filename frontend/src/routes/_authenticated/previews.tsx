import { createFileRoute } from '@tanstack/react-router'
import { requireAnyPermission } from '@/auth/route-guard'
import { PreviewsPage } from '@/features/previews/previews-page'

export const Route = createFileRoute('/_authenticated/previews')({
  beforeLoad: ({ context }) =>
    requireAnyPermission(context.session, [
      'preview.generate',
      'preview.adjust',
      'preview.approve.business',
      'preview.approve.finance',
    ]),
  component: PreviewsPage,
})
