import { queryOptions } from '@tanstack/react-query'
import Decimal from 'decimal.js'
import { detail as fetchInvoiceDetail } from './generated/formal-invoice-controller/formal-invoice-controller'
import { list8 as fetchImports } from './generated/import-controller/import-controller'
import {
  invoices as fetchInvoices,
  preview1 as fetchPreview,
  previews as fetchPreviews,
} from './generated/invoice-lifecycle-controller/invoice-lifecycle-controller'
import { list3 as fetchProfiles } from './generated/invoice-profile-controller/invoice-profile-controller'
import { list7 as fetchJobs } from './generated/job-controller/job-controller'
import {
  discovered as fetchDiscoveredBills,
  list1 as fetchLibrenmsInstances,
  mappings as fetchLibrenmsMappings,
} from './generated/librenms-controller/librenms-controller'
import {
  companies as fetchCompanies,
  contractItems as fetchContractItems,
  contracts as fetchContracts,
  products as fetchProducts,
  serviceResources as fetchServiceResources,
  services as fetchServices,
} from './generated/master-data-controller/master-data-controller'
import type * as GeneratedModel from './generated/model'
import {
  listEndpoints as fetchWebhookEndpoints,
  listLogs as fetchNotificationLogs,
} from './generated/notification-controller/notification-controller'
import { status as fetchOperationalStatus } from './generated/operations-controller/operations-controller'
import {
  get2 as fetchPaymentDetail,
  list as fetchPayments,
} from './generated/payment-controller/payment-controller'
import {
  rule as fetchPricingRule,
  rules as fetchPricingRules,
} from './generated/pricing-controller/pricing-controller'
import { receivables as fetchReceivables } from './generated/receivables-report-controller/receivables-report-controller'
import {
  permissions as fetchSystemPermissions,
  roles as fetchSystemRoles,
  users as fetchSystemUsers,
} from './generated/system-admin-controller/system-admin-controller'
import {
  get4 as fetchTemplateDetail,
  list2 as fetchTemplates,
} from './generated/template-controller/template-controller'
import { snapshots as fetchUsageSnapshots } from './generated/usage-evidence-controller/usage-evidence-controller'
import { api, idempotencyKey } from './http'
import type { Complete, MinorUnits } from './types'

export type PreviewSummary = Complete<GeneratedModel.PreviewSummary>
type PreviewDetail = Complete<GeneratedModel.PreviewDetail>
export type InvoiceSummary = Complete<GeneratedModel.InvoiceSummary>
type InvoiceDetail = Complete<GeneratedModel.InvoiceDetail>
export type InvoiceProfile = Complete<GeneratedModel.ProfileResponse>
export type Job = Complete<GeneratedModel.JobResponse>
export type Payment = Complete<GeneratedModel.PaymentSummary>
type PaymentDetail = Complete<GeneratedModel.PaymentDetail>
export type LibrenmsInstance = Complete<GeneratedModel.InstanceResponse>
type DiscoveredBill = Complete<GeneratedModel.DiscoveredBillResponse>
export type LibrenmsMapping = Complete<GeneratedModel.MappingResponse>
type UsageSnapshot = Complete<GeneratedModel.UsageSnapshotResponse>
export type Service = Complete<GeneratedModel.ServiceResponse>
type Product = Complete<GeneratedModel.ProductResponse>
type ServiceResource = Complete<GeneratedModel.ServiceResourceResponse>
export type Company = Complete<GeneratedModel.CompanyResponse>
export type Contract = Complete<GeneratedModel.ContractResponse>
export type ContractItem = Complete<GeneratedModel.ContractItemResponse>
type PricingRule = Complete<GeneratedModel.PricingRuleResponse>
export type PricingVersion = Complete<GeneratedModel.PricingVersionResponse>
type PricingRuleDetail = Complete<GeneratedModel.PricingRuleDetail>
export type InvoiceTemplate = Complete<GeneratedModel.TemplateResponse>
type TemplateVersion = Complete<GeneratedModel.TemplateVersionResponse>
type TemplateDetail = Complete<GeneratedModel.TemplateDetail>
type OperationalSettings = Complete<GeneratedModel.OperationalSettings>
type OperationalStatus = Complete<GeneratedModel.OperationalStatus>
export type ReceivablesReport = Complete<GeneratedModel.ReceivablesReport>
type ImportJob = Complete<GeneratedModel.ImportResponse>
export type SystemRole = Complete<GeneratedModel.RoleResponse>
export type SystemUser = Omit<
  Complete<GeneratedModel.UserResponse>,
  'must_change_password' | 'temporary_password_expires_at'
> & {
  must_change_password: boolean
  temporary_password_expires_at: string | null
}
type SystemPermission = Complete<GeneratedModel.PermissionResponse>
type WebhookEndpoint = Complete<GeneratedModel.WebhookEndpointResponse>
type NotificationLog = Complete<GeneratedModel.NotificationLogResponse>

export const previewsQuery = (status?: string) =>
  queryOptions({
    queryKey: ['invoice-previews', status],
    queryFn: async ({ signal }) =>
      (await fetchPreviews({ status }, signal)) as PreviewSummary[],
    refetchInterval: 15_000,
  })

export const previewDetailQuery = (id: string) =>
  queryOptions({
    queryKey: ['invoice-preview', id],
    queryFn: async ({ signal }) =>
      (await fetchPreview(id, signal)) as PreviewDetail,
  })

export const invoicesQuery = queryOptions({
  queryKey: ['invoices'],
  queryFn: async ({ signal }) =>
    (await fetchInvoices(undefined, signal)) as InvoiceSummary[],
  refetchInterval: 15_000,
})

export const invoiceDetailQuery = (invoiceId?: string) =>
  queryOptions({
    queryKey: ['invoice', invoiceId],
    enabled: Boolean(invoiceId),
    queryFn: async ({ signal }) =>
      (await fetchInvoiceDetail(invoiceId!, signal)) as InvoiceDetail,
  })

export const profilesQuery = queryOptions({
  queryKey: ['invoice-profiles'],
  queryFn: async ({ signal }) =>
    (await fetchProfiles(undefined, signal)) as InvoiceProfile[],
})

export const jobsQuery = (status?: string) =>
  queryOptions({
    queryKey: ['jobs', status],
    queryFn: async ({ signal }) =>
      (await fetchJobs({ status }, signal)) as Job[],
    refetchInterval: 5_000,
  })

export const paymentsQuery = queryOptions({
  queryKey: ['payments'],
  queryFn: async ({ signal }) =>
    (await fetchPayments(undefined, signal)) as Payment[],
})

export const paymentDetailQuery = (paymentId?: string) =>
  queryOptions({
    queryKey: ['payment', paymentId],
    enabled: Boolean(paymentId),
    queryFn: async ({ signal }) =>
      (await fetchPaymentDetail(paymentId!, signal)) as PaymentDetail,
  })

export const librenmsInstancesQuery = queryOptions({
  queryKey: ['librenms-instances'],
  queryFn: async ({ signal }) =>
    (await fetchLibrenmsInstances(signal)) as LibrenmsInstance[],
})

export const discoveredBillsQuery = (instanceId?: string) =>
  queryOptions({
    queryKey: ['librenms-discovered-bills', instanceId],
    enabled: Boolean(instanceId),
    queryFn: async ({ signal }) =>
      (await fetchDiscoveredBills(instanceId!, signal)) as DiscoveredBill[],
  })

export const librenmsMappingsQuery = (instanceId?: string) =>
  queryOptions({
    queryKey: ['librenms-mappings', instanceId],
    enabled: Boolean(instanceId),
    queryFn: async ({ signal }) =>
      (await fetchLibrenmsMappings(instanceId!, signal)) as LibrenmsMapping[],
  })

export const usageSnapshotsQuery = queryOptions({
  queryKey: ['usage-snapshots'],
  queryFn: async ({ signal }) =>
    (await fetchUsageSnapshots(undefined, signal)) as UsageSnapshot[],
})

export const servicesQuery = queryOptions({
  queryKey: ['services'],
  queryFn: async ({ signal }) =>
    (await fetchServices({ limit: 200 }, signal)) as Service[],
})

export const productsQuery = queryOptions({
  queryKey: ['products'],
  queryFn: async ({ signal }) =>
    (await fetchProducts(undefined, signal)) as Product[],
})

export const serviceResourcesQuery = (serviceId?: string) =>
  queryOptions({
    queryKey: ['service-resources', serviceId],
    enabled: Boolean(serviceId),
    queryFn: async ({ signal }) =>
      (await fetchServiceResources(serviceId!, signal)) as ServiceResource[],
  })

export const companiesQuery = queryOptions({
  queryKey: ['companies'],
  queryFn: async ({ signal }) =>
    (await fetchCompanies({ limit: 200 }, signal)) as Company[],
})

export const contractsQuery = queryOptions({
  queryKey: ['contracts'],
  queryFn: async ({ signal }) =>
    (await fetchContracts({ limit: 200 }, signal)) as Contract[],
})

export const contractItemsQuery = (contractId?: string) =>
  queryOptions({
    queryKey: ['contract-items', contractId],
    enabled: Boolean(contractId),
    queryFn: async ({ signal }) =>
      (await fetchContractItems(contractId!, signal)) as ContractItem[],
  })

export const pricingRulesQuery = queryOptions({
  queryKey: ['pricing-rules'],
  queryFn: async ({ signal }) =>
    (await fetchPricingRules(undefined, signal)) as PricingRule[],
})

export const pricingRuleDetailQuery = (ruleId?: string) =>
  queryOptions({
    queryKey: ['pricing-rule', ruleId],
    enabled: Boolean(ruleId),
    queryFn: async ({ signal }) =>
      (await fetchPricingRule(ruleId!, signal)) as PricingRuleDetail,
  })

export const templatesQuery = queryOptions({
  queryKey: ['invoice-templates'],
  queryFn: async ({ signal }) =>
    (await fetchTemplates(signal)) as InvoiceTemplate[],
})

export const templateDetailQuery = (templateId?: string) =>
  queryOptions({
    queryKey: ['invoice-template', templateId],
    enabled: Boolean(templateId),
    queryFn: async ({ signal }) =>
      (await fetchTemplateDetail(templateId!, signal)) as TemplateDetail,
  })

export const operationalStatusQuery = queryOptions({
  queryKey: ['operational-status'],
  queryFn: async ({ signal }) =>
    (await fetchOperationalStatus(signal)) as OperationalStatus,
  refetchInterval: 10_000,
})

export const receivablesReportQuery = (asOf?: string) =>
  queryOptions({
    queryKey: ['receivables-report', asOf],
    queryFn: async ({ signal }) =>
      (await fetchReceivables({ as_of: asOf }, signal)) as ReceivablesReport,
  })

export const importsQuery = queryOptions({
  queryKey: ['imports'],
  queryFn: async ({ signal }) =>
    (await fetchImports(undefined, signal)) as ImportJob[],
  refetchInterval: 5_000,
})

export const systemUsersQuery = queryOptions({
  queryKey: ['system-users'],
  queryFn: async ({ signal }) =>
    (await fetchSystemUsers(signal)) as SystemUser[],
})

export const systemRolesQuery = queryOptions({
  queryKey: ['system-roles'],
  queryFn: async ({ signal }) =>
    (await fetchSystemRoles(signal)) as SystemRole[],
})

export const systemPermissionsQuery = queryOptions({
  queryKey: ['system-permissions'],
  queryFn: async ({ signal }) =>
    (await fetchSystemPermissions(signal)) as SystemPermission[],
})

export const webhookEndpointsQuery = queryOptions({
  queryKey: ['webhook-endpoints'],
  queryFn: async ({ signal }) =>
    (await fetchWebhookEndpoints(signal)) as WebhookEndpoint[],
})

export const notificationLogsQuery = queryOptions({
  queryKey: ['notification-logs'],
  queryFn: async ({ signal }) =>
    (await fetchNotificationLogs(undefined, signal)) as NotificationLog[],
  refetchInterval: 10_000,
})

export async function previewCommand(
  preview: PreviewSummary,
  action: 'submit-review' | 'approve-business' | 'approve-finance' | 'reject',
  comment: string
) {
  return (
    await api.post(
      `/invoice-previews/${preview.id}/${action}`,
      { expected_version: preview.version, comment },
      {
        headers: {
          'If-Match': `"${preview.version}"`,
          'Idempotency-Key': idempotencyKey(`preview-${action}`),
        },
      }
    )
  ).data
}

export async function finalizePreview(
  preview: PreviewSummary,
  confirmationNote: string
) {
  return (
    await api.post(
      `/invoice-previews/${preview.id}/finalize`,
      {
        expected_version: preview.version,
        confirmation_note: confirmationNote,
      },
      {
        headers: {
          'If-Match': `"${preview.version}"`,
          'Idempotency-Key': idempotencyKey('preview-finalize'),
        },
      }
    )
  ).data
}

export async function recalculatePreview(
  preview: PreviewSummary,
  reason: string
) {
  return (
    await api.post(
      `/invoice-previews/${preview.id}/recalculate`,
      { expected_version: preview.version, reason },
      {
        headers: {
          'If-Match': `"${preview.version}"`,
          'Idempotency-Key': idempotencyKey('preview-recalculate'),
        },
      }
    )
  ).data as { job_id: string }
}

export async function generatePreview(
  profileId: string,
  periodStart: string,
  periodEnd: string
) {
  return (
    await api.post(
      `/invoice-profiles/${profileId}/preview`,
      {
        period_start: periodStart,
        period_end: periodEnd,
        force_usage_sync: false,
      },
      { headers: { 'Idempotency-Key': idempotencyKey('preview-generate') } }
    )
  ).data as { job_id: string }
}

export async function recordPayment(input: {
  customer_id: string
  company_id?: string
  currency_code: string
  amount_minor: MinorUnits
  payment_method: string
  paid_at: string
  external_reference?: string
  reason: string
}) {
  return (
    await api.post(
      '/payments',
      { ...input, source_system: 'MANUAL' },
      { headers: { 'Idempotency-Key': idempotencyKey('payment') } }
    )
  ).data
}

export interface CreateCompanyInput {
  customer_id: string
  company_code: string
  company_name: string
  company_name_en?: string
  country_region?: string
  address?: string
  tax_number?: string
  invoice_title?: string
  phone?: string
  bank_name?: string
  bank_account?: string
  invoice_type?: string
  swift_code?: string
  br_number?: string
  bank_code?: string
  bank_address?: string
  default_currency: string
  default_tax_rate?: string
}

export async function createCompany(input: CreateCompanyInput) {
  return (
    await api.post(
      '/companies',
      { ...input, reason: '在客户页新增客户公司' },
      { headers: { 'Idempotency-Key': idempotencyKey('company') } }
    )
  ).data as Company
}

export interface CreateLibrenmsInstanceInput {
  instance_code: string
  instance_name: string
  base_url: string
  api_token: string
  timezone: string
}

export async function createLibrenmsInstance(
  input: CreateLibrenmsInstanceInput
) {
  return (
    await api.post(
      '/librenms/instances',
      {
        ...input,
        connect_timeout_ms: 5000,
        read_timeout_ms: 30000,
        max_concurrency: 4,
        reason: '在 LibreNMS 工作台新增数据源',
      },
      { headers: { 'Idempotency-Key': idempotencyKey('librenms-create') } }
    )
  ).data as LibrenmsInstance
}

export async function discoverBills(instance: LibrenmsInstance) {
  return (
    await api.post(
      `/librenms/instances/${instance.id}/discover-bills`,
      {},
      { headers: { 'Idempotency-Key': idempotencyKey('librenms-discover') } }
    )
  ).data as { job_id: string }
}

export async function verifyLibrenms(instance: LibrenmsInstance) {
  return (
    await api.post(
      `/librenms/instances/${instance.id}/verify`,
      {},
      { headers: { 'Idempotency-Key': idempotencyKey('librenms-verify') } }
    )
  ).data as { job_id: string }
}

export async function retryJob(job: Job, reason: string) {
  return (
    await api.post(
      `/jobs/${job.id}/retry`,
      { reason },
      { headers: { 'Idempotency-Key': idempotencyKey('job-retry') } }
    )
  ).data as Job
}

export async function uploadImportFile(file: File) {
  const form = new FormData()
  form.append('file', file)
  form.append('reason', '主数据导入文件上传')
  return (
    await api.post<{ id: string }>('/files', form, {
      headers: { 'Idempotency-Key': idempotencyKey('file-upload') },
    })
  ).data
}

export async function createImport(sourceFileId: string, importType: string) {
  return (
    await api.post(
      '/imports/master-data',
      {
        source_file_id: sourceFileId,
        import_type: importType,
        options: {},
        reason: '从系统管理页面创建导入',
      },
      { headers: { 'Idempotency-Key': idempotencyKey('master-data-import') } }
    )
  ).data as { import_id: string; job_id: string; status: string }
}

export async function confirmImport(importId: string) {
  return (
    await api.post(
      `/imports/${importId}/confirm`,
      { reason: '用户确认已通过校验的主数据导入' },
      { headers: { 'Idempotency-Key': idempotencyKey('import-confirm') } }
    )
  ).data
}

export async function createWebhookEndpoint(input: {
  endpoint_code: string
  endpoint_name: string
  target_url: string
  signing_secret: string
}) {
  return (
    await api.post(
      '/webhook-endpoints',
      {
        ...input,
        event_types: ['invoice.confirmed'],
        reason: '创建正式账单 Webhook',
      },
      { headers: { 'Idempotency-Key': idempotencyKey('webhook-endpoint') } }
    )
  ).data as WebhookEndpoint
}

export async function createSystemUser(input: {
  username: string
  email: string
  display_name: string
  temporary_password: string
  role_ids: string[]
}) {
  return (
    await api.post(
      '/system/users',
      { ...input, reason: '从系统管理页面创建租户用户' },
      { headers: { 'Idempotency-Key': idempotencyKey('system-user') } }
    )
  ).data as SystemUser
}

export async function updateSystemUserStatus(
  user: SystemUser,
  status: 'ACTIVE' | 'LOCKED' | 'DISABLED'
) {
  return (
    await api.post(
      `/system/users/${user.id}/status`,
      {
        expected_version: user.version,
        status,
        reason: `管理员将用户状态调整为 ${status}`,
      },
      {
        headers: {
          'If-Match': `"${user.version}"`,
          'Idempotency-Key': idempotencyKey('system-user-status'),
        },
      }
    )
  ).data as SystemUser
}

export async function updateSystemUserRoles(
  user: SystemUser,
  roleIds: string[]
) {
  return (
    await api.post(
      `/system/users/${user.id}/roles`,
      {
        expected_version: user.version,
        role_ids: roleIds,
        reason: '管理员更新用户角色',
      },
      {
        headers: {
          'If-Match': `"${user.version}"`,
          'Idempotency-Key': idempotencyKey('system-user-roles'),
        },
      }
    )
  ).data as SystemUser
}

export async function resetSystemUserPassword(
  user: SystemUser,
  temporaryPassword: string
) {
  return (
    await api.post(
      `/system/users/${user.id}/reset-password`,
      {
        expected_version: user.version,
        temporary_password: temporaryPassword,
        reason: '管理员重置用户临时密码',
      },
      {
        headers: {
          'If-Match': `"${user.version}"`,
          'Idempotency-Key': idempotencyKey('system-user-password-reset'),
        },
      }
    )
  ).data as SystemUser
}

export async function createSystemRole(input: {
  role_code: string
  role_name: string
  permissions: string[]
}) {
  return (
    await api.post(
      '/system/roles',
      { ...input, reason: '从系统管理页面创建角色' },
      { headers: { 'Idempotency-Key': idempotencyKey('system-role') } }
    )
  ).data as SystemRole
}

export async function updateSystemRole(
  role: SystemRole,
  roleName: string,
  permissions: string[]
) {
  return (
    await api.post(
      `/system/roles/${role.id}`,
      {
        expected_version: role.version,
        role_name: roleName,
        permissions,
        reason: '管理员更新角色权限',
      },
      {
        headers: {
          'If-Match': `"${role.version}"`,
          'Idempotency-Key': idempotencyKey('system-role-update'),
        },
      }
    )
  ).data as SystemRole
}

export async function createInvoiceTemplate(input: {
  template_code: string
  template_name: string
  default_language: string
}) {
  return (
    await api.post(
      '/invoice-templates',
      { ...input, reason: '从模板中心创建模板' },
      { headers: { 'Idempotency-Key': idempotencyKey('invoice-template') } }
    )
  ).data as InvoiceTemplate
}

export async function createPricingRule(input: {
  rule_code: string
  rule_name: string
  description?: string
}) {
  return (
    await api.post(
      '/pricing-rules',
      { ...input, reason: '从合同与价格工作台创建价格规则' },
      { headers: { 'Idempotency-Key': idempotencyKey('pricing-rule') } }
    )
  ).data as PricingRule
}

export async function createProduct(input: {
  product_code: string
  product_name: string
  service_type: string
  default_unit?: string
}) {
  return (
    await api.post(
      '/products',
      {
        ...input,
        default_unit: input.default_unit || null,
        attributes_schema: {},
        reason: '从业务资产工作台创建产品类型',
      },
      { headers: { 'Idempotency-Key': idempotencyKey('product') } }
    )
  ).data as Product
}

export async function createService(input: {
  service_no: string
  customer_id: string
  company_id: string
  product_id?: string
  service_name: string
  service_type: string
  region?: string
  datacenter?: string
  line_name?: string
  activated_on?: string
  deactivated_on?: string
  status: string
  notes?: string
}) {
  return (
    await api.post(
      '/services',
      {
        ...input,
        product_id: input.product_id || null,
        region: input.region || null,
        datacenter: input.datacenter || null,
        line_name: input.line_name || null,
        activated_on: input.activated_on || null,
        deactivated_on: input.deactivated_on || null,
        attributes: {},
        notes: input.notes || null,
        reason: '从业务资产工作台创建业务',
      },
      { headers: { 'Idempotency-Key': idempotencyKey('service') } }
    )
  ).data as Service
}

export async function createServiceResource(
  serviceId: string,
  input: {
    resource_type: string
    resource_ref: string
    display_name?: string
    effective_from?: string
    effective_to?: string
  }
) {
  return (
    await api.post(
      `/services/${serviceId}/resources`,
      {
        ...input,
        display_name: input.display_name || null,
        effective_from: input.effective_from || null,
        effective_to: input.effective_to || null,
        attributes: {},
        reason: '从业务资产工作台登记服务资源',
      },
      { headers: { 'Idempotency-Key': idempotencyKey('service-resource') } }
    )
  ).data as ServiceResource
}

export async function createContract(input: {
  contract_no: string
  customer_id: string
  company_id: string
  contract_name: string
  effective_from: string
  effective_to?: string
  auto_renew: boolean
  billing_cycle: string
  billing_day?: number
  payment_terms_days: number
  currency_code: string
  tax_rate?: string
  tax_inclusive: boolean
  notes?: string
}) {
  return (
    await api.post(
      '/contracts',
      {
        ...input,
        effective_to: input.effective_to || null,
        billing_day: input.billing_day || null,
        tax_rate: input.tax_rate || null,
        reason: '从合同与价格工作台创建合同',
      },
      { headers: { 'Idempotency-Key': idempotencyKey('contract') } }
    )
  ).data as Contract
}

export async function activateContract(contract: Contract) {
  return (
    await api.post(
      `/contracts/${contract.id}/activate`,
      { reason: '合同商业条款和关联关系已人工复核' },
      {
        headers: {
          'If-Match': `"${contract.version}"`,
          'Idempotency-Key': idempotencyKey('contract-activate'),
        },
      }
    )
  ).data as Contract
}

export async function createContractItem(
  contractId: string,
  input: {
    contract_item_no: string
    service_id: string
    pricing_rule_id: string
    item_name: string
    billing_type: string
    billing_cycle: string
    effective_from: string
    effective_to?: string
    default_quantity?: string
    unit?: string
    tax_category?: string
    auto_bill: boolean
    visible_on_invoice: boolean
    sort_order: number
    status: string
  }
) {
  return (
    await api.post(
      `/contracts/${contractId}/items`,
      {
        ...input,
        effective_to: input.effective_to || null,
        default_quantity: input.default_quantity || null,
        unit: input.unit || null,
        tax_category: input.tax_category || null,
        attributes: {},
        reason: '从合同与价格工作台创建合同计费项',
      },
      { headers: { 'Idempotency-Key': idempotencyKey('contract-item') } }
    )
  ).data as ContractItem
}

export async function createPricingVersion(
  ruleId: string,
  input: {
    effective_from: string
    effective_to?: string
    billing_type: string
    currency_code: string
    unit?: string
    unit_price?: string
    base_fee?: string
    committed_quantity?: string
    overage_unit_price?: string
    minimum_charge?: string
    maximum_charge?: string
    discount_rate?: string
    tax_rate?: string
    rounding_mode: string
    rounding_scale?: number
    rounding_step?: string
    free_allowance?: string
    proration_mode?: string
    tiers: Array<{
      lower_bound: string
      upper_bound?: string
      unit_price: string
    }>
    change_note: string
  }
) {
  const { rounding_step, free_allowance, proration_mode, ...request } = input
  return (
    await api.post(
      `/pricing-rules/${ruleId}/versions`,
      {
        ...request,
        effective_to: input.effective_to || null,
        unit_price: input.unit_price || null,
        base_fee: input.base_fee || null,
        committed_quantity: input.committed_quantity || null,
        overage_unit_price: input.overage_unit_price || null,
        minimum_charge: input.minimum_charge || null,
        maximum_charge: input.maximum_charge || null,
        discount_rate: input.discount_rate || null,
        tax_rate: input.tax_rate || null,
        config: {
          ...(rounding_step ? { rounding_step } : {}),
          ...(free_allowance ? { free_allowance } : {}),
          proration_mode: proration_mode || 'ACTUAL_DAYS',
        },
        tiers: input.tiers.map((tier) => ({
          ...tier,
          upper_bound: tier.upper_bound || null,
        })),
        reason: '从合同与价格工作台创建不可变价格版本',
      },
      { headers: { 'Idempotency-Key': idempotencyKey('pricing-version') } }
    )
  ).data as PricingVersion
}

export async function publishPricingVersion(versionId: string) {
  return (
    await api.post(
      `/pricing-rule-versions/${versionId}/publish`,
      { reason: '价格版本已人工复核并发布' },
      { headers: { 'Idempotency-Key': idempotencyKey('pricing-publish') } }
    )
  ).data as PricingVersion
}

export async function validatePricingVersion(versionId: string) {
  return (await api.post(`/pricing-rule-versions/${versionId}/validate`, {}))
    .data as { valid: boolean; errors: string[] }
}

export async function beginMfaEnrollment(currentPassword: string) {
  return (
    await api.post(
      '/auth/mfa/enrollment',
      {
        current_password: currentPassword,
        reason: '用户从系统管理页面开始 MFA 注册',
      },
      { headers: { 'Idempotency-Key': idempotencyKey('mfa-enrollment') } }
    )
  ).data as {
    secret: string
    otpauth_uri: string
    enrollment_proof: string
    version: number
  }
}

export async function confirmMfaEnrollment(
  code: string,
  enrollmentProof: string
) {
  return (
    await api.post(
      '/auth/mfa/confirm',
      {
        code,
        enrollment_proof: enrollmentProof,
        reason: '用户确认 MFA 注册',
      },
      { headers: { 'Idempotency-Key': idempotencyKey('mfa-confirm') } }
    )
  ).data as { recovery_codes: string[]; version: number }
}

export async function regenerateMfaRecoveryCodes(code: string) {
  return (
    await api.post(
      '/auth/mfa/recovery-codes',
      { code, reason: '用户重新生成 MFA 恢复码' },
      { headers: { 'Idempotency-Key': idempotencyKey('mfa-recovery') } }
    )
  ).data as { recovery_codes: string[]; version: number }
}

export async function disableMfa(code: string) {
  return (
    await api.post(
      '/auth/mfa/disable',
      { code, reason: '用户禁用 MFA' },
      { headers: { 'Idempotency-Key': idempotencyKey('mfa-disable') } }
    )
  ).data as { mfa_enabled: boolean; version: number }
}

export async function sendInvoice(
  invoice: InvoiceSummary,
  input: { emails: string[]; webhook_endpoint_ids: string[]; reason: string }
) {
  return (
    await api.post(
      `/invoices/${invoice.id}/send`,
      { ...input, expected_version: invoice.version },
      {
        headers: {
          'If-Match': `"${invoice.version}"`,
          'Idempotency-Key': idempotencyKey('invoice-send'),
        },
      }
    )
  ).data
}

export async function voidInvoice(invoice: InvoiceSummary, reason: string) {
  return (
    await api.post(
      `/invoices/${invoice.id}/void`,
      { expected_version: invoice.version, reason },
      {
        headers: {
          'If-Match': `"${invoice.version}"`,
          'Idempotency-Key': idempotencyKey('invoice-void'),
        },
      }
    )
  ).data as { invoice_id: string; document_status: string; version: number }
}

export async function createReplacementPreview(
  invoice: InvoiceSummary,
  reason: string
) {
  return (
    await api.post(
      `/invoices/${invoice.id}/create-replacement-preview`,
      { expected_version: invoice.version, reason },
      {
        headers: {
          'If-Match': `"${invoice.version}"`,
          'Idempotency-Key': idempotencyKey('invoice-replacement'),
        },
      }
    )
  ).data as {
    preview_id: string
    preview_number: string
    status: string
    version: number
    created: boolean
  }
}

export async function allocatePayment(
  payment: Payment,
  invoiceId: string,
  amountMinor: MinorUnits,
  reason: string
) {
  return (
    await api.post(
      `/payments/${payment.id}/allocations`,
      {
        invoice_id: invoiceId,
        amount_minor: amountMinor,
        expected_payment_version: payment.version,
        reason,
      },
      {
        headers: {
          'If-Match': `"${payment.version}"`,
          'Idempotency-Key': idempotencyKey('payment-allocation'),
        },
      }
    )
  ).data
}

export async function reversePaymentAllocation(
  payment: Payment,
  allocationId: string,
  reason: string
) {
  return (
    await api.post(
      `/payments/${payment.id}/allocations/${allocationId}/reverse`,
      { expected_version: payment.version, reason },
      {
        headers: {
          'If-Match': `"${payment.version}"`,
          'Idempotency-Key': idempotencyKey('payment-allocation-reverse'),
        },
      }
    )
  ).data
}

export async function refundPayment(
  payment: Payment,
  amountMinor: MinorUnits,
  reason: string,
  externalReference?: string
) {
  return (
    await api.post(
      `/payments/${payment.id}/refunds`,
      {
        expected_version: payment.version,
        amount_minor: amountMinor,
        refunded_at: new Date().toISOString(),
        external_reference: externalReference,
        reason,
      },
      {
        headers: {
          'If-Match': `"${payment.version}"`,
          'Idempotency-Key': idempotencyKey('payment-refund'),
        },
      }
    )
  ).data
}

export async function createLibrenmsMapping(
  instanceId: string,
  input: {
    librenms_bill_id: number
    customer_id: string
    company_id: string
    service_id: string
    contract_item_id: string
    billing_direction: string
    source_unit?: string
    effective_from: string
    reason: string
  }
) {
  return (
    await api.post(`/librenms/instances/${instanceId}/mappings`, input, {
      headers: { 'Idempotency-Key': idempotencyKey('librenms-mapping') },
    })
  ).data as LibrenmsMapping
}

export async function syncLibrenmsHistory(
  instanceId: string,
  mappingId: string,
  periodStart: string,
  periodEnd: string
) {
  return (
    await api.post(
      `/librenms/instances/${instanceId}/mappings/${mappingId}/sync-history`,
      { period_start: periodStart, period_end: periodEnd },
      { headers: { 'Idempotency-Key': idempotencyKey('librenms-history') } }
    )
  ).data as { job_id: string }
}

export async function createTemplateVersion(
  templateId: string,
  input: { html_content: string; css_content: string; change_note: string }
) {
  return (
    await api.post(
      `/invoice-templates/${templateId}/versions`,
      {
        ...input,
        schema: { type: 'object' },
        field_config: {},
        list_config: [],
        reason: '从模板中心创建版本',
      },
      { headers: { 'Idempotency-Key': idempotencyKey('template-version') } }
    )
  ).data as TemplateVersion
}

export async function copyInvoiceTemplate(
  templateId: string,
  input: { template_code: string; template_name: string }
) {
  return (
    await api.post(
      `/invoice-templates/${templateId}/copy`,
      { ...input, reason: '从模板中心复制模板' },
      { headers: { 'Idempotency-Key': idempotencyKey('template-copy') } }
    )
  ).data as InvoiceTemplate
}

export async function publishTemplateVersion(versionId: string) {
  return (
    await api.post(
      `/template-versions/${versionId}/publish`,
      { reason: '从模板中心发布已校验版本' },
      { headers: { 'Idempotency-Key': idempotencyKey('template-publish') } }
    )
  ).data as TemplateVersion
}

export async function updateOperationalSettings(
  settings: OperationalSettings,
  input: {
    system_user_id: string | null
    auto_generation_enabled: boolean
    auto_send_enabled: boolean
    emergency_stop: boolean
    emergency_reason: string | null
  }
) {
  return (
    await api.patch(
      '/operations/settings',
      { ...input, expected_version: settings.version },
      { headers: { 'If-Match': `"${settings.version}"` } }
    )
  ).data as OperationalSettings
}

export function compareMinor(left: Decimal.Value, right: Decimal.Value) {
  return new Decimal(left).cmp(right)
}

const MAX_MINOR_UNITS = new Decimal('9223372036854775807')

function currencyScale(currency: string) {
  return currency === 'JPY' ? 0 : 2
}

export function toPositiveMinorUnits(
  value: Decimal.Value,
  currency: string
): MinorUnits {
  const minor = new Decimal(value).mul(
    new Decimal(10).pow(currencyScale(currency))
  )
  if (!minor.isPositive() || !minor.isInteger() || minor.gt(MAX_MINOR_UNITS)) {
    throw new Error('Amount cannot be represented exactly in minor units')
  }
  return minor.toFixed(0)
}

export function isValidPositiveAmount(value: string, currency: string) {
  try {
    toPositiveMinorUnits(value, currency)
    return true
  } catch {
    return false
  }
}

export function abbreviateMinor(minor: Decimal.Value) {
  const value = new Decimal(minor)
  const magnitude = value.abs()
  const units = [
    { threshold: new Decimal('1000000000000'), suffix: 'T' },
    { threshold: new Decimal('1000000000'), suffix: 'B' },
    { threshold: new Decimal('1000000'), suffix: 'M' },
    { threshold: new Decimal('1000'), suffix: 'K' },
  ]
  const unit = units.find(({ threshold }) =>
    magnitude.greaterThanOrEqualTo(threshold)
  )

  return unit
    ? `${value.div(unit.threshold).toSignificantDigits(3).toString()}${unit.suffix}`
    : value.toFixed(0)
}

export function money(minor: Decimal.Value, currency: string) {
  const scale = currencyScale(currency)
  const value = new Decimal(minor)
    .div(new Decimal(10).pow(scale))
    .toFixed(scale)
  const [integer, fraction] = value.split('.')
  const grouped = integer.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  const symbols: Record<string, string> = {
    CNY: '¥',
    USD: '$',
    HKD: 'HK$',
    JPY: '¥',
  }
  return `${symbols[currency] ?? currency + ' '}${grouped}${fraction ? `.${fraction}` : ''}`
}

async function patchEntity<T>(
  path: string,
  body: unknown,
  version: number
): Promise<T> {
  return (
    await api.patch(path, body, {
      headers: { 'If-Match': `W/"${version}"` },
    })
  ).data as T
}

export function updateCompany(
  id: string,
  version: number,
  input: {
    company_name?: string
    company_name_en?: string
    country_region?: string
    address?: string
    tax_number?: string
    invoice_title?: string
    phone?: string
    bank_name?: string
    bank_account?: string
    invoice_type?: string
    swift_code?: string
    br_number?: string
    bank_code?: string
    bank_address?: string
    default_currency?: string
    default_tax_rate?: string
    status?: string
    reason: string
  }
) {
  return patchEntity<Company>(`/companies/${id}`, input, version)
}

export function updateService(
  id: string,
  version: number,
  input: {
    service_name?: string
    region?: string
    datacenter?: string
    line_name?: string
    activated_on?: string
    deactivated_on?: string
    status?: string
    notes?: string
    reason: string
  }
) {
  return patchEntity<Service>(`/services/${id}`, input, version)
}

export function updateContract(
  id: string,
  version: number,
  input: {
    contract_name?: string
    effective_from?: string
    effective_to?: string
    auto_renew?: boolean
    billing_day?: number
    payment_terms_days?: number
    tax_rate?: string
    tax_inclusive?: boolean
    notes?: string
    reason: string
  }
) {
  return patchEntity<Contract>(`/contracts/${id}`, input, version)
}

export function updateContractItem(
  id: string,
  version: number,
  input: {
    item_name?: string
    effective_to?: string
    default_quantity?: string
    auto_bill?: boolean
    visible_on_invoice?: boolean
    sort_order?: number
    status?: string
    reason: string
  }
) {
  return patchEntity<ContractItem>(`/contract-items/${id}`, input, version)
}

export function updateInvoiceProfile(
  id: string,
  version: number,
  input: {
    profile_name?: string
    billing_entity_id?: string
    template_id?: string
    document_template_id?: string
    language?: string
    timezone?: string
    billing_day?: number
    payment_terms_days?: number
    invoice_number_rule?: string
    auto_generate?: boolean
    auto_submit_review?: boolean
    auto_send?: boolean
    status?: string
    notes?: string
    reason: string
  }
) {
  return patchEntity<InvoiceProfile>(`/invoice-profiles/${id}`, input, version)
}

export function updateLibrenmsInstance(
  id: string,
  version: number,
  input: {
    instance_name?: string
    base_url?: string
    api_token?: string
    timezone?: string
    connect_timeout_ms?: number
    read_timeout_ms?: number
    max_concurrency?: number
    status?: string
    reason: string
  }
) {
  return patchEntity<LibrenmsInstance>(
    `/librenms/instances/${id}`,
    input,
    version
  )
}

export type BillingEntity = Complete<GeneratedModel.EntityResponse>

export const billingEntitiesQuery = queryOptions({
  queryKey: ['billing-entities'],
  queryFn: async ({ signal }) =>
    (await api.get('/billing-entities', { signal })).data as BillingEntity[],
})

export interface BillingEntityInput {
  entity_code?: string
  entity_name: string
  entity_name_en?: string
  country_region?: string
  address?: string
  phone?: string
  tax_number?: string
  br_number?: string
  invoice_title?: string
  bank_name?: string
  bank_code?: string
  swift_code?: string
  bank_address?: string
  bank_account?: string
  default_currency?: string
  status?: string
}

export async function createBillingEntity(input: BillingEntityInput) {
  return (
    await api.post(
      '/billing-entities',
      { ...input, reason: '在系统管理新增出账主体' },
      { headers: { 'Idempotency-Key': idempotencyKey('billing-entity') } }
    )
  ).data as BillingEntity
}

export function updateBillingEntity(
  id: string,
  version: number,
  input: BillingEntityInput & { reason: string }
) {
  return patchEntity<BillingEntity>(`/billing-entities/${id}`, input, version)
}

export interface RenderedFile {
  id: string
  filename: string
  mime_type: string
  size: number
  sha256: string
}

export async function uploadContractTemplate(
  contractId: string,
  version: number,
  file: File
): Promise<RenderedFile> {
  const form = new FormData()
  form.append('file', file)
  return (
    await api.post(`/contracts/${contractId}/template`, form, {
      headers: { 'If-Match': `W/"${version}"` },
    })
  ).data as RenderedFile
}

export async function renderContractDocument(
  contractId: string,
  options?: { templateId?: string; billingEntityId?: string }
) {
  const body: Record<string, string> = {}
  if (options?.templateId) body.template_id = options.templateId
  if (options?.billingEntityId) body.billing_entity_id = options.billingEntityId
  return (
    await api.post(`/contracts/${contractId}/render`, body)
  ).data as RenderedFile
}

export async function uploadProfileExcelTemplate(
  profileId: string,
  version: number,
  file: File
): Promise<RenderedFile> {
  const form = new FormData()
  form.append('file', file)
  return (
    await api.post(`/invoice-profiles/${profileId}/excel-template`, form, {
      headers: { 'If-Match': `W/"${version}"` },
    })
  ).data as RenderedFile
}

export async function renderInvoiceExcel(invoiceId: string) {
  return (await api.post(`/invoices/${invoiceId}/render-excel`, {}))
    .data as RenderedFile
}

export async function downloadFile(fileId: string, filename: string) {
  const response = await api.get(`/files/${fileId}/content`, {
    responseType: 'blob',
  })
  const url = URL.createObjectURL(response.data)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  anchor.click()
  URL.revokeObjectURL(url)
}

export type DocumentTemplate = Complete<GeneratedModel.DocumentTemplateResponse>

export const documentTemplatesQuery = (type?: string) =>
  queryOptions({
    queryKey: ['document-templates', type],
    queryFn: async ({ signal }) =>
      (await api.get('/document-templates', { params: { type }, signal }))
        .data as DocumentTemplate[],
  })

export async function uploadDocumentTemplate(
  input: {
    template_code: string
    template_name: string
    template_type: 'CONTRACT_DOCX' | 'INVOICE_XLSX'
    description?: string
  },
  file: File
) {
  const form = new FormData()
  form.append('template_code', input.template_code)
  form.append('template_name', input.template_name)
  form.append('template_type', input.template_type)
  if (input.description) form.append('description', input.description)
  form.append('file', file)
  form.append('reason', '在模板中心上传模板')
  return (
    await api.post('/document-templates', form)
  ).data as DocumentTemplate
}

export async function updateDocumentTemplate(
  id: string,
  version: number,
  input: { template_name?: string; description?: string; status?: string },
  file?: File
) {
  const form = new FormData()
  if (input.template_name) form.append('template_name', input.template_name)
  if (input.description) form.append('description', input.description)
  if (input.status) form.append('status', input.status)
  if (file) form.append('file', file)
  form.append('reason', '在模板中心编辑模板')
  return (
    await api.patch(`/document-templates/${id}`, form, {
      headers: { 'If-Match': `W/"${version}"` },
    })
  ).data as DocumentTemplate
}

export async function deleteDocumentTemplate(id: string, version: number) {
  return await api.delete(`/document-templates/${id}`, {
    headers: { 'If-Match': `W/"${version}"` },
    params: { reason: '在模板中心删除模板' },
  })
}
