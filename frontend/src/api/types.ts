import type * as GeneratedModel from './generated/model'

export type Complete<T> = T extends readonly (infer Item)[]
  ? Complete<Item>[]
  : T extends object
    ? { [Key in keyof T]-?: Complete<Exclude<T[Key], undefined>> }
    : Exclude<T, undefined>

export type MinorUnits = string

export type PermissionCode =
  | 'customer.read'
  | 'customer.write'
  | 'contract.write'
  | 'pricing.publish'
  | 'usage.sync'
  | 'preview.generate'
  | 'preview.adjust'
  | 'preview.approve.business'
  | 'preview.approve.finance'
  | 'invoice.finalize'
  | 'invoice.send'
  | 'invoice.void'
  | 'payment.record'
  | 'template.publish'
  | 'audit.read'
  | 'system.admin'

export type Session = Omit<
  Complete<GeneratedModel.SessionResponse>,
  'permissions' | 'must_change_password'
> & {
  permissions: PermissionCode[]
  must_change_password: boolean
}

export type ProblemDetails = {
  type?: string
  title?: string
  status?: number
  detail?: string
  instance?: string
  code?: string
  request_id?: string
  errors?: Array<{ field: string; message: string }>
}
