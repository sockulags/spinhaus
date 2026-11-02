import { defineConfig } from 'vite'

export default defineConfig({
  base: './',
  build: { outDir: 'dist', emptyOutDir: true, target: 'es2020', chunkSizeWarningLimit: 2500, assetsInlineLimit: 0 }
})
