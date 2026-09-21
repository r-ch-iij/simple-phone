import { defineConfig } from 'vite'

export default defineConfig({
  // Relative URLs keep the build usable under a project Pages path and in forks.
  base: './',
  build: {
    target: 'es2018',
  },
})
