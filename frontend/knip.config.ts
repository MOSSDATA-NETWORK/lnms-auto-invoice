import type { KnipConfig } from 'knip'

const config: KnipConfig = {
  entry: ['orval.config.ts', 'src/tanstack-table.d.ts'],
  ignore: ['src/api/generated/**'],
}

export default config
