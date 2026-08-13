import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Copy, FileCode2, LockKeyhole, Plus, ShieldCheck } from 'lucide-react'
import { toast } from 'sonner'
import { problemFrom } from '@/api/http'
import {
  copyInvoiceTemplate,
  createInvoiceTemplate,
  createTemplateVersion,
  publishTemplateVersion,
  templateDetailQuery,
  templatesQuery,
  type InvoiceTemplate,
} from '@/api/operations'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Skeleton } from '@/components/ui/skeleton'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { Textarea } from '@/components/ui/textarea'
import { Main } from '@/components/layout/main'
import { ConsoleHeader } from '@/features/shell/console-header'
import { PageHeading } from '@/features/shell/page-heading'

export function TemplatesPage() {
  const queryClient = useQueryClient()
  const templates = useQuery(templatesQuery)
  const [open, setOpen] = useState(false)
  const [code, setCode] = useState('')
  const [name, setName] = useState('')
  const [language, setLanguage] = useState('zh-CN')
  const [selected, setSelected] = useState<InvoiceTemplate>()
  const [copying, setCopying] = useState<InvoiceTemplate>()
  const [copyCode, setCopyCode] = useState('')
  const [copyName, setCopyName] = useState('')
  const detail = useQuery(templateDetailQuery(selected?.id))
  const latestVersion = detail.data?.versions?.[0]
  useEffect(() => {
    if (latestVersion) {
      setHtml(latestVersion.html_content)
      setCss(latestVersion.css_content ?? '')
      setChangeNote(`基于 v${latestVersion.version_no} 修改`)
    }
  }, [latestVersion?.id])
  const [html, setHtml] = useState(
    '<main class="invoice"><h1>{{invoice_number}}</h1></main>'
  )
  const [css, setCss] = useState('.invoice { font-family: sans-serif; }')
  const [changeNote, setChangeNote] = useState('初始化安全模板版本')
  const create = useMutation({
    mutationFn: () =>
      createInvoiceTemplate({
        template_code: code,
        template_name: name,
        default_language: language,
      }),
    onSuccess: async () => {
      toast.success('模板骨架已创建')
      setOpen(false)
      setCode('')
      setName('')
      await queryClient.invalidateQueries({ queryKey: ['invoice-templates'] })
    },
  })
  const createVersion = useMutation({
    mutationFn: () =>
      createTemplateVersion(selected!.id, {
        html_content: html,
        css_content: css,
        change_note: changeNote,
      }),
    onSuccess: async () => {
      toast.success('模板版本已创建并通过安全校验')
      await queryClient.invalidateQueries({
        queryKey: ['invoice-template', selected?.id],
      })
    },
  })
  const publish = useMutation({
    mutationFn: publishTemplateVersion,
    onSuccess: async () => {
      toast.success('模板版本已发布；已发布内容不可覆盖修改')
      await Promise.all([
        queryClient.invalidateQueries({
          queryKey: ['invoice-template', selected?.id],
        }),
        queryClient.invalidateQueries({ queryKey: ['invoice-templates'] }),
      ])
    },
  })
  const copy = useMutation({
    mutationFn: () =>
      copyInvoiceTemplate(copying!.id, {
        template_code: copyCode.trim(),
        template_name: copyName.trim(),
      }),
    onSuccess: async (created) => {
      toast.success(`已复制为 ${created.template_name}`)
      setCopying(undefined)
      setCopyCode('')
      setCopyName('')
      await queryClient.invalidateQueries({ queryKey: ['invoice-templates'] })
    },
    onError: (error) => {
      const problem = problemFrom(error)
      toast.error(problem.detail ?? problem.title ?? '复制失败')
    },
  })
  return (
    <>
      <ConsoleHeader label='templates' />
      <Main className='space-y-7'>
        <PageHeading
          eyebrow='模板与文件'
          title='安全模板中心'
          description='维护版本化 Handlebars HTML/CSS。发布前阻断脚本、远程 URL、本地文件和危险 CSS；Render Worker 默认无网络。'
          action={
            <Button onClick={() => setOpen(true)}>
              <Plus />
              创建模板
            </Button>
          }
        />
        <div className='grid gap-4 lg:grid-cols-[1fr_320px]'>
          <div className='overflow-hidden rounded-xl border bg-card'>
            {templates.isLoading ? (
              <Loading />
            ) : !templates.data?.length ? (
              <div className='grid place-items-center py-16 text-center'>
                <FileCode2 className='size-7 text-muted-foreground' />
                <p className='mt-4 font-semibold'>尚无模板</p>
                <p className='mt-2 text-sm text-muted-foreground'>
                  先创建模板骨架，再通过版本 API 提交 HTML、CSS 和字段配置。
                </p>
              </div>
            ) : (
              <Table>
                <TableHeader>
                  <TableRow className='bg-muted/30'>
                    <TableHead>模板代码</TableHead>
                    <TableHead>名称</TableHead>
                    <TableHead>语言</TableHead>
                    <TableHead>当前版本</TableHead>
                    <TableHead>状态</TableHead>
                    <TableHead>资源版本</TableHead>
                    <TableHead />
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {templates.data.map((template) => (
                    <TableRow key={template.id}>
                      <TableCell className='font-mono text-xs font-semibold'>
                        {template.template_code}
                      </TableCell>
                      <TableCell className='font-medium'>
                        {template.template_name}
                      </TableCell>
                      <TableCell>{template.default_language}</TableCell>
                      <TableCell className='font-mono text-xs'>
                        {template.current_version_id?.slice(0, 8) ?? '未发布'}
                      </TableCell>
                      <TableCell>
                        <Badge
                          variant={
                            template.status === 'ACTIVE' ? 'default' : 'outline'
                          }
                        >
                          {template.status}
                        </Badge>
                      </TableCell>
                      <TableCell className='font-mono text-xs'>
                        v{template.version}
                      </TableCell>
                      <TableCell>
                        <div className='flex gap-2'>
                          <Button
                            size='sm'
                            variant='outline'
                            onClick={() => setSelected(template)}
                          >
                            管理版本
                          </Button>
                          <Button
                            size='sm'
                            variant='ghost'
                            onClick={() => {
                              setCopying(template)
                              setCopyCode(`${template.template_code}-COPY`)
                              setCopyName(`${template.template_name} 副本`)
                            }}
                          >
                            <Copy /> 复制
                          </Button>
                        </div>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
          </div>
          <div className='grid content-start gap-4'>
            <Card className='shadow-none'>
              <CardHeader>
                <CardTitle className='flex items-center gap-2 text-base'>
                  <ShieldCheck className='size-4 text-emerald-700' />
                  发布门禁
                </CardTitle>
                <CardDescription>
                  未知变量、脚本、事件属性、远程资源和文件 URL 全部阻断。
                </CardDescription>
              </CardHeader>
            </Card>
            <Card className='shadow-none'>
              <CardHeader>
                <CardTitle className='flex items-center gap-2 text-base'>
                  <LockKeyhole className='size-4' />
                  正式文件
                </CardTitle>
              </CardHeader>
              <CardContent className='text-sm leading-6 text-muted-foreground'>
                正式 PDF 同时留存 SHA-256、模板版本、渲染器版本、Chromium
                版本和对象存储引用。
              </CardContent>
            </Card>
          </div>
        </div>
      </Main>
      <Dialog
        open={Boolean(copying)}
        onOpenChange={(next) => !next && setCopying(undefined)}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>复制模板 · {copying?.template_name}</DialogTitle>
            <DialogDescription>
              复制包含全部版本与资源;副本为独立草稿,可在线修改后再发布。
            </DialogDescription>
          </DialogHeader>
          <div className='grid gap-4'>
            <Field label='新模板代码'>
              <Input
                className='font-mono uppercase'
                value={copyCode}
                onChange={(event) =>
                  setCopyCode(event.target.value.toUpperCase())
                }
              />
            </Field>
            <Field label='新模板名称'>
              <Input
                value={copyName}
                onChange={(event) => setCopyName(event.target.value)}
              />
            </Field>
          </div>
          <DialogFooter>
            <Button variant='outline' onClick={() => setCopying(undefined)}>
              取消
            </Button>
            <Button
              disabled={
                copy.isPending ||
                !/^[A-Z0-9][A-Z0-9_-]{2,99}$/.test(copyCode.trim()) ||
                copyName.trim().length < 2
              }
              onClick={() => copy.mutate()}
            >
              创建副本
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
      <Dialog open={open} onOpenChange={setOpen}>
        {' '}
        <DialogContent>
          <DialogHeader>
            <DialogTitle>创建模板骨架</DialogTitle>
            <DialogDescription>
              模板创建后仍是草稿，必须创建版本、通过安全校验并显式发布。
            </DialogDescription>
          </DialogHeader>
          <div className='grid gap-4'>
            <Field label='模板代码'>
              <Input
                className='font-mono uppercase'
                value={code}
                onChange={(event) => setCode(event.target.value.toUpperCase())}
                placeholder='INVOICE_ZH'
              />
            </Field>
            <Field label='模板名称'>
              <Input
                value={name}
                onChange={(event) => setName(event.target.value)}
                placeholder='标准中文账单'
              />
            </Field>
            <Field label='默认语言'>
              <Input
                value={language}
                onChange={(event) => setLanguage(event.target.value)}
              />
            </Field>
          </div>
          <DialogFooter>
            <Button variant='outline' onClick={() => setOpen(false)}>
              取消
            </Button>
            <Button
              disabled={
                create.isPending ||
                !/^[A-Z0-9][A-Z0-9_-]{2,119}$/.test(code) ||
                name.trim().length < 2
              }
              onClick={() => create.mutate()}
            >
              {create.isPending ? '正在创建…' : '创建草稿'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
      <Dialog
        open={Boolean(selected)}
        onOpenChange={(open) => !open && setSelected(undefined)}
      >
        <DialogContent className='max-h-[calc(100svh-2rem)] overflow-y-auto sm:max-w-5xl'>
          <DialogHeader>
            <DialogTitle>{selected?.template_name}</DialogTitle>
            <DialogDescription>
              每次保存创建新的草稿版本。发布前执行脚本、远程 URL、本地文件和危险
              CSS 校验。
            </DialogDescription>
          </DialogHeader>
          <div className='grid gap-5 lg:grid-cols-[1.2fr_.8fr]'>
            <div className='space-y-4'>
              <Field label='Handlebars HTML'>
                <Textarea
                  value={html}
                  onChange={(event) => setHtml(event.target.value)}
                  className='min-h-64 font-mono text-xs'
                />
              </Field>
              <Field label='CSS'>
                <Textarea
                  value={css}
                  onChange={(event) => setCss(event.target.value)}
                  className='min-h-40 font-mono text-xs'
                />
              </Field>
              <Field label='变更说明'>
                <Input
                  value={changeNote}
                  onChange={(event) => setChangeNote(event.target.value)}
                />
              </Field>
              <Button
                disabled={
                  createVersion.isPending ||
                  html.trim().length < 10 ||
                  changeNote.trim().length < 2
                }
                onClick={() => createVersion.mutate()}
              >
                创建安全草稿版本
              </Button>
            </div>
            <div className='space-y-3'>
              <p className='text-sm font-medium'>版本时间轴</p>
              {detail.isLoading ? (
                <Loading />
              ) : !detail.data?.versions.length ? (
                <p className='rounded-lg border p-6 text-center text-sm text-muted-foreground'>
                  尚无版本。
                </p>
              ) : (
                detail.data.versions.map((version) => (
                  <div key={version.id} className='rounded-lg border p-4'>
                    <div className='flex items-start justify-between gap-3'>
                      <div>
                        <p className='font-mono text-sm font-semibold'>
                          v{version.version_no}
                        </p>
                        <p className='mt-1 text-xs text-muted-foreground'>
                          {version.change_note ?? '无变更说明'}
                        </p>
                      </div>
                      <Badge
                        variant={
                          version.status === 'PUBLISHED' ? 'default' : 'outline'
                        }
                      >
                        {version.status}
                      </Badge>
                    </div>
                    <p className='mt-3 truncate font-mono text-[10px] text-muted-foreground'>
                      {version.content_sha256}
                    </p>
                    {version.status === 'DRAFT' && (
                      <Button
                        size='sm'
                        className='mt-3 w-full'
                        disabled={publish.isPending}
                        onClick={() => publish.mutate(version.id)}
                      >
                        校验并发布
                      </Button>
                    )}
                  </div>
                ))
              )}
            </div>
          </div>
        </DialogContent>
      </Dialog>
    </>
  )
}

function Field({
  label,
  children,
}: {
  label: string
  children: React.ReactNode
}) {
  return (
    <div className='space-y-2'>
      <Label>{label}</Label>
      {children}
    </div>
  )
}
function Loading() {
  return (
    <div className='space-y-3 p-6'>
      {Array.from({ length: 5 }).map((_, index) => (
        <Skeleton key={index} className='h-12' />
      ))}
    </div>
  )
}
