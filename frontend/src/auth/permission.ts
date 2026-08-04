import type { PermissionCode, Session } from '@/api/types'

export function can(session: Session | undefined, permission?: PermissionCode) {
  return !permission || Boolean(session?.permissions.includes(permission))
}
