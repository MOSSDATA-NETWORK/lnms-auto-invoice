import { useState } from 'react'
import type { Company } from '@/api/operations'
import { Button } from '@/components/ui/button'
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

export interface CompanyFormValues {
  customer_id: string
  company_code: string
  company_name: string
  company_name_en?: string
  address?: string
  tax_number?: string
  invoice_title?: string
  phone?: string
  bank_name?: string
  bank_account?: string
  invoice_type?: string
  default_currency: string
  default_tax_rate?: string
  status?: string
}

export function CompanyFormDialog({
  open,
  company,
  customerId,
  pending,
  onClose,
  onSubmit,
}: {
  open: boolean
  company?: Company
  customerId: string
  pending: boolean
  onClose: () => void
  onSubmit: (values: CompanyFormValues) => void
}) {
  const editing = Boolean(company)
  const [code, setCode] = useState(company?.company_code ?? '')
  const [name, setName] = useState(company?.company_name ?? '')
  const [nameEn, setNameEn] = useState(company?.company_name_en ?? '')
  const [address, setAddress] = useState(company?.address ?? '')
  const [taxNumber, setTaxNumber] = useState(company?.tax_number ?? '')
  const [invoiceTitle, setInvoiceTitle] = useState(company?.invoice_title ?? '')
  const [phone, setPhone] = useState(company?.phone ?? '')
  const [bankName, setBankName] = useState(company?.bank_name ?? '')
  const [bankAccount, setBankAccount] = useState(company?.bank_account ?? '')
  const [invoiceType, setInvoiceType] = useState(
    company?.invoice_type ?? 'GENERAL'
  )
  const [currency, setCurrency] = useState(company?.default_currency ?? 'CNY')
  const [taxRate, setTaxRate] = useState(company?.default_tax_rate ?? '')
  const [status, setStatus] = useState(company?.status ?? 'ACTIVE')

  const valid =
    (editing || /^[A-Z0-9][A-Z0-9-]{2,63}$/.test(code.trim())) &&
    name.trim().length >= 2 &&
    /^[A-Z]{3}$/.test(currency.trim())

  return (
    <Dialog open={open} onOpenChange={(next) => !next && onClose()}>
      <DialogContent className='max-h-[90vh] overflow-y-auto sm:max-w-2xl'>
        <DialogHeader>
          <DialogTitle>
            {editing ? `编辑公司 · ${company?.company_code}` : '新增公司'}
          </DialogTitle>
          <DialogDescription>
            开票种类为专票时,请补齐税号、开户银行与银行账户。
          </DialogDescription>
        </DialogHeader>
        <div className='grid gap-4 sm:grid-cols-2'>
          {!editing && (
            <div className='space-y-2'>
              <Label>公司编码</Label>
              <Input
                placeholder='ACME-CN'
                className='font-mono'
                value={code}
                onChange={(e) => setCode(e.target.value.toUpperCase())}
              />
            </div>
          )}
          <div className='space-y-2'>
            <Label>公司名称</Label>
            <Input value={name} onChange={(e) => setName(e.target.value)} />
          </div>
          <div className='space-y-2'>
            <Label>英文名称</Label>
            <Input value={nameEn} onChange={(e) => setNameEn(e.target.value)} />
          </div>
          <div className='space-y-2 sm:col-span-2'>
            <Label>地址</Label>
            <Input
              value={address}
              onChange={(e) => setAddress(e.target.value)}
            />
          </div>
          <div className='space-y-2'>
            <Label>开票种类</Label>
            <select
              value={invoiceType}
              onChange={(e) => setInvoiceType(e.target.value)}
              className='h-9 w-full rounded-md border bg-background px-3 text-sm'
            >
              <option value='GENERAL'>普票</option>
              <option value='SPECIAL'>专票</option>
            </select>
          </div>
          <div className='space-y-2'>
            <Label>税号</Label>
            <Input
              className='font-mono'
              value={taxNumber}
              onChange={(e) => setTaxNumber(e.target.value)}
            />
          </div>
          <div className='space-y-2'>
            <Label>发票抬头</Label>
            <Input
              value={invoiceTitle}
              onChange={(e) => setInvoiceTitle(e.target.value)}
            />
          </div>
          <div className='space-y-2'>
            <Label>电话</Label>
            <Input
              className='font-mono'
              value={phone}
              onChange={(e) => setPhone(e.target.value)}
            />
          </div>
          <div className='space-y-2'>
            <Label>开户银行</Label>
            <Input
              value={bankName}
              onChange={(e) => setBankName(e.target.value)}
            />
          </div>
          <div className='space-y-2'>
            <Label>银行账户</Label>
            <Input
              className='font-mono'
              value={bankAccount}
              onChange={(e) => setBankAccount(e.target.value)}
            />
          </div>
          <div className='space-y-2'>
            <Label>默认币种</Label>
            <Input
              className='font-mono'
              value={currency}
              onChange={(e) => setCurrency(e.target.value.toUpperCase())}
            />
          </div>
          <div className='space-y-2'>
            <Label>默认税率(如 0.06)</Label>
            <Input
              className='font-mono'
              value={taxRate}
              onChange={(e) => setTaxRate(e.target.value)}
            />
          </div>
          {editing && (
            <div className='space-y-2'>
              <Label>状态</Label>
              <select
                value={status}
                onChange={(e) => setStatus(e.target.value)}
                className='h-9 w-full rounded-md border bg-background px-3 text-sm'
              >
                <option value='ACTIVE'>ACTIVE</option>
                <option value='ARCHIVED'>ARCHIVED</option>
              </select>
            </div>
          )}
        </div>
        <DialogFooter>
          <Button variant='outline' onClick={onClose}>
            取消
          </Button>
          <Button
            disabled={pending || !valid}
            onClick={() =>
              onSubmit({
                customer_id: customerId,
                company_code: code.trim(),
                company_name: name.trim(),
                company_name_en: nameEn.trim() || undefined,
                address: address.trim() || undefined,
                tax_number: taxNumber.trim() || undefined,
                invoice_title: invoiceTitle.trim() || undefined,
                phone: phone.trim() || undefined,
                bank_name: bankName.trim() || undefined,
                bank_account: bankAccount.trim() || undefined,
                invoice_type: invoiceType,
                default_currency: currency.trim(),
                default_tax_rate: taxRate.trim() || undefined,
                status: editing ? status : undefined,
              })
            }
          >
            {editing ? '保存修改' : '创建公司'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
