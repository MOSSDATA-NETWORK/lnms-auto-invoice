import { createFileRoute } from '@tanstack/react-router'
import { requireAnyPermission } from '@/auth/route-guard'
import { ContractsPage } from '@/features/contracts/contracts-page'

export const Route = createFileRoute('/_authenticated/contracts')({
  beforeLoad: ({ context }) =>
    requireAnyPermission(context.session, [
      'contract.write',
      'pricing.publish',
    ]),
  component: ContractsPage,
})
