import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// The dev server proxies the API to the monitoring server, so the browser talks to one origin.
const api = process.env.API_TARGET ?? 'http://localhost:8095'
export default defineConfig({
  plugins: [react()],
  server: { port: 5180, proxy: { '/api': api } },
  preview: { port: 4180, proxy: { '/api': api } },
  // `npm run build` writes into the server's static folder, so a packaged server jar serves the UI itself
  build: { outDir: '../server/src/main/resources/static', emptyOutDir: true },
})
