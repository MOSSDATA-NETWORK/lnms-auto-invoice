import { createFileRoute } from '@tanstack/react-router'
import { requireAnyPermission } from '@/auth/route-guard'
import { LetterheadPage } from '@/features/letterhead/letterhead-page'

export const Route = createFileRoute('/_authenticated/letterhead')({
  beforeLoad: ({ context }) =>
    requireAnyPermission(context.session, ['payment.record', 'system.admin']),
  component: LetterheadPage,
})
