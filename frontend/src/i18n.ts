import i18n from 'i18next'
import { initReactI18next } from 'react-i18next'

const resources = {
  'zh-CN': {
    translation: {
      app: {
        name: 'Auto Invoice',
        subtitle: '商业账单控制台',
      },
      auth: {
        eyebrow: '受控访问',
        title: '进入账务控制台',
        description: '合同、用量、审批与正式账单在同一条可追溯链路中运行。',
        tenant: '租户代码',
        username: '用户名或邮箱',
        password: '密码',
        mfa: '六位动态验证码',
        signIn: '安全登录',
        verify: '验证并进入',
        signingIn: '正在验证…',
        sessionExpired: '会话已过期，请重新登录。',
      },
      nav: {
        overview: '总览',
        customers: '客户管理',
        services: '业务管理',
        contracts: '合同与价格',
        librenms: 'LibreNMS',
        profiles: '账单配置',
        previews: '预览与审批',
        invoices: '正式账单',
        templates: '模板中心',
        payments: '付款管理',
        reports: '报表中心',
        jobs: '任务与审计',
        system: '系统管理',
      },
      common: {
        search: '搜索命令或页面…',
        signOut: '退出登录',
        cancel: '取消',
        confirm: '确认',
        retry: '重试',
        loading: '正在加载',
        unavailable: '暂时无法获取数据',
      },
      dashboard: {
        eyebrow: '账期运行态势',
        title: '自动账单作业台',
        description: '从用量证据到正式账单，优先处理会阻断出账的事项。',
        customers: '客户',
        services: '有效业务',
        review: '待审核预览',
        finalizing: '正式化中',
        deadJobs: '死信任务',
        receivable: '未结应收',
      },
      customers: {
        eyebrow: '主数据',
        title: '客户与商业主体',
        description: '客户是商业关系主体，公司用于签约、付款与账单抬头。',
        add: '新增客户',
        number: '客户编号',
        name: '客户名称',
        type: '类型',
        currency: '默认币种',
        terms: '付款期限',
        status: '状态',
        empty: '尚无客户。创建首个客户后才能配置业务、合同与账单。',
      },
    },
  },
} as const

void i18n.use(initReactI18next).init({
  resources,
  lng: 'zh-CN',
  fallbackLng: 'zh-CN',
  interpolation: { escapeValue: false },
})
