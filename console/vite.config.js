import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      // One entry per ingestion provider namespace served by core-api.
      '/zes': 'http://localhost:8080'
    }
  }
})
