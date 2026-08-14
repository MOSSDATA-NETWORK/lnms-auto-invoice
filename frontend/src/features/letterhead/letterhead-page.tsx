import { BillingEntitiesPanel } from '@/features/system/billing-entities-panel'
import { Main } from '@/components/layout/main'
import { ConsoleHeader } from '@/features/shell/console-header'
import { PageHeading } from '@/features/shell/page-heading'

export function LetterheadPage() {
  return (
    <>
      <ConsoleHeader label='letterhead' />
      <Main className='space-y-7'>
        <PageHeading
          eyebrow='财务端 · 公司抬头'
          title='公司抬头与出账主体'
          description='这里维护我方开具与收款的各公司主体。中国内地主体维护税号与开票抬头，香港主体维护 BR Number 与 SWIFT。合同出件时甲方与账单卖方快照取自此处。'
        />
        <BillingEntitiesPanel />
      </Main>
    </>
  )
}
