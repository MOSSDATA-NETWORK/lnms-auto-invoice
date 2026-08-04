import { CommandMenu } from '@/components/command-menu'
import { Header } from '@/components/layout/header'
import { ProfileDropdown } from '@/components/profile-dropdown'
import { Search } from '@/components/search'
import { ThemeSwitch } from '@/components/theme-switch'

export function ConsoleHeader({ label }: { label: string }) {
  return (
    <Header fixed className='border-b bg-background/92'>
      <div className='hidden min-w-0 md:block'>
        <p className='font-mono text-[10px] tracking-[0.2em] text-muted-foreground'>
          AUTO INVOICE / {label.toUpperCase()}
        </p>
      </div>
      <div className='ms-auto flex items-center gap-2'>
        <Search placeholder='搜索页面或命令…' />
        <ThemeSwitch />
        <ProfileDropdown />
      </div>
      <CommandMenu />
    </Header>
  )
}
