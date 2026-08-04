# OpenAPI 契约

`auto-invoice.json` 是由 API 启动器的 `/v3/api-docs` 生成并提交的 OpenAPI 3.1 契约，也是前端代码生成的默认输入。

更新后端公共接口时：

1. 在隔离的本地或 CI 环境设置 `OPENAPI_DOCS_ENABLED=true`，启动 API 并从 `/v3/api-docs` 导出最新的 `auto-invoice.json`。Swagger UI 不参与生成，通常保持关闭。
2. 在 `frontend` 目录运行 `pnpm run api:generate`。
3. 运行前端类型检查、构建和测试，确认生成路径不会重复 `/api/v1`。
4. 同时提交契约、生成客户端、API 测试和对应 `docs/*.md` 更新。

本地默认读取该静态文件；设置 `OPENAPI_URL` 可以临时改用运行中的 OpenAPI 地址。

动态 OpenAPI 与 Swagger UI 在应用配置中默认关闭，生产 Web 也不会代理这些路径。不要为了前端运行在正式环境开启它们。

生成约束：

- Schema 属性和查询参数统一为 `snake_case`。
- `BigDecimal` 统一发布为十进制字符串，浏览器不承担权威金额计算。
- Jackson `JsonNode` 发布为允许附加属性的对象，生成的 TypeScript 类型可安全索引。
- `frontend/src/api/generated/` 禁止手工编辑；需要幂等键或 ETag 的命令使用生成模型加手写薄封装。
