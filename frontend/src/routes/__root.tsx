import { Outlet, createRootRouteWithContext } from '@tanstack/react-router'
import type { RouterContext } from '@/router-context'
import { Toaster } from '@/components/ui/sonner'

export const Route = createRootRouteWithContext<RouterContext>()({
  component: RootComponent,
  notFoundComponent: () => (
    <ErrorPanel
      code='404'
      title='页面不存在'
      detail='这个地址不属于当前账务控制台。'
    />
  ),
  errorComponent: ({ error }) => (
    <ErrorPanel code='500' title='页面加载失败' detail={error.message} />
  ),
})

function RootComponent() {
  return (
    <>
      <Outlet />
      <Toaster richColors position='top-right' />
    </>
  )
}

function ErrorPanel({
  code,
  title,
  detail,
}: {
  code: string
  title: string
  detail: string
}) {
  return (
    <main className='grid min-h-svh place-items-center bg-background px-6'>
      <div className='max-w-lg border-s-4 border-amber-500 ps-6'>
        <p className='font-mono text-sm tracking-[0.24em] text-muted-foreground'>
          ERROR / {code}
        </p>
        <h1 className='mt-3 text-3xl font-semibold tracking-tight'>{title}</h1>
        <p className='mt-3 text-sm leading-6 text-muted-foreground'>{detail}</p>
        <a
          className='mt-6 inline-flex text-sm font-semibold text-primary underline-offset-4 hover:underline'
          href='/'
        >
          返回总览
        </a>
      </div>
    </main>
  )
}
