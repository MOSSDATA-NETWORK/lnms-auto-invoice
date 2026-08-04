import { type SVGProps } from 'react'
import { cn } from '@/lib/utils'

export function Logo({ className, ...props }: SVGProps<SVGSVGElement>) {
  return (
    <svg
      id='auto-invoice-logo'
      viewBox='0 0 24 24'
      xmlns='http://www.w3.org/2000/svg'
      height='24'
      width='24'
      fill='none'
      stroke='currentColor'
      strokeWidth='2'
      strokeLinecap='round'
      strokeLinejoin='round'
      className={cn('size-6', className)}
      {...props}
    >
      <title>Auto Invoice</title>
      <path d='M7 3.5h10a2 2 0 0 1 2 2v15l-3-1.8-4 1.8-4-1.8-3 1.8v-15a2 2 0 0 1 2-2Z' />
      <path d='M8.5 8h7M8.5 12h4.5' />
    </svg>
  )
}
