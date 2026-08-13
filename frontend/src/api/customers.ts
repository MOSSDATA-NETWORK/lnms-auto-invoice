import { queryOptions } from '@tanstack/react-query'
import { list4 as fetchCustomers } from './generated/customer-controller/customer-controller'
import type {
  CustomerCreateRequest,
  CustomerResponse,
  CustomerUpdateRequest,
  CursorPageCustomerResponse,
} from './generated/model'
import { api, idempotencyKey } from './http'
import type { Complete } from './types'

export type Customer = Complete<CustomerResponse>
type CustomerPage = Complete<CursorPageCustomerResponse>

export const customersQuery = (search = '') =>
  queryOptions({
    queryKey: ['customers', search],
    queryFn: async ({ signal }) =>
      (await fetchCustomers(
        { q: search || undefined, limit: 100 },
        signal
      )) as CustomerPage,
  })

export const customerDetailQuery = (id: string) =>
  queryOptions({
    queryKey: ['customers', 'detail', id],
    queryFn: async ({ signal }) =>
      (await api.get<Customer>(`/customers/${id}`, { signal })).data,
  })

export async function createCustomer(
  input: CustomerCreateRequest
): Promise<Customer> {
  return (
    await api.post<Customer>('/customers', input, {
      headers: { 'Idempotency-Key': idempotencyKey('customer') },
    })
  ).data
}

export async function updateCustomer(
  customer: Customer,
  input: CustomerUpdateRequest
): Promise<Customer> {
  return (
    await api.patch<Customer>(`/customers/${customer.id}`, input, {
      headers: { 'If-Match': `"${customer.version}"` },
    })
  ).data
}

export async function archiveCustomer(
  customer: Customer,
  reason: string
): Promise<void> {
  await api.post(
    `/customers/${customer.id}/archive`,
    { reason },
    {
      headers: {
        'If-Match': `"${customer.version}"`,
        'Idempotency-Key': idempotencyKey('customer-archive'),
      },
    }
  )
}
