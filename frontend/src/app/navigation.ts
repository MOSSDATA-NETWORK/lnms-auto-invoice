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

const items: GuardedItem[] = [
  { title: '总览', url: '/', icon: LayoutDashboard },
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
    title: '合同与价格',
    url: '/contracts',
    icon: BookOpenCheck,
    permissions: ['contract.write', 'pricing.publish'],
  },
  {
    title: 'LibreNMS',
    url: '/librenms',
    icon: Cable,
    permissions: ['usage.sync'],
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
  {
    title: '付款管理',
    url: '/payments',
    icon: WalletCards,
    permissions: ['payment.record'],
  },
  {
    title: '报表中心',
    url: '/reports',
    icon: ScrollText,
    permissions: ['payment.record', 'audit.read', 'system.admin'],
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

export function navigationFor(session?: Session): NavGroup[] {
  return [
    {
      title: '账务运行',
      items: items
        .filter(
          (item) =>
            !item.permissions ||
            item.permissions.some((permission) => can(session, permission))
        )
        .map(({ permissions: _permissions, ...item }) => item),
    },
  ]
}
