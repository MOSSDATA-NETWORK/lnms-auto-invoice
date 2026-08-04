import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { useLocation, useNavigate } from '@tanstack/react-router'
import { toast } from 'sonner'
import { signOut } from '@/api/auth'
import { ConfirmDialog } from '@/components/confirm-dialog'

interface SignOutDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
}

export function SignOutDialog({ open, onOpenChange }: SignOutDialogProps) {
  const navigate = useNavigate()
  const location = useLocation()
  const queryClient = useQueryClient()
  const [pending, setPending] = useState(false)

  const handleSignOut = async () => {
    setPending(true)
    try {
      await signOut()
      queryClient.clear()
      await navigate({
        to: '/sign-in',
        search: { redirect: location.href },
        replace: true,
      })
    } catch {
      toast.error('退出失败，请重试。')
    } finally {
      setPending(false)
      onOpenChange(false)
    }
  }

  return (
    <ConfirmDialog
      open={open}
      onOpenChange={onOpenChange}
      title='退出登录'
      desc='将结束当前服务端会话。再次访问受保护页面时需要重新登录。'
      cancelBtnText='取消'
      confirmText={pending ? '正在退出…' : '退出登录'}
      destructive
      handleConfirm={handleSignOut}
      className='sm:max-w-sm'
    />
  )
}
