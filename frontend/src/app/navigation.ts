import { can } from '@/auth/permission'
import {
  Activity,
  Blocks,
  BookOpenCheck,
  Boxes,
  Building2,
  Cable,
  FileCheck2,
  FileClock,
  FileStack,
  LayoutDashboard,
  ScrollText,
  Settings2,
  Stamp,
  WalletCards,
} from 'lucide-react'
import type { PermissionCode, Session } from '@/api/types'
import type { NavGroup } from '@/components/layout/types'

type GuardedItem = {
  title: string
  url: string
  icon: React.ElementType
  permissions?: PermissionCode[]
}

const overview: GuardedItem = {
  title: '总览',
  url: '/',
  icon: LayoutDashboard,
}

const business: GuardedItem[] = [
  {
    title: '客户管理',
    url: '/customers',
    icon: Building2,
    permissions: ['customer.read'],
  },
  {
    title: '业务管理',
    url: '/services',
    icon: Boxes,
    permissions: ['customer.read'],
  },
  {
    title: '合同管理',
    url: '/contracts',
    icon: BookOpenCheck,
    permissions: ['contract.write', 'pricing.publish'],
  },
  {
    title: '账单配置',
    url: '/profiles',
    icon: Blocks,
    permissions: ['preview.generate'],
  },
  {
    title: '预览与审批',
    url: '/previews',
    icon: FileClock,
    permissions: [
      'preview.generate',
      'preview.adjust',
      'preview.approve.business',
      'preview.approve.finance',
    ],
  },
  {
    title: '正式账单',
    url: '/invoices',
    icon: FileCheck2,
    permissions: ['invoice.finalize', 'invoice.send', 'invoice.void'],
  },
  {
    title: '模板中心',
    url: '/templates',
    icon: FileStack,
    permissions: ['template.publish'],
  },
]

const finance: GuardedItem[] = [
  {
    title: '付款管理',
    url: '/payments',
    icon: WalletCards,
    permissions: ['payment.record'],
  },
  {
    title: '公司抬头',
    url: '/letterhead',
    icon: Stamp,
    permissions: ['payment.record', 'system.admin'],
  },
  {
    title: '报表中心',
    url: '/reports',
    icon: ScrollText,
    permissions: ['payment.record', 'audit.read', 'system.admin'],
  },
]

const admin: GuardedItem[] = [
  {
    title: 'LibreNMS',
    url: '/librenms',
    icon: Cable,
    permissions: ['usage.sync'],
  },
  {
    title: '任务与审计',
    url: '/jobs',
    icon: Activity,
    permissions: ['audit.read', 'system.admin'],
  },
  {
    title: '系统管理',
    url: '/system',
    icon: Settings2,
    permissions: ['system.admin'],
  },
]

function visible(items: GuardedItem[], session?: Session) {
  return items
    .filter(
      (item) =>
        !item.permissions ||
        item.permissions.some((permission) => can(session, permission))
    )
    .map(({ permissions: _permissions, ...item }) => item)
}

export function navigationFor(session?: Session): NavGroup[] {
  return [
    { title: '总览', items: visible([overview], session) },
    { title: '业务端', items: visible(business, session) },
    { title: '财务端', items: visible(finance, session) },
    { title: '管理端', items: visible(admin, session) },
  ]
}
