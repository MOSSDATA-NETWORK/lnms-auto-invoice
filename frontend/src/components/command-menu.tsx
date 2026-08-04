import React from 'react'
import { useSuspenseQuery } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import { navigationFor } from '@/app/navigation'
import { ArrowRight, Laptop, Moon, Sun } from 'lucide-react'
import { sessionQuery } from '@/api/auth'
import { useSearch } from '@/context/search-provider'
import { useTheme } from '@/context/theme-provider'
import {
  CommandDialog,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
  CommandSeparator,
} from '@/components/ui/command'
import { ScrollArea } from '@/components/ui/scroll-area'

export function CommandMenu() {
  const navigate = useNavigate()
  const { setTheme } = useTheme()
  const { open, setOpen } = useSearch()
  const { data: session } = useSuspenseQuery(sessionQuery)
  const navigation = navigationFor(session)

  const runCommand = React.useCallback(
    (command: () => unknown) => {
      setOpen(false)
      command()
    },
    [setOpen]
  )

  return (
    <CommandDialog modal open={open} onOpenChange={setOpen}>
      <CommandInput placeholder='搜索命令或页面…' />
      <CommandList>
        <ScrollArea type='hover' className='h-72 pe-1'>
          <CommandEmpty>没有匹配的页面。</CommandEmpty>
          {navigation.map((group) => (
            <CommandGroup key={group.title} heading={group.title}>
              {group.items
                .flatMap((item) => item.items ?? [item])
                .map((item) => (
                  <CommandItem
                    key={`${item.title}-${item.url}`}
                    value={item.title}
                    onSelect={() =>
                      runCommand(() => navigate({ to: item.url }))
                    }
                  >
                    <ArrowRight className='size-3 text-muted-foreground' />
                    {item.title}
                  </CommandItem>
                ))}
            </CommandGroup>
          ))}
          <CommandSeparator />
          <CommandGroup heading='外观'>
            <CommandItem onSelect={() => runCommand(() => setTheme('light'))}>
              <Sun />
              浅色
            </CommandItem>
            <CommandItem onSelect={() => runCommand(() => setTheme('dark'))}>
              <Moon />
              深色
            </CommandItem>
            <CommandItem onSelect={() => runCommand(() => setTheme('system'))}>
              <Laptop />
              跟随系统
            </CommandItem>
          </CommandGroup>
        </ScrollArea>
      </CommandList>
    </CommandDialog>
  )
}
