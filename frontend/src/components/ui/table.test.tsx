import { describe, expect, it } from 'vitest'
import { render } from 'vitest-browser-react'
import { Table, TableBody, TableCell, TableRow } from './table'

describe('Table', () => {
  it('exposes the horizontal scroll container to keyboard users', async () => {
    const screen = await render(
      <Table aria-label='账单列表'>
        <TableBody>
          <TableRow>
            <TableCell>INV-001</TableCell>
          </TableRow>
        </TableBody>
      </Table>
    )

    const region = screen.getByRole('region', {
      name: '可横向滚动的数据表',
    })

    await expect.element(region).toHaveAttribute('tabindex', '0')
    await expect
      .element(screen.getByRole('table', { name: '账单列表' }))
      .toBeInTheDocument()
  })
})
