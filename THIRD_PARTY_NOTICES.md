# Third-Party Notices

## shadcn-admin

Auto Invoice 的前端工程以 [`satnaing/shadcn-admin`](https://github.com/satnaing/shadcn-admin)
提交 `e16c87f213a5ba5e45964e9b67c792105ec74d26` 作为一次性代码基线。

- Copyright (c) 2024 Sat Naing
- License: MIT
- Local license copy: [`frontend/LICENSE`](frontend/LICENSE)
- Usage model: copied source baseline, not an npm dependency and not automatically synchronized with upstream

Auto Invoice 保留了管理后台布局、Sidebar、主题、Command Menu、Data Table、Dialog、错误页和测试配置；认证、品牌、路由、领域页面和 API 客户端已替换为本项目实现。上游演示用户、Clerk、假数据、任务、聊天和 JavaScript 可读 Token 不属于 Auto Invoice 运行时认证模型。

MIT License:

```text
Copyright (c) 2024 Sat Naing

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## shadcn/ui local-copy policy

`frontend/src/components/ui` 中的组件是源码副本，不是可安全覆盖的生成目录。项目对布局、主题、可访问性状态和表格交互存在本地适配。后续运行 shadcn CLI 前必须先比较差异并人工合并，禁止直接覆盖本地组件。
