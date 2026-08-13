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
  country_region?: string
  address?: string
  tax_number?: string
  invoice_title?: string
  phone?: string
  bank_name?: string
  bank_account?: string
  invoice_type?: string
  swift_code?: string
  br_number?: string
  bank_code?: string
  bank_address?: string
  default_currency: string
  default_tax_rate?: string
  status?: string
}

function companyRegion(company?: Company): string {
  return company?.country_region === 'HK' ? 'HK' : 'CN'
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
  const [region, setRegion] = useState(companyRegion(company))
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
  const [swiftCode, setSwiftCode] = useState(company?.swift_code ?? '')
  const [bankCode, setBankCode] = useState(company?.bank_code ?? '')
  const [bankAddress, setBankAddress] = useState(company?.bank_address ?? '')
  const [brNumber, setBrNumber] = useState(company?.br_number ?? '')
  const [currency, setCurrency] = useState(
    company?.default_currency ?? (region === 'HK' ? 'HKD' : 'CNY')
  )
  const [taxRate, setTaxRate] = useState(company?.default_tax_rate ?? '')
  const [status, setStatus] = useState(company?.status ?? 'ACTIVE')

  const valid =
    (editing || /^[A-Z0-9][A-Z0-9-]{2,63}$/.test(code.trim())) &&
    name.trim().length >= 2 &&
    /^[A-Z]{3}$/.test(currency.trim())

  const switchRegion = (next: string) => {
    setRegion(next)
    setCurrency(next === 'HK' ? 'HKD' : 'CNY')
  }

  return (
    <Dialog open={open} onOpenChange={(next) => !next && onClose()}>
      <DialogContent className='max-h-[90vh] overflow-y-auto sm:max-w-2xl'>
        <DialogHeader>
          <DialogTitle>
            {editing ? `编辑公司 · ${company?.company_code}` : '新增公司'}
          </DialogTitle>
          <DialogDescription>
            {region === 'HK'
              ? '香港公司不开具内地发票,请填写英文银行信息用于收款。'
              : '开票种类为专票时,请补齐税号、开户银行与银行账户。'}
          </DialogDescription>
        </DialogHeader>
        <div className='grid gap-4 sm:grid-cols-2'>
          <div className='space-y-2'>
            <Label>地区</Label>
            <select
              value={region}
              onChange={(e) => switchRegion(e.target.value)}
              className='h-9 w-full rounded-md border bg-background px-3 text-sm'
            >
              <option value='CN'>中国</option>
              <option value='HK'>中国香港</option>
            </select>
          </div>
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
            <Label>{region === 'HK' ? '公司地址' : '地址'}</Label>
            <Input
              value={address}
              onChange={(e) => setAddress(e.target.value)}
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
          {region === 'CN' ? (
            <>
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
            </>
          ) : (
            <>
              <div className='space-y-2'>
                <Label>商业登记号(BR Number)</Label>
                <Input
                  className='font-mono'
                  placeholder='8 位数字'
                  value={brNumber}
                  onChange={(e) => setBrNumber(e.target.value)}
                />
              </div>
              <div className='space-y-2'>
                <Label>Bank Name</Label>
                <Input
                  value={bankName}
                  onChange={(e) => setBankName(e.target.value)}
                />
              </div>
              <div className='space-y-2'>
                <Label>Bank Code</Label>
                <Input
                  className='font-mono'
                  value={bankCode}
                  onChange={(e) => setBankCode(e.target.value)}
                />
              </div>
              <div className='space-y-2'>
                <Label>Swift Code</Label>
                <Input
                  className='font-mono'
                  value={swiftCode}
                  onChange={(e) => setSwiftCode(e.target.value.toUpperCase())}
                />
              </div>
              <div className='space-y-2'>
                <Label>账户号码</Label>
                <Input
                  className='font-mono'
                  value={bankAccount}
                  onChange={(e) => setBankAccount(e.target.value)}
                />
              </div>
              <div className='space-y-2 sm:col-span-2'>
                <Label>Bank Address</Label>
                <Input
                  value={bankAddress}
                  onChange={(e) => setBankAddress(e.target.value)}
                />
              </div>
            </>
          )}
          <div className='space-y-2'>
            <Label>默认币种</Label>
            <Input
              className='font-mono'
              value={currency}
              onChange={(e) => setCurrency(e.target.value.toUpperCase())}
            />
          </div>
          {region === 'CN' && (
            <div className='space-y-2'>
              <Label>默认税率(如 0.06)</Label>
              <Input
                className='font-mono'
                value={taxRate}
                onChange={(e) => setTaxRate(e.target.value)}
              />
            </div>
          )}
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
                country_region: region,
                address: address.trim() || undefined,
                tax_number:
                  region === 'CN' ? taxNumber.trim() || undefined : undefined,
                invoice_title:
                  region === 'CN'
                    ? invoiceTitle.trim() || undefined
                    : undefined,
                phone: phone.trim() || undefined,
                bank_name: bankName.trim() || undefined,
                bank_account: bankAccount.trim() || undefined,
                invoice_type: region === 'CN' ? invoiceType : undefined,
                swift_code:
                  region === 'HK' ? swiftCode.trim() || undefined : undefined,
                br_number:
                  region === 'HK' ? brNumber.trim() || undefined : undefined,
                bank_code:
                  region === 'HK' ? bankCode.trim() || undefined : undefined,
                bank_address:
                  region === 'HK' ? bankAddress.trim() || undefined : undefined,
                default_currency: currency.trim(),
                default_tax_rate:
                  region === 'CN' ? taxRate.trim() || undefined : undefined,
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
