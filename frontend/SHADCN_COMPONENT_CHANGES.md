# shadcn/ui 本地差异记录

Auto Invoice 基于 shadcn-admin 固定提交建立前端代码后，不再自动同步上游。以下文件属于必须人工合并的本地副本：

- `src/components/ui/sidebar.tsx`：与布局上下文、折叠状态、移动端 Sheet 和本地 Cookie 偏好集成。
- `src/components/layout/*`：已替换为 Auto Invoice 品牌、权限导航、同源会话和租户信息。
- `src/components/data-table/*`：保留 TanStack Table 能力，并与 URL 查询状态和服务端分页约定集成。
- `src/components/ui/button.tsx`、`badge.tsx`、`table.tsx`、`dialog.tsx`、`select.tsx`：被领域页面广泛引用；升级时必须验证现有尺寸、状态、键盘焦点和暗色主题。
- `src/styles/theme.css`、`src/styles/index.css`：使用 Auto Invoice 的财务运营工作台色彩、IBM Plex 字体和 Tailwind CSS 4 变量。

更新流程：

1. 在临时目录运行 shadcn CLI 或检出新上游版本。
2. 逐文件比较，不对 `src/components/ui` 执行覆盖式生成。
3. 保留 Auto Invoice 的权限、会话、i18n、可访问性和测试约束。
4. 运行 `pnpm run format:check`、`pnpm run build`、`pnpm run lint` 和 `pnpm run test`。
5. 若组件行为或公共 API 变化，同一变更更新本文件。
