import { Link, createFileRoute } from '@tanstack/react-router'
import { ShieldX } from 'lucide-react'
import { Button } from '@/components/ui/button'

export const Route = createFileRoute('/forbidden')({ component: ForbiddenPage })

function ForbiddenPage() {
  return (
    <main className='grid min-h-svh place-items-center bg-[radial-gradient(circle_at_top_right,color-mix(in_oklab,var(--destructive)_10%,transparent),transparent_32%)] px-6'>
      <section className='max-w-lg rounded-xl border bg-card p-8 shadow-sm'>
        <ShieldX className='size-9 text-destructive' />
        <p className='mt-6 font-mono text-xs tracking-[0.24em] text-muted-foreground'>
          ACCESS / DENIED
        </p>
        <h1 className='mt-3 text-3xl font-semibold tracking-tight'>
          没有执行此操作的权限
        </h1>
        <p className='mt-3 text-sm leading-6 text-muted-foreground'>
          菜单隐藏和路由守卫只改善操作体验，最终授权始终由服务端权限代码决定。
        </p>
        <Button asChild className='mt-7'>
          <Link to='/'>返回工作台</Link>
        </Button>
      </section>
    </main>
  )
}
