import { defineConfig } from 'orval'

export default defineConfig({
  autoInvoice: {
    input: process.env.OPENAPI_URL ?? '../openapi/auto-invoice.json',
    output: {
      target: './src/api/generated/auto-invoice.ts',
      schemas: './src/api/generated/model',
      client: 'react-query',
      httpClient: 'axios',
      mode: 'tags-split',
      override: {
        mutator: {
          path: './src/api/http.ts',
          name: 'apiMutator',
        },
      },
    },
  },
})
