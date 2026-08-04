import { Link, createFileRoute } from '@tanstack/react-router'
import { ServerCrash } from 'lucide-react'
import { Button } from '@/components/ui/button'

export const Route = createFileRoute('/500')({ component: ServerErrorPage })

function ServerErrorPage() {
  return (
    <main className='grid min-h-svh place-items-center bg-background px-6'>
      <section className='max-w-lg rounded-xl border bg-card p-8 shadow-sm'>
        <ServerCrash className='size-9 text-amber-600' />
        <p className='mt-6 font-mono text-xs tracking-[0.24em] text-muted-foreground'>
          SERVER / ERROR
        </p>
        <h1 className='mt-3 text-3xl font-semibold tracking-tight'>
          服务暂时不可用
        </h1>
        <p className='mt-3 text-sm leading-6 text-muted-foreground'>
          请求没有使用本地假数据兜底。请稍后重试，或前往任务与审计页面检查后台作业。
        </p>
        <Button asChild className='mt-7'>
          <Link to='/'>返回工作台</Link>
        </Button>
      </section>
    </main>
  )
}
