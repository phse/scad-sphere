// 3D-Viewer für .stl, .3mf, .obj, .off, .amf und die Vorschau von .scad-Dateien. Läuft im eingebetteten Chromium (JCEF)
// der IDE; das Plugin steuert ihn über window.scadSphere, Rückmeldungen gehen per window.scadSphereHost.send.
import './passiveWheel.js'
import {
  AmbientLight, Box3, BufferGeometry, Color, DirectionalLight, DoubleSide, EdgesGeometry, Float32BufferAttribute,
  GridHelper, Group, LineBasicMaterial, LineSegments, Mesh, MeshPhongMaterial, Object3D, OrthographicCamera,
  PerspectiveCamera, Scene, ShapeGeometry, ShapePath, Sphere, Vector3, WebGLRenderer,
} from 'three'
import { OrbitControls } from 'three/addons/controls/OrbitControls.js'
import { STLLoader } from 'three/addons/loaders/STLLoader.js'
import { ThreeMFLoader } from 'three/addons/loaders/3MFLoader.js'
import { OBJLoader } from 'three/addons/loaders/OBJLoader.js'
import { AMFLoader } from 'three/addons/loaders/AMFLoader.js'
import { parseOff } from './offLoader.js'

// OpenSCAD und Slicer denken mit Z nach oben.
Object3D.DEFAULT_UP.set(0, 0, 1)

const init = window.__scadSphereInit || {}
const labels = init.labels || {}
const host = window.scadSphereHost || { send() {} }
const $ = (id) => document.getElementById(id)

const settings = {
  colors: true,
  edges: false,
  grid: true,
  ortho: false,
  ...readStored(),
}

const view = $('view')
let renderer
try {
  renderer = new WebGLRenderer({ antialias: true, preserveDrawingBuffer: true })
} catch (error) {
  // Ohne WebGL (z. B. abgeschaltete GPU im eingebetteten Browser) bleibt nur ein Hinweis
  const empty = $('empty')
  empty.textContent = labels.noWebGl || 'WebGL is not available.'
  empty.hidden = false
  const noop = () => {}
  window.scadSphere = { load: noop, setTools: noop, setTheme: noop, setStatus: noop, showEmpty: noop, setMessages: noop, expandMessages: noop, fit: noop, snapshot: noop, state: noop }
  host.send({ type: 'error', message: String(error) })
  host.send({ type: 'ready' })
  throw error
}
renderer.setPixelRatio(window.devicePixelRatio || 1)
view.appendChild(renderer.domElement)

const scene = new Scene()
const perspective = new PerspectiveCamera(30, 1, 0.1, 100000)
const orthographic = new OrthographicCamera(-1, 1, 1, -1, -100000, 100000)
let camera = settings.ortho ? orthographic : perspective
perspective.position.set(80, -170, 130)
orthographic.position.copy(perspective.position)

// Grundlicht plus ein Licht von der Kamera aus, damit jede Seite gut lesbar bleibt. Das Kameralicht hängt
// immer an der aktiven Kamera (beide Kameras sind in der Szene, zwei Lichter wären doppelt so hell).
const ambient = new AmbientLight(0xffffff, 1.2)
const keyLight = new DirectionalLight(0xffffff, 0.8)
keyLight.position.set(-1, -2, 3)
const headlight = new DirectionalLight(0xffffff, 1.6)
headlight.position.set(0.4, 0.6, 0)
headlight.target.position.set(0, 0, -1)
scene.add(ambient, keyLight, perspective, orthographic)
camera.add(headlight, headlight.target)

const controls = new OrbitControls(camera, renderer.domElement)
controls.enableDamping = false
controls.screenSpacePanning = true
controls.zoomToCursor = true
controls.addEventListener('change', requestRender)

let theme = { model: '#f9d72c', grid: '#c9ccd6', gridMajor: '#a8adbd', edges: '#2b2d30', dark: false }
let model = null        // Group mit den Meshes des aktuellen Modells
let edges = null        // Kanten, erst beim Einschalten berechnet
let grid = null
let bounds = null       // Box3 des Modells
let hasModel = false
let modelHasColors = false
let loadId = 0

// --- Zeichnen bei Bedarf (kein Dauer-Loop, die IDE soll ruhig bleiben) ---

let renderPending = false
function requestRender() {
  if (renderPending) return
  renderPending = true
  requestAnimationFrame(() => {
    renderPending = false
    renderer.render(scene, camera)
  })
}

function resize() {
  const width = view.clientWidth || 1
  const height = view.clientHeight || 1
  renderer.setSize(width, height, false)
  perspective.aspect = width / height
  perspective.updateProjectionMatrix()
  updateOrthoFrustum()
  requestRender()
}

function updateOrthoFrustum() {
  const width = view.clientWidth || 1
  const height = view.clientHeight || 1
  // Ausschnitt so groß wie der der Perspektive im Abstand des Drehpunkts
  const distance = orthographic.position.distanceTo(controls.target) || 100
  const halfHeight = distance * Math.tan((perspective.fov * Math.PI) / 360)
  orthographic.top = halfHeight
  orthographic.bottom = -halfHeight
  orthographic.left = -halfHeight * (width / height)
  orthographic.right = halfHeight * (width / height)
  orthographic.updateProjectionMatrix()
}

new ResizeObserver(resize).observe(view)

// --- Laden ---

async function load({ url, format, keepCamera }) {
  const id = ++loadId
  let response
  try {
    response = await fetch(url, { cache: 'no-store' })
    if (!response.ok) throw new Error(`${response.status} ${response.statusText}`)
  } catch (error) {
    if (id === loadId) showError(String(error.message || error))
    return
  }
  const data = TEXT_FORMATS.has(format) ? await response.text() : await response.arrayBuffer()
  if (id !== loadId) return
  let object
  try {
    object = parse(format, data)
  } catch (error) {
    showError(labels.parseError ? `${labels.parseError}\n${error.message || error}` : String(error))
    return
  }
  setModel(object, keepCamera && hasModel)
}

const TEXT_FORMATS = new Set(['svg', 'obj', 'off'])

function parse(format, data) {
  switch (format) {
    case 'stl': {
      const geometry = new STLLoader().parse(data)
      // Farbige Binär-STL (VisCAM/Magics) bringen Farben je Dreieck mit
      return single(geometry, geometry.hasColors)
    }
    case '3mf':
      return styleLoaded(new ThreeMFLoader().parse(data))
    case 'amf':
      return styleLoaded(new AMFLoader().parse(data))
    case 'obj': {
      // Ohne .mtl: alles in der Modellfarbe, außer die Ecken bringen Farben mit (v x y z r g b)
      const group = new Group()
      new OBJLoader().parse(data).traverse((child) => {
        if (!child.isMesh) return
        child.material = Array.isArray(child.material) ? child.material.map(() => objMaterial(child)) : objMaterial(child)
        group.add(child.clone())
      })
      return group
    }
    case 'off': {
      const { geometry, hasColors } = parseOff(data, new Color(theme.model))
      return single(geometry, hasColors)
    }
    case 'svg':
      return parseSvg(data)
    default:
      throw new Error(`Unknown format ${format}`)
  }
}

function single(geometry, vertexColors) {
  const material = modelMaterial()
  if (vertexColors) {
    material.vertexColors = true
    material.color.set(0xffffff)
    material.userData.themeColor = false
  }
  const group = new Group()
  group.add(new Mesh(geometry, material))
  return group
}

function objMaterial(mesh) {
  const material = modelMaterial()
  if (mesh.geometry.attributes.color) {
    material.vertexColors = true
    material.color.set(0xffffff)
    material.userData.themeColor = false
  }
  return material
}

/** Materialien aus 3MF/AMF wie die übrigen zeichnen; ohne Farbangabe in der Modellfarbe statt weiß bzw. blau. */
function styleLoaded(group) {
  group.traverse((child) => {
    if (!child.isMesh) return
    const materials = Array.isArray(child.material) ? child.material : [child.material]
    for (const material of materials) {
      material.side = DoubleSide
      material.flatShading = true
      material.shininess = 12
      if (material.name === '__DEFAULT' || material.name === '') {
        if (!material.vertexColors && !material.map) {
          material.color.set(theme.model)
          material.userData.themeColor = true
        }
      }
      material.needsUpdate = true
    }
  })
  return group
}

// --- Farbe oder einfarbig ---

/** Hat das Modell eigene Farben? Die Standardfarbe von OpenSCAD (wie die Modellfarbe) zählt nicht. */
function detectColors(object) {
  const plain = new Color(theme.model).getHex()
  let found = false
  object.traverse((child) => {
    if (!child.isMesh) return
    const materials = Array.isArray(child.material) ? child.material : [child.material]
    for (const material of materials) {
      if (material.userData.themeColor) continue
      if (material.vertexColors || material.map || material.color.getHex() !== plain) found = true
    }
  })
  return found
}

/** Farben zeigen oder alles in der Modellfarbe; die Originalfarben merkt sich jedes Material. */
function applyColorMode() {
  model?.traverse((child) => {
    if (!child.isMesh) return
    const materials = Array.isArray(child.material) ? child.material : [child.material]
    for (const material of materials) {
      if (material.userData.themeColor) continue
      const original = material.userData.original ||= {
        color: material.color.clone(),
        vertexColors: material.vertexColors,
        map: material.map,
      }
      const mono = !settings.colors
      material.color.copy(mono ? new Color(theme.model) : original.color)
      material.vertexColors = mono ? false : original.vertexColors
      material.map = mono ? null : original.map
      material.needsUpdate = true
    }
  })
  requestRender()
}

function toggleColors() {
  settings.colors = !settings.colors
  applyColorMode()
  store()
  updateToolbar()
}

/**
 * 2D-Ergebnis von OpenSCAD (SVG-Export, Pfade aus M/L/z, y gespiegelt): als Fläche auf z = 0,
 * so wie OpenSCAD 2D-Objekte selbst zeigt.
 */
function parseSvg(text) {
  const doc = new DOMParser().parseFromString(text, 'image/svg+xml')
  const path = new ShapePath()
  for (const element of doc.querySelectorAll('path')) {
    const tokens = (element.getAttribute('d') || '').match(/[MLZmlz]|-?[\d.]+(?:e[-+]?\d+)?/g) || []
    let command = null
    for (let i = 0; i < tokens.length; i++) {
      const token = tokens[i]
      if (/^[MLZmlz]$/.test(token)) {
        command = token.toUpperCase()
        if (command === 'Z') path.currentPath?.closePath?.()
        continue
      }
      const x = parseFloat(token)
      const y = -parseFloat(tokens[++i])
      if (command === 'M') {
        path.moveTo(x, y)
        command = 'L'
      } else {
        path.lineTo(x, y)
      }
    }
  }
  const group = new Group()
  // toShapes erkennt Löcher über die Verschachtelung (gerade/ungerade), nicht über die Drehrichtung
  const shapes = path.toShapes()
  if (shapes.length > 0) {
    const geometry = new ShapeGeometry(shapes)
    group.add(new Mesh(geometry, modelMaterial()))
  }
  return group
}

function modelMaterial() {
  const material = new MeshPhongMaterial({ color: theme.model, flatShading: true, side: DoubleSide, shininess: 12 })
  material.userData.themeColor = true
  return material
}

function setModel(object, keepCamera) {
  clearModel()
  model = object
  scene.add(model)
  bounds = new Box3().setFromObject(model)
  hasModel = !bounds.isEmpty()
  modelHasColors = detectColors(model)
  applyColorMode()
  updateToolbar()
  $('empty').hidden = true
  updateGrid()
  if (settings.edges) buildEdges()
  updateInfo()
  if (!keepCamera) fit('iso', false)
  updateOrthoFrustum()
  requestRender()
}

function clearModel() {
  if (edges) {
    scene.remove(edges)
    edges.traverse((child) => child.geometry?.dispose())
    edges = null
  }
  if (!model) return
  scene.remove(model)
  model.traverse((child) => {
    child.geometry?.dispose()
    const materials = Array.isArray(child.material) ? child.material : child.material ? [child.material] : []
    for (const material of materials) {
      material.map?.dispose()
      material.userData.original?.map?.dispose()
      material.dispose()
    }
  })
  model = null
  modelHasColors = false
}

function triangleCount(object) {
  let count = 0
  object.traverse((child) => {
    if (!child.isMesh) return
    const geometry = child.geometry
    count += (geometry.index ? geometry.index.count : geometry.attributes.position.count) / 3
  })
  return Math.round(count)
}

function updateInfo() {
  const info = $('info')
  if (!hasModel) {
    info.hidden = true
    return
  }
  const size = bounds.getSize(new Vector3())
  const number = (value) => value.toLocaleString(init.locale || undefined, { maximumFractionDigits: 2 })
  const triangles = triangleCount(model)
  const sizeText = size.z < 1e-9 ? `${number(size.x)} × ${number(size.y)}` : `${number(size.x)} × ${number(size.y)} × ${number(size.z)}`
  info.textContent = `${sizeText} mm · ${triangles.toLocaleString(init.locale || undefined)} ${labels.triangles || 'triangles'}`
  info.hidden = false
}

// --- Raster auf z = 0 (wie Druckbett), Größe passend zum Modell ---

function updateGrid() {
  if (grid) {
    scene.remove(grid)
    grid.geometry.dispose()
    grid.material.dispose()
    grid = null
  }
  if (!settings.grid) return requestRender()
  let extent = 100
  if (hasModel) {
    const box = bounds
    extent = Math.max(Math.abs(box.min.x), Math.abs(box.max.x), Math.abs(box.min.y), Math.abs(box.max.y), 10) * 2.4
  }
  const step = niceStep(extent / 20)
  const size = Math.ceil(extent / step / 2) * 2 * step
  grid = new GridHelper(size, Math.round(size / step), theme.gridMajor, theme.grid)
  grid.rotation.x = Math.PI / 2
  // Knapp unter z = 0, damit Flächen auf dem Raster nicht flimmern
  grid.position.z = hasModel && bounds.min.z >= -1e-6 ? -0.001 * size : 0
  grid.material.transparent = true
  grid.material.opacity = 0.7
  grid.material.depthWrite = false
  grid.renderOrder = -1
  scene.add(grid)
  requestRender()
}

function niceStep(raw) {
  const exponent = Math.floor(Math.log10(Math.max(raw, 0.001)))
  const base = 10 ** exponent
  const fraction = raw / base
  return (fraction < 1.5 ? 1 : fraction < 3.5 ? 2 : fraction < 7.5 ? 5 : 10) * base
}

// --- Kanten ---

function buildEdges() {
  if (!model) return
  edges = new Group()
  const material = new LineBasicMaterial({ color: theme.edges, transparent: true, opacity: theme.dark ? 0.55 : 0.45 })
  model.updateMatrixWorld(true)
  model.traverse((child) => {
    if (!child.isMesh) return
    const lines = new LineSegments(new EdgesGeometry(child.geometry, 25), material)
    lines.matrixAutoUpdate = false
    lines.matrix.copy(child.matrixWorld)
    edges.add(lines)
  })
  scene.add(edges)
}

function rebuildEdges() {
  if (edges) {
    scene.remove(edges)
    edges.traverse((child) => child.geometry?.dispose())
    edges = null
  }
  if (settings.edges) buildEdges()
  requestRender()
}

function toggleEdges() {
  settings.edges = !settings.edges
  rebuildEdges()
  store()
  updateToolbar()
}

// --- Kamera ---

const directions = {
  iso: new Vector3(0.346, -0.742, 0.574),
  top: new Vector3(0, -0.0001, 1),
  front: new Vector3(0, -1, 0),
  right: new Vector3(1, 0, 0),
  back: new Vector3(0, 1, 0),
  left: new Vector3(-1, 0, 0),
}

function fit(direction, animate = true) {
  const sphere = hasModel ? bounds.getBoundingSphere(new Sphere()) : new Sphere(new Vector3(), 50)
  const radius = Math.max(sphere.radius, 0.5)
  // Der engere der beiden Blickwinkel entscheidet (hohe, schmale Vorschau neben dem Editor)
  const vertical = (perspective.fov * Math.PI) / 360
  const horizontal = Math.atan(Math.tan(vertical) * perspective.aspect)
  const distance = (radius / Math.sin(Math.min(vertical, horizontal))) * 1.08
  const dir = (direction ? directions[direction] : camera.position.clone().sub(controls.target)).clone().normalize()
  const target = sphere.center.clone()
  const position = target.clone().add(dir.multiplyScalar(distance))
  perspective.near = Math.max(radius / 500, 0.01)
  perspective.far = Math.max(radius * 200, 1000)
  perspective.updateProjectionMatrix()
  moveCamera(position, target, animate)
}

let animation = null
function moveCamera(position, target, animate) {
  if (animation) cancelAnimationFrame(animation.frame)
  const zoomFrom = orthographic.zoom
  if (!animate) {
    for (const cam of [perspective, orthographic]) cam.position.copy(position)
    orthographic.zoom = 1
    orthographic.updateProjectionMatrix()
    controls.target.copy(target)
    controls.update()
    updateOrthoFrustum()
    requestRender()
    return
  }
  const fromTarget = controls.target.clone()
  const fromOffset = camera.position.clone().sub(fromTarget)
  const toOffset = position.clone().sub(target)
  const fromLength = fromOffset.length()
  const toLength = toOffset.length()
  fromOffset.normalize()
  toOffset.normalize()
  const start = performance.now()
  const duration = 260
  const step = (now) => {
    const t = Math.min(1, (now - start) / duration)
    const ease = 1 - (1 - t) ** 3
    const offset = fromOffset.clone().lerp(toOffset, ease)
    if (offset.lengthSq() < 1e-8) offset.copy(toOffset)
    offset.normalize().multiplyScalar(fromLength + (toLength - fromLength) * ease)
    controls.target.copy(fromTarget).lerp(target, ease)
    for (const cam of [perspective, orthographic]) cam.position.copy(controls.target).add(offset)
    orthographic.zoom = zoomFrom + (1 - zoomFrom) * ease
    orthographic.updateProjectionMatrix()
    controls.update()
    updateOrthoFrustum()
    requestRender()
    if (t < 1) animation = { frame: requestAnimationFrame(step) }
    else animation = null
  }
  animation = { frame: requestAnimationFrame(step) }
}

function toggleOrtho() {
  settings.ortho = !settings.ortho
  const offset = camera.position.clone().sub(controls.target)
  if (settings.ortho) {
    orthographic.position.copy(perspective.position)
    orthographic.zoom = 1
    camera = orthographic
    updateOrthoFrustum()
  } else {
    // Im Ortho-Modus zoomt OrbitControls über camera.zoom - beim Zurückwechseln in den Abstand umrechnen
    perspective.position.copy(controls.target).add(offset.multiplyScalar(1 / orthographic.zoom))
    camera = perspective
  }
  camera.add(headlight, headlight.target)
  controls.object = camera
  controls.update()
  store()
  updateToolbar()
  requestRender()
}

// --- Werkzeugleiste ---

const icons = {
  fit: '<path d="M2.5 6V3.5a1 1 0 0 1 1-1H6M10 2.5h2.5a1 1 0 0 1 1 1V6M13.5 10v2.5a1 1 0 0 1-1 1H10M6 13.5H3.5a1 1 0 0 1-1-1V10"/><rect x="5.5" y="5.5" width="5" height="5" rx="0.5"/>',
  iso: '<path d="M8 1.8 13.5 5v6.2L8 14.3 2.5 11.2V5z"/><path d="M2.5 5 8 8.2 13.5 5M8 8.2v6.1"/>',
  top: '<path d="M8 1.8 13.5 5v6.2L8 14.3 2.5 11.2V5z" opacity=".45"/><path d="M8 1.8 13.5 5 8 8.2 2.5 5z" fill="currentColor" fill-opacity=".35"/>',
  front: '<path d="M8 1.8 13.5 5v6.2L8 14.3 2.5 11.2V5z" opacity=".45"/><path d="M2.5 5 8 8.2v6.1l-5.5-3.1z" fill="currentColor" fill-opacity=".35"/>',
  right: '<path d="M8 1.8 13.5 5v6.2L8 14.3 2.5 11.2V5z" opacity=".45"/><path d="M13.5 5 8 8.2v6.1l5.5-3.1z" fill="currentColor" fill-opacity=".35"/>',
  edges: '<path d="M8 1.8 13.5 5v6.2L8 14.3 2.5 11.2V5z"/><path d="M2.5 5 8 8.2 13.5 5M8 8.2v6.1"/><circle cx="8" cy="8.2" r="1.2" fill="currentColor"/><circle cx="2.5" cy="5" r="1.2" fill="currentColor"/><circle cx="13.5" cy="5" r="1.2" fill="currentColor"/>',
  ortho: '<path d="M3 4.5h10M3 11.5h10M4.5 4.5v7M11.5 4.5v7"/>',
  grid: '<path d="M2.5 2.5h11v11h-11zM2.5 6.2h11M2.5 9.8h11M6.2 2.5v11M9.8 2.5v11"/>',
  render: '<path d="M13 8a5 5 0 1 1-1.5-3.6"/><path d="M13.2 2.5v2.8h-2.8"/>',
  stop: '<rect x="4" y="4" width="8" height="8" rx="1" fill="currentColor" fill-opacity=".35"/>',
  customizer: '<path d="M2.5 4h1M6.5 4h7M2.5 8h6.25M11.75 8h1.75M2.5 12h2.75M8.25 12h5.25"/><circle cx="5" cy="4" r="1.5"/><circle cx="10.25" cy="8" r="1.5"/><circle cx="6.75" cy="12" r="1.5"/>',
  colors: '<circle cx="5.9" cy="6" r="3.4"/><circle cx="10.1" cy="6" r="3.4"/><circle cx="8" cy="9.8" r="3.4"/>',
}

// Knöpfe, die das Plugin nur in der Vorschau von .scad-Dateien zeigt (setTools)
let tools = null

const viewButtons = [
  ['fit', () => fit(null)],
  'sep',
  ['iso', () => fit('iso')],
  ['top', () => fit('top')],
  ['front', () => fit('front')],
  ['right', () => fit('right')],
  'sep',
  ['colors', toggleColors, () => settings.colors, () => modelHasColors],
  ['edges', toggleEdges, () => settings.edges],
  ['ortho', toggleOrtho, () => settings.ortho],
  ['grid', () => { settings.grid = !settings.grid; store(); updateGrid(); updateToolbar() }, () => settings.grid],
]

let buttons = viewButtons

function buildToolbar() {
  buttons = tools ? [
    ...viewButtons,
    'sep',
    ['render', () => host.send({ type: 'action', action: 'render' })],
    ['customizer', () => host.send({ type: 'action', action: 'customizer' }), () => tools.customizer, () => tools.customizerEnabled],
  ] : viewButtons
  const bar = $('toolbar')
  bar.textContent = ''
  for (const entry of buttons) {
    if (entry === 'sep') {
      bar.appendChild(Object.assign(document.createElement('span'), { className: 'sep' }))
      continue
    }
    const [name, action] = entry
    const button = document.createElement('button')
    button.type = 'button'
    button.dataset.name = name
    button.title = labels[`tool.${name}`] || name
    button.innerHTML = `<svg data-icon="${name}" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.2" stroke-linejoin="round" stroke-linecap="round">${icons[name]}</svg>`
    button.addEventListener('click', action)
    bar.appendChild(button)
  }
  updateToolbar()
}

function updateToolbar() {
  for (const entry of buttons) {
    if (entry === 'sep' || !entry[2]) continue
    const [name, , isOn, isEnabled] = entry
    const button = document.querySelector(`.toolbar button[data-name="${name}"]`)
    if (!button) continue
    // Nicht verfügbar (z. B. Farben bei einer STL): Knopf bleibt an seinem Platz, nur ausgegraut
    const enabled = !isEnabled || isEnabled()
    button.disabled = !enabled
    button.classList.toggle('on', enabled && isOn())
    const label = labels[`tool.${name}`] || name
    button.title = enabled ? label : labels[`tool.${name}.none`] || label
  }
  // Neu rechnen wird zu Abbrechen, solange OpenSCAD rechnet
  const render = document.querySelector('.toolbar button[data-name="render"]')
  if (render && tools) {
    const icon = tools.rendering ? 'stop' : 'render'
    if (render.firstElementChild?.dataset.icon !== icon) {
      render.innerHTML = `<svg data-icon="${icon}" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.2" stroke-linejoin="round" stroke-linecap="round">${icons[icon]}</svg>`
    }
    render.title = labels[tools.rendering ? 'tool.stop' : 'tool.render'] || icon
  }
}

/** Knöpfe der Vorschau ein- bzw. umschalten (siehe ModelViewer.setTools). */
function setTools(next) {
  const first = !tools
  tools = next
  if (first) buildToolbar()
  else updateToolbar()
}

// Werkzeugleiste blendet sich ein, sobald die Maus in die Nähe kommt.
document.addEventListener('pointermove', (event) => {
  const bar = $('toolbar').getBoundingClientRect()
  const near = event.clientX > bar.left - 60 && event.clientY < bar.bottom + 60
  $('toolbar').classList.toggle('near', near)
}, { passive: true })

// --- Status, leere Ansicht, Meldungen ---

let statusTimer = null
function setStatus(text) {
  clearInterval(statusTimer)
  const status = $('status')
  if (!text) {
    status.hidden = true
    return
  }
  const started = performance.now()
  const show = () => {
    const seconds = Math.floor((performance.now() - started) / 1000)
    $('status-text').textContent = seconds >= 2 ? `${text} ${seconds} s` : text
  }
  show()
  statusTimer = setInterval(show, 1000)
  status.hidden = false
}

/** Hinweis statt Modell, z. B. wenn OpenSCAD fehlt; `link` löst eine Aktion im Plugin aus. */
function showEmpty({ text, link, keepModel }) {
  if (!keepModel) {
    clearModel()
    hasModel = false
    bounds = null
    updateInfo()
    updateGrid()
  }
  const empty = $('empty')
  empty.textContent = ''
  const box = document.createElement('div')
  box.textContent = text
  if (link) {
    box.appendChild(document.createElement('br'))
    const anchor = document.createElement('a')
    anchor.textContent = link.label
    anchor.addEventListener('click', () => host.send({ type: 'action', action: link.action }))
    box.appendChild(anchor)
  }
  empty.appendChild(box)
  empty.hidden = false
  requestRender()
}

function showError(text) {
  showEmpty({ text, keepModel: false })
}

function setMessages(list) {
  const box = $('messages')
  const items = $('messages-list')
  items.textContent = ''
  if (!list || list.length === 0) {
    box.hidden = true
    return
  }
  const counts = { error: 0, warning: 0, echo: 0 }
  for (const message of list) {
    if (message.level in counts) counts[message.level]++
    const item = document.createElement('li')
    item.className = message.level
    item.appendChild(document.createTextNode(message.text))
    if (message.line) {
      item.appendChild(document.createTextNode(' '))
      const where = document.createElement('span')
      where.className = 'where'
      where.textContent = message.where
      where.addEventListener('click', () => host.send({ type: 'navigate', file: message.file || null, line: message.line }))
      item.appendChild(where)
    }
    items.appendChild(item)
  }
  const summary = $('messages-summary')
  summary.textContent = ''
  for (const level of ['error', 'warning', 'echo']) {
    if (!counts[level]) continue
    const span = document.createElement('span')
    span.className = level
    // Beschriftung als „Einzahl|Mehrzahl“
    const [one, other = one] = (labels[`messages.${level}`] || level).split('|')
    span.textContent = `${counts[level]} ${counts[level] === 1 ? one : other}`
    summary.appendChild(span)
  }
  // Fehler gleich zeigen, Echo-Ausgaben nur auf Klick
  box.classList.toggle('open', counts.error > 0 || box.classList.contains('open'))
  box.hidden = false
}

$('messages-summary').addEventListener('click', () => $('messages').classList.toggle('open'))

// --- Theme ---

function setTheme(next) {
  theme = { ...theme, ...next }
  const root = document.documentElement.style
  for (const key of ['bg', 'fg', 'muted', 'panel', 'border', 'hover', 'active', 'error', 'warning']) {
    if (next[key]) root.setProperty(`--${key}`, next[key])
  }
  // Systemschrift des Mac heißt in Java „.AppleSystemUIFont“ - die kennt CSS nicht, dann bleibt system-ui
  if (next.font && !next.font.startsWith('.')) root.setProperty('--ide-font', `"${next.font}"`)
  if (next.fontSize) root.setProperty('--font-size', `${next.fontSize}px`)
  scene.background = new Color(theme.bg || '#f7f8fa')
  model?.traverse((child) => {
    if (!child.isMesh) return
    const materials = Array.isArray(child.material) ? child.material : [child.material]
    for (const material of materials) if (material.userData.themeColor) material.color.set(theme.model)
  })
  if (!settings.colors) applyColorMode()
  if (edges) rebuildEdges()
  updateGrid()
  requestRender()
}

// --- Gespeicherte Ansichtsoptionen (je Viewer-Seite, per localStorage des eingebetteten Browsers) ---

function readStored() {
  try {
    return JSON.parse(localStorage.getItem('scadsphere.settings') || '{}')
  } catch {
    return {}
  }
}

function store() {
  try {
    localStorage.setItem('scadsphere.settings', JSON.stringify({ colors: settings.colors, edges: settings.edges, grid: settings.grid, ortho: settings.ortho }))
  } catch {
    // ohne Speicher eben nur für diese Sitzung
  }
}

// --- Bild für Screenshots/Export ---

function snapshot() {
  renderer.render(scene, camera)
  return renderer.domElement.toDataURL('image/png')
}

/** Zustand für Tests über die DevTools-Schnittstelle. */
function state() {
  return {
    camera: camera.position.toArray(),
    target: controls.target.toArray(),
    bounds: bounds ? [bounds.min.toArray(), bounds.max.toArray()] : null,
    size: [view.clientWidth, view.clientHeight, window.devicePixelRatio],
    ortho: settings.ortho,
    colors: settings.colors,
    modelHasColors,
  }
}

/** Meldungsliste aufklappen (sonst nur bei Fehlern von selbst). */
function expandMessages() {
  $('messages').classList.add('open')
}

window.scadSphere = { load, setTools, setTheme, setStatus, showEmpty, setMessages, expandMessages, fit, snapshot, state }

if (init.theme) setTheme(init.theme)
else scene.background = new Color('#f7f8fa')
buildToolbar()
resize()
updateGrid()
host.send({ type: 'ready' })
