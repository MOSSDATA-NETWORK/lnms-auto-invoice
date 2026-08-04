import { redirect } from '@tanstack/react-router'
import type { PermissionCode, Session } from '@/api/types'

export function requireAnyPermission(
  session: Session,
  permissions: PermissionCode[]
) {
  if (
    !permissions.some((permission) => session.permissions.includes(permission))
  )
    throw redirect({ to: '/forbidden' })
}
