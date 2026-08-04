import type { ReactNode } from 'react'

export function PageHeading({
  eyebrow,
  title,
  description,
  action,
}: {
  eyebrow: string
  title: string
  description: string
  action?: ReactNode
}) {
  return (
    <div className='flex max-w-full min-w-0 flex-col justify-between gap-5 border-b pb-7 md:flex-row md:items-end'>
      <div className='max-w-3xl min-w-0'>
        <p className='font-mono text-[11px] font-medium tracking-[0.22em] text-emerald-700 dark:text-emerald-300'>
          {eyebrow}
        </p>
        <h1 className='mt-3 text-3xl font-semibold tracking-[-0.025em] break-words sm:text-4xl'>
          {title}
        </h1>
        <p className='mt-3 max-w-2xl text-sm leading-6 break-words text-muted-foreground'>
          {description}
        </p>
      </div>
      {action ? (
        <div className='max-w-full min-w-0 md:shrink-0'>{action}</div>
      ) : null}
    </div>
  )
}
