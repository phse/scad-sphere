// Baut den 3D-Viewer: viewer.js (three.js + Loader + Steuerung) als ein Skript ohne Module,
// dazu index.html und viewer.css. Gradle packt dist/ unter /scadsphere-web/ ins Plugin.
import { build } from 'esbuild'
import { copyFileSync, mkdirSync, rmSync } from 'node:fs'

rmSync('dist', { recursive: true, force: true })
mkdirSync('dist')

await build({
  entryPoints: ['src/viewer.js'],
  outfile: 'dist/viewer.js',
  bundle: true,
  format: 'iife',
  minify: true,
  target: 'chrome120',
  legalComments: 'eof',
  logLevel: 'warning',
})

for (const file of ['index.html', 'viewer.css']) copyFileSync(`src/${file}`, `dist/${file}`)
