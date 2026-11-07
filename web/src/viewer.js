// 3D-Viewer für .stl, .3mf, .obj, .off, .amf und die Vorschau von .scad-Dateien. Läuft im eingebetteten Chromium (JCEF)
// der IDE; das Plugin steuert ihn über window.scadSphere, Rückmeldungen gehen per window.scadSphereHost.send.
import './passiveWheel.js'
import {
  AmbientLight, BackSide, Box3, BufferGeometry, Color, DirectionalLight, DoubleSide, EdgesGeometry, Float32BufferAttribute,
  FrontSide, GridHelper, Group, Line, LineBasicMaterial, LineSegments, Mesh, MeshBasicMaterial, MeshPhongMaterial, Object3D,
  OrthographicCamera, PerspectiveCamera, Plane, PlaneGeometry, Raycaster, Scene, ShapeGeometry, ShapePath, Sphere, SphereGeometry,
  Vector2, Vector3, WebGLRenderer,
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
  bed: true,
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
  window.scadSphere = { load: noop, setTools: noop, setTheme: noop, setStatus: noop, showEmpty: noop, setMessages: noop, expandMessages: noop, fit: noop, snapshot: noop, state: noop, setBed: noop, setAnimation: noop }
  host.send({ type: 'error', message: String(error) })
  host.send({ type: 'ready' })
  throw error
}
renderer.setPixelRatio(window.devicePixelRatio || 1)
renderer.localClippingEnabled = true
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
let overlay = null      // Teile mit # (hervorgehoben) und % (Hintergrund), nicht Teil des Modells

// --- Zeichnen bei Bedarf (kein Dauer-Loop, die IDE soll ruhig bleiben) ---

let renderPending = false
function requestRender() {
  if (renderPending) return
  renderPending = true
  requestAnimationFrame(() => {
    renderPending = false
    renderer.render(scene, camera)
    updateMeasureLabel()
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

async function load({ url, format, keepCamera, overlayUrl }) {
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
  let overlayObject = null
  if (overlayUrl) {
    try {
      const overlayResponse = await fetch(overlayUrl, { cache: 'no-store' })
      if (overlayResponse.ok) overlayObject = styleOverlay(new ThreeMFLoader().parse(await overlayResponse.arrayBuffer()))
    } catch {
      // ohne Hervorhebungen weiter
    }
    if (id !== loadId) return
  }
  setModel(object, keepCamera && hasModel)
  if (overlayObject) {
    overlay = overlayObject
    scene.add(overlay)
    applySection()
    requestRender()
  }
}

/**
 * # und %-Teile (das Plugin rechnet sie getrennt und färbt # rot, % grau): durchsichtig wie in OpenSCAD.
 * Sie zählen nicht zur Größe, zum Druckbett oder zum Messen.
 */
function styleOverlay(group) {
  group.traverse((child) => {
    if (!child.isMesh) return
    const materials = Array.isArray(child.material) ? child.material : [child.material]
    child.material = materials.map((material) => {
      const highlight = material.color.r > material.color.g + 0.2
      const style = new MeshPhongMaterial({
        color: highlight ? '#ff5a78' : '#9a9a9a',
        transparent: true,
        opacity: highlight ? 0.45 : 0.22,
        depthWrite: false,
        side: DoubleSide,
        flatShading: true,
      })
      style.userData.overlay = true
      material.dispose()
      return style
    })
    if (child.material.length === 1) child.material = child.material[0]
    child.renderOrder = 2
  })
  return group
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
  updateBed()
  if (settings.edges) buildEdges()
  updateInfo()
  clearMeasure()
  updateSectionRange()
  applySection()
  if (!keepCamera) fit('iso', false)
  updateOrthoFrustum()
  requestRender()
}

function clearModel() {
  removeCaps()
  if (overlay) {
    scene.remove(overlay)
    overlay.traverse((child) => {
      child.geometry?.dispose()
      const materials = Array.isArray(child.material) ? child.material : child.material ? [child.material] : []
      for (const material of materials) material.dispose()
    })
    overlay = null
  }
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
  const problem = bedProblem()
  if (problem) {
    const warning = document.createElement('span')
    warning.className = 'bed-warning'
    warning.textContent = ` · ${problem}`
    info.appendChild(warning)
  }
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
  if (bedShown()) {
    const area = bedArea()
    extent = Math.max(extent, Math.abs(area.min.x) * 2, Math.abs(area.max.x) * 2, Math.abs(area.min.y) * 2, Math.abs(area.max.y) * 2)
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
  if (section.on) material.clippingPlanes = [clipPlane]
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
  bottom: new Vector3(0, -0.0001, -1),
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
  bottom: '<path d="M8 1.8 13.5 5v6.2L8 14.3 2.5 11.2V5z" opacity=".45"/><path d="M2.5 11.2 8 8.1l5.5 3.1L8 14.3z" fill="currentColor" fill-opacity=".35"/>',
  left: '<path d="M8 1.8 13.5 5v6.2L8 14.3 2.5 11.2V5z" opacity=".45"/><path d="M2.5 5 8 1.8v6.4l-5.5 3z" fill="currentColor" fill-opacity=".35"/>',
  back: '<path d="M8 1.8 13.5 5v6.2L8 14.3 2.5 11.2V5z" opacity=".45"/><path d="M8 1.8 13.5 5v6.2L8 8.2z" fill="currentColor" fill-opacity=".35"/>',
  edges: '<path d="M8 1.8 13.5 5v6.2L8 14.3 2.5 11.2V5z"/><path d="M2.5 5 8 8.2 13.5 5M8 8.2v6.1"/><circle cx="8" cy="8.2" r="1.2" fill="currentColor"/><circle cx="2.5" cy="5" r="1.2" fill="currentColor"/><circle cx="13.5" cy="5" r="1.2" fill="currentColor"/>',
  ortho: '<path d="M3 4.5h10M3 11.5h10M4.5 4.5v7M11.5 4.5v7"/>',
  grid: '<path d="M2.5 2.5h11v11h-11zM2.5 6.2h11M2.5 9.8h11M6.2 2.5v11M9.8 2.5v11"/>',
  render: '<path d="M13 8a5 5 0 1 1-1.5-3.6"/><path d="M13.2 2.5v2.8h-2.8"/>',
  stop: '<rect x="4" y="4" width="8" height="8" rx="1" fill="currentColor" fill-opacity=".35"/>',
  customizer: '<path d="M2.5 4h1M6.5 4h7M2.5 8h6.25M11.75 8h1.75M2.5 12h2.75M8.25 12h5.25"/><circle cx="5" cy="4" r="1.5"/><circle cx="10.25" cy="8" r="1.5"/><circle cx="6.75" cy="12" r="1.5"/>',
  bed: '<path d="M1.5 10.5 5 6.5h9.5L11 10.5z"/><path d="M1.5 10.5v1.5H11v-1.5M11 12l3.5-4V6.5"/>',
  measure: '<path d="M2 11.5 11.5 2l2.5 2.5L4.5 14z"/><path d="M5 8.5l1.2 1.2M7 6.5l1.7 1.7M9 4.5l1.2 1.2"/>',
  section: '<path d="M8 1.8 13.5 5v6.2L8 14.3 2.5 11.2V5z" opacity=".45"/><path d="M1 8.5h14" /><path d="M2.5 8.5 8 11.6l5.5-3.1L8 5.4z" fill="currentColor" fill-opacity=".35"/>',
  display: '<path d="M1.5 8s2.4-4.5 6.5-4.5S14.5 8 14.5 8s-2.4 4.5-6.5 4.5S1.5 8 1.5 8z"/><circle cx="8" cy="8" r="2"/>',
  more: '<path d="M4 4.5 7.5 8 4 11.5M8.5 4.5 12 8l-3.5 3.5"/>',
  image: '<path d="M2 5.5a1 1 0 0 1 1-1h2l1-1.5h4l1 1.5h2a1 1 0 0 1 1 1v6.5a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1z"/><circle cx="8" cy="8.5" r="2.4"/>',
  colors: '<circle cx="5.9" cy="6" r="3.4"/><circle cx="10.1" cy="6" r="3.4"/><circle cx="8" cy="9.8" r="3.4"/>',
}

// Knöpfe, die das Plugin nur in der Vorschau von .scad-Dateien zeigt (setTools)
let tools = null

// Einträge der Leiste. `toggle`: an/aus (isOn), `enabled`: sonst ausgegraut. Gruppen (`items`) öffnen beim
// Darüberfahren ein Menü. `keep`: Rang beim Platzmangel - kleinere Werte wandern zuerst ins »-Menü.
const viewItems = [
  { name: 'iso', action: () => fit('iso') },
  { name: 'top', action: () => fit('top') },
  { name: 'bottom', action: () => fit('bottom') },
  { name: 'front', action: () => fit('front') },
  { name: 'right', action: () => fit('right') },
  { name: 'left', action: () => fit('left') },
  { name: 'back', action: () => fit('back') },
]
const displayItems = [
  { name: 'colors', action: toggleColors, isOn: () => settings.colors, enabled: () => modelHasColors },
  { name: 'edges', action: toggleEdges, isOn: () => settings.edges },
  { name: 'ortho', action: toggleOrtho, isOn: () => settings.ortho },
  { name: 'grid', action: () => { settings.grid = !settings.grid; store(); updateGrid(); updateToolbar() }, isOn: () => settings.grid },
  { name: 'bed', action: toggleBed, isOn: () => settings.bed, enabled: () => !!bed?.enabled },
]

function toolbarEntries() {
  return [
    { name: 'fit', action: () => fit(null), keep: 9 },
    { name: 'views', icon: 'iso', items: viewItems, keep: 8 },
    { name: 'display', items: displayItems, keep: 4 },
    'sep',
    // Beim Abspielen kommt alle paar Millisekunden ein neues Modell - Messpunkte hielten nicht
    { name: 'measure', action: toggleMeasure, isOn: () => measure.on, enabled: () => hasModel && !timeAnimation?.playing,
      disabledTip: () => timeAnimation?.playing ? 'tool.measure.playing' : null, keep: 3 },
    { name: 'section', action: toggleSection, isOn: () => section.on, enabled: () => hasModel, keep: 2 },
    { name: 'image', action: () => host.send({ type: 'image', data: snapshot() }), enabled: () => hasModel, keep: 0 },
    ...(tools ? [
      'sep',
      { name: 'render', action: () => host.send({ type: 'action', action: 'render' }), keep: 7 },
      { name: 'customizer', action: () => host.send({ type: 'action', action: 'customizer' }),
        isOn: () => tools.customizer, enabled: () => tools.customizerEnabled, keep: 1 },
    ] : []),
  ]
}

let entries = []
let overflow = new Set()   // Namen der Einträge im »-Menü

const svg = (icon) =>
  `<svg data-icon="${icon}" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.2" stroke-linejoin="round" stroke-linecap="round">${icons[icon] || ''}</svg>`

const label = (name) => labels[`tool.${name}`] || name

function isEnabled(entry) {
  return !entry.enabled || entry.enabled()
}

function iconOf(entry) {
  if (entry.name === 'render' && tools?.rendering) return 'stop'
  return entry.icon || entry.name
}

function buildToolbar() {
  entries = toolbarEntries()
  layoutToolbar()
}

/** Leiste aufbauen; passt sie nicht in die Breite, wandern Einträge mit kleinem `keep` ins »-Menü. */
function layoutToolbar() {
  closeMenu()
  overflow = new Set()
  renderBar()
  const bar = $('toolbar')
  const available = Math.max(view.clientWidth - 16, 120)
  const ranked = entries.filter((e) => e !== 'sep').sort((a, b) => a.keep - b.keep)
  for (const entry of ranked) {
    if (bar.offsetWidth <= available) break
    overflow.add(entry.name)
    renderBar()
  }
}

function renderBar() {
  const bar = $('toolbar')
  bar.textContent = ''
  let lastWasSep = true
  for (const entry of entries) {
    if (entry === 'sep') {
      if (!lastWasSep) bar.appendChild(Object.assign(document.createElement('span'), { className: 'sep' }))
      lastWasSep = true
      continue
    }
    if (overflow.has(entry.name)) continue
    bar.appendChild(toolButton(entry))
    lastWasSep = false
  }
  if (bar.lastChild?.className === 'sep') bar.lastChild.remove()
  if (overflow.size > 0) {
    bar.appendChild(toolButton({ name: 'more', items: () => entries.filter((e) => e !== 'sep' && overflow.has(e.name)) }))
  }
  updateToolbar()
}

function toolButton(entry) {
  const button = document.createElement('button')
  button.type = 'button'
  button.dataset.name = entry.name
  button.dataset.tip = label(entry.name)
  button.innerHTML = svg(iconOf(entry))
  if (entry.items) {
    // Nur Darüberfahren öffnet (trackHover), ein Klick tut nichts
    button.classList.add('group')
    button.menuEntry = entry
  } else {
    button.addEventListener('click', () => { closeMenu(); entry.action() })
  }
  return button
}

function updateToolbar() {
  for (const entry of entries) {
    if (entry === 'sep') continue
    const button = document.querySelector(`.toolbar button[data-name="${entry.name}"]`)
    if (!button) continue
    // Nicht verfügbar (z. B. Farben bei einer STL): Knopf bleibt an seinem Platz, nur ausgegraut
    const enabled = isEnabled(entry)
    button.disabled = !enabled
    button.classList.toggle('on', enabled && !!entry.isOn?.())
    button.dataset.tip = entry.name === 'render' && tools?.rendering ? label('stop')
      : enabled ? label(entry.name) : labels[entry.disabledTip?.() || `tool.${entry.name}.none`] || label(entry.name)
    const icon = iconOf(entry)
    if (button.firstElementChild?.dataset.icon !== icon) button.innerHTML = svg(icon)
  }
  // Nur neu aufbauen, wenn sich etwas geändert hat: Beim Abspielen einer Animation kommt jedes Bild hier vorbei, ein
  // ständig ersetztes Menü verschluckt sonst Klicks (Element zwischen Drücken und Loslassen ausgetauscht)
  if (openMenu && menuSignature(openMenu) !== openMenu.signature) fillMenu(openMenu)
}

function menuSignature(menu) {
  const items = typeof menu.entry.items === 'function' ? menu.entry.items() : menu.entry.items
  const flat = items.flatMap((item) => item.items ? [item, ...item.items] : [item])
  return flat.map((item) => `${item.name}:${isEnabled(item)}:${!!item.isOn?.()}:${iconOf(item)}`).join('|')
}

// --- Menüs der Gruppen (Darüberfahren öffnet, Wegfahren schließt nach kurzer Pause) ---
// Ausgewertet wird bei jeder Mausbewegung, was unter dem Zeiger liegt - nicht pointerenter/-leave der Knöpfe: Im
// eingebetteten Browser der IDE kommen diese bei benachbarten Knöpfen teils vertauscht an, dann blieb das alte Menü
// offen und beim Zurückfahren öffnete sich das des Nachbarn.

let openMenu = null   // { button, entry, element }
let closeTimer = null
let hoverTimer = null
let hoverButton = null // Knopf, dessen Menü gleich aufgeht

function cancelHover() {
  clearTimeout(hoverTimer)
  hoverTimer = null
  hoverButton = null
}

function cancelClose() {
  clearTimeout(closeTimer)
  closeTimer = null
}

function trackHover(target) {
  if (openMenu?.element.contains(target)) {
    cancelHover()
    cancelClose()
    return
  }
  const button = target?.closest?.('.toolbar button.group')
  if (button && !button.disabled) {
    cancelClose()
    if (openMenu?.button === button) { cancelHover(); return }
    if (hoverButton === button) return
    cancelHover()
    hoverButton = button
    hoverTimer = setTimeout(() => { hoverButton = null; showMenu(button, button.menuEntry) }, openMenu ? 0 : 120)
    return
  }
  cancelHover()
  if (openMenu && closeTimer === null) closeTimer = setTimeout(closeMenu, 250)
}

document.addEventListener('pointermove', (event) => trackHover(event.target), { passive: true })
document.documentElement.addEventListener('pointerleave', () => trackHover(null))

function showMenu(button, entry) {
  closeMenu()
  const element = document.createElement('div')
  element.className = 'menu'
  document.body.appendChild(element)
  openMenu = { button, entry, element }
  button.classList.add('open')
  hideTip()
  fillMenu(openMenu)
  const rect = button.getBoundingClientRect()
  const width = element.offsetWidth
  element.style.top = `${rect.bottom + 4}px`
  element.style.left = `${Math.max(8, Math.min(rect.right - width, window.innerWidth - width - 8))}px`
}

function fillMenu(menu) {
  menu.signature = menuSignature(menu)
  const items = typeof menu.entry.items === 'function' ? menu.entry.items() : menu.entry.items
  menu.element.textContent = ''
  // Überschrift statt Tooltip: Name der Gruppe (im »-Menü haben die Gruppen eigene Überschriften)
  if (menu.entry.name !== 'more') {
    const title = document.createElement('div')
    title.className = 'menu-heading'
    title.textContent = label(menu.entry.name)
    menu.element.appendChild(title)
  }
  items.forEach((item, index) => {
    if (item.items) {
      // Gruppe im »-Menü: Überschrift und ihre Einträge
      const heading = document.createElement('div')
      heading.className = 'menu-heading'
      if (index > 0) heading.classList.add('gap')
      heading.textContent = label(item.name)
      menu.element.appendChild(heading)
      for (const child of item.items) menu.element.appendChild(menuRow(child))
    } else {
      menu.element.appendChild(menuRow(item))
    }
  })
}

function menuRow(item) {
  const row = document.createElement('button')
  row.type = 'button'
  row.className = 'menu-item'
  const enabled = isEnabled(item)
  row.disabled = !enabled
  const check = item.isOn ? `<span class="check">${enabled && item.isOn() ? '✓' : ''}</span>` : '<span class="check"></span>'
  row.innerHTML = `${check}${svg(iconOf(item))}<span class="text"></span>`
  row.querySelector('.text').textContent = enabled ? label(item.name) : labels[item.disabledTip?.() || `tool.${item.name}.none`] || label(item.name)
  row.addEventListener('click', () => {
    item.action()
    // Menü bleibt offen: mehrere Ansichten oder Umschalter nacheinander; zu geht es beim Wegfahren, Esc oder Klick daneben
    updateToolbar()
  })
  return row
}

function closeMenu() {
  cancelClose()
  cancelHover()
  if (!openMenu) return
  openMenu.element.remove()
  openMenu.button.classList.remove('open')
  openMenu = null
}

document.addEventListener('pointerdown', (event) => {
  if (openMenu && !openMenu.element.contains(event.target) && !openMenu.button.contains(event.target)) closeMenu()
})
document.addEventListener('keydown', (event) => { if (event.key === 'Escape') closeMenu() })
new ResizeObserver(() => { if (entries.length) layoutToolbar() }).observe(view)

// --- Tooltips: Der eingebettete Browser der IDE zeigt `title` nicht an, deshalb eigene (data-tip) ---

const tooltip = Object.assign(document.createElement('div'), { className: 'tooltip', hidden: true })
document.body.appendChild(tooltip)
let tipTimer = null
let tipTarget = null

document.addEventListener('pointerover', (event) => {
  const target = event.target.closest?.('[data-tip]')
  if (target === tipTarget) return
  hideTip()
  if (!target) return
  tipTarget = target
  // Gruppen öffnen beim Darüberfahren ihr Menü (mit Überschrift) - ein Tooltip davor würde nur kurz aufblitzen
  if (target.classList.contains('group')) return
  // Kurz warten wie bei IDE-Tooltips; ist schon einer zu sehen, sofort wechseln
  tipTimer = setTimeout(() => showTip(target), tooltip.dataset.recent ? 0 : 450)
})
document.addEventListener('pointerdown', hideTip, true)

function showTip(target) {
  // Über einer Gruppe mit offenem Menü stört der Tooltip
  if (!target.isConnected || !target.dataset.tip || openMenu?.button === target) return
  tooltip.textContent = target.dataset.tip
  tooltip.hidden = false
  const rect = target.getBoundingClientRect()
  const width = tooltip.offsetWidth
  tooltip.style.left = `${Math.max(4, Math.min(rect.left + rect.width / 2 - width / 2, window.innerWidth - width - 4))}px`
  tooltip.style.top = `${rect.bottom + 6}px`
  tooltip.dataset.recent = '1'
}

function hideTip() {
  clearTimeout(tipTimer)
  tipTarget = null
  if (!tooltip.hidden) {
    tooltip.hidden = true
    setTimeout(() => { if (tooltip.hidden) delete tooltip.dataset.recent }, 400)
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


// --- Druckbett (Größe aus den Einstellungen der IDE) ---

let bed = init.bed || null   // { enabled, width, depth, height, center }
let bedObject = null

function bedShown() {
  return !!bed?.enabled && settings.bed
}

/**
 * Fläche des Betts. Mitte: Der Slicer legt das Modell selbst aufs Bett und mittig hin - das Bett liegt deshalb unter
 * dem tiefsten Punkt, mittig unter dem Modell. Ecke vorne links: Position bleibt, das Bett liegt ab dem Ursprung auf z = 0.
 */
function bedArea() {
  let min = new Vector3(0, 0, 0)
  if (bed.center) {
    const base = hasModel && bounds ? new Vector3((bounds.min.x + bounds.max.x) / 2, (bounds.min.y + bounds.max.y) / 2, bounds.min.z) : new Vector3()
    min = base.sub(new Vector3(bed.width / 2, bed.depth / 2, 0))
  }
  return new Box3(min, min.clone().add(new Vector3(bed.width, bed.depth, bed.height)))
}

/** Passt das Modell nicht aufs Bett, ein kurzer Satz dazu; sonst null. */
function bedProblem() {
  if (!bedShown() || !hasModel) return null
  const size = bounds.getSize(new Vector3())
  const eps = 1e-6
  const number = (value) => value.toLocaleString(init.locale || undefined, { maximumFractionDigits: 1 })
  const bedText = `${number(bed.width)} × ${number(bed.depth)} × ${number(bed.height)} mm`
  if (size.x > bed.width + eps || size.y > bed.depth + eps || size.z > bed.height + eps) {
    return (labels['bed.tooLarge'] || 'Larger than the print bed ({0})').replace('{0}', bedText)
  }
  // Mitte: der Slicer legt das Modell selbst aufs Bett, nur die Größe zählt
  if (bed.center) return null
  const area = bedArea()
  if (bounds.min.x < area.min.x - eps || bounds.max.x > area.max.x + eps || bounds.min.y < area.min.y - eps ||
    bounds.max.y > area.max.y + eps || bounds.max.z > area.max.z + eps) {
    return labels['bed.outside'] || 'Partly outside the print bed'
  }
  if (bounds.min.z < -eps) return labels['bed.below'] || 'Partly below the print bed (z < 0)'
  return null
}

function updateBed() {
  if (bedObject) {
    scene.remove(bedObject)
    bedObject.traverse((child) => { child.geometry?.dispose(); child.material?.dispose() })
    bedObject = null
  }
  if (bedShown()) {
    const area = bedArea()
    const problem = !!bedProblem()
    const color = problem ? (theme.warning || '#c27d04') : (theme.active || '#3574f0')
    bedObject = new Group()
    const z = area.min.z - 0.002 * Math.max(bed.width, bed.depth)
    const plate = new Mesh(
      new PlaneGeometry(bed.width, bed.depth),
      new MeshBasicMaterial({ color, transparent: true, opacity: theme.dark ? 0.08 : 0.06, depthWrite: false, side: DoubleSide }),
    )
    plate.position.set((area.min.x + area.max.x) / 2, (area.min.y + area.max.y) / 2, z)
    plate.renderOrder = -2
    const outline = (height, opacity) => {
      const points = [[area.min.x, area.min.y], [area.max.x, area.min.y], [area.max.x, area.max.y], [area.min.x, area.max.y], [area.min.x, area.min.y]]
      const geometry = new BufferGeometry().setFromPoints(points.map(([x, y]) => new Vector3(x, y, height)))
      return new Line(geometry, new LineBasicMaterial({ color, transparent: true, opacity }))
    }
    bedObject.add(plate, outline(z, 0.9))
    // Bauraum nur andeuten, wenn das Modell zu hoch ist
    if (problem && hasModel && bounds.max.z > area.max.z) {
      bedObject.add(outline(area.max.z, 0.6))
      const corners = []
      for (const x of [area.min.x, area.max.x]) for (const y of [area.min.y, area.max.y]) corners.push(new Vector3(x, y, z), new Vector3(x, y, area.max.z))
      bedObject.add(new LineSegments(new BufferGeometry().setFromPoints(corners), new LineBasicMaterial({ color, transparent: true, opacity: 0.35 })))
    }
    scene.add(bedObject)
  }
  requestRender()
}

function setBed(next) {
  bed = next
  updateGrid()
  updateBed()
  updateInfo()
  updateToolbar()
}

function toggleBed() {
  settings.bed = !settings.bed
  store()
  updateGrid()
  updateBed()
  updateInfo()
  updateToolbar()
}

// --- Messen: zwei Punkte auf dem Modell anklicken (nahe Ecken rasten ein) ---

const measure = { on: false, points: [], marker: null }
const raycaster = new Raycaster()
const measureLabel = document.createElement('div')
measureLabel.className = 'measure-label'
measureLabel.hidden = true
document.body.appendChild(measureLabel)

function toggleMeasure() {
  measure.on = !measure.on
  if (!measure.on) clearMeasure()
  renderer.domElement.style.cursor = measure.on ? 'crosshair' : ''
  updateToolbar()
}

function clearMeasure() {
  measure.points = []
  if (measure.marker) {
    scene.remove(measure.marker)
    measure.marker.traverse((child) => { child.geometry?.dispose(); child.material?.dispose() })
    measure.marker = null
  }
  measureLabel.hidden = true
  requestRender()
}

/** Punkt unter der Maus auf dem Modell; liegt eine Ecke des Dreiecks nah (12 px), die Ecke. */
function pick(event) {
  if (!model) return null
  const rect = renderer.domElement.getBoundingClientRect()
  const mouse = new Vector2(((event.clientX - rect.left) / rect.width) * 2 - 1, -((event.clientY - rect.top) / rect.height) * 2 + 1)
  raycaster.setFromCamera(mouse, camera)
  const hits = raycaster.intersectObject(model, true).filter((hit) => !section.on || clipPlane.distanceToPoint(hit.point) >= -1e-6)
  const hit = hits[0]
  if (!hit) return null
  let best = hit.point.clone()
  if (hit.face) {
    const position = hit.object.geometry.attributes.position
    let bestDistance = 12
    for (const index of [hit.face.a, hit.face.b, hit.face.c]) {
      const vertex = new Vector3().fromBufferAttribute(position, index).applyMatrix4(hit.object.matrixWorld)
      const screen = vertex.clone().project(camera)
      const dx = ((screen.x - mouse.x) / 2) * rect.width
      const dy = ((screen.y - mouse.y) / 2) * rect.height
      const distance = Math.hypot(dx, dy)
      if (distance < bestDistance) {
        bestDistance = distance
        best = vertex
      }
    }
  }
  return best
}

function addMeasurePoint(point) {
  if (measure.points.length >= 2) clearMeasure()
  measure.points.push(point)
  if (measure.marker) {
    scene.remove(measure.marker)
    measure.marker.traverse((child) => { child.geometry?.dispose(); child.material?.dispose() })
  }
  const group = new Group()
  const radius = Math.max((hasModel ? bounds.getSize(new Vector3()).length() : 50) / 250, 0.05)
  for (const p of measure.points) {
    const dot = new Mesh(new SphereGeometry(radius, 12, 8), new MeshBasicMaterial({ color: theme.active || '#3574f0', depthTest: false }))
    dot.position.copy(p)
    dot.renderOrder = 10
    group.add(dot)
  }
  if (measure.points.length === 2) {
    const line = new Line(new BufferGeometry().setFromPoints(measure.points),
      new LineBasicMaterial({ color: theme.active || '#3574f0', depthTest: false }))
    line.renderOrder = 10
    group.add(line)
  }
  measure.marker = group
  scene.add(group)
  requestRender()
}

function updateMeasureLabel() {
  if (measure.points.length !== 2) {
    measureLabel.hidden = true
    return
  }
  const [a, b] = measure.points
  const number = (value) => value.toLocaleString(init.locale || undefined, { maximumFractionDigits: 2 })
  const d = b.clone().sub(a)
  measureLabel.innerHTML = ''
  const main = document.createElement('div')
  main.textContent = `${number(d.length())} mm`
  const parts = document.createElement('div')
  parts.className = 'parts'
  parts.textContent = `Δx ${number(Math.abs(d.x))} · Δy ${number(Math.abs(d.y))} · Δz ${number(Math.abs(d.z))}`
  measureLabel.append(main, parts)
  const middle = a.clone().add(b).multiplyScalar(0.5).project(camera)
  const rect = renderer.domElement.getBoundingClientRect()
  measureLabel.style.left = `${((middle.x + 1) / 2) * rect.width}px`
  measureLabel.style.top = `${((1 - middle.y) / 2) * rect.height}px`
  measureLabel.hidden = middle.z > 1
}

// Klick (ohne Ziehen) setzt einen Punkt; Ziehen dreht wie gewohnt
let pointerDown = null
renderer.domElement.addEventListener('pointerdown', (event) => { pointerDown = { x: event.clientX, y: event.clientY } })
renderer.domElement.addEventListener('pointerup', (event) => {
  if (!measure.on || !pointerDown || event.button !== 0) return
  const moved = Math.hypot(event.clientX - pointerDown.x, event.clientY - pointerDown.y)
  pointerDown = null
  if (moved > 4) return
  const point = pick(event)
  if (point) addMeasurePoint(point)
  else clearMeasure()
})
document.addEventListener('keydown', (event) => {
  if (event.key !== 'Escape') return
  if (measure.points.length) clearMeasure()
  else if (measure.on) toggleMeasure()
})

// --- Schnitt: Ebene quer zu X, Y oder Z, Schnittfläche gefüllt (Rückseiten in eigener Farbe) ---

const section = { on: false, axis: 'z', flip: false, value: 0, fraction: 0.5 }
const clipPlane = new Plane(new Vector3(0, 0, -1), 0)
let caps = null

const sectionPanel = document.createElement('div')
sectionPanel.className = 'section-panel'
sectionPanel.hidden = true
sectionPanel.innerHTML = `
  <span class="axes"><button type="button" data-axis="x">X</button><button type="button" data-axis="y">Y</button><button type="button" data-axis="z">Z</button></span>
  <input type="range" min="0" max="1000" step="1">
  <span class="value"></span>
  <button type="button" class="flip"></button>`
document.body.appendChild(sectionPanel)
const sectionSlider = sectionPanel.querySelector('input')
sectionPanel.querySelector('.flip').dataset.tip = labels['section.flip'] || 'Show the other side'
sectionPanel.querySelector('.flip').innerHTML = '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.2" stroke-linejoin="round" stroke-linecap="round"><path d="M4 2.5v11M4 13.5 2 11.5M4 13.5l2-2M12 13.5v-11M12 2.5l-2 2M12 2.5l2 2"/></svg>'
for (const button of sectionPanel.querySelectorAll('[data-axis]')) {
  button.addEventListener('click', () => { section.axis = button.dataset.axis; section.fraction = 0.5; updateSectionRange(); applySection() })
}
sectionPanel.querySelector('.flip').addEventListener('click', () => { section.flip = !section.flip; applySection() })
sectionSlider.addEventListener('input', () => {
  section.fraction = Number(sectionSlider.value) / 1000
  updateSectionRange()
  applySection()
})

// --- Animation ($t): Leiste oben links, das Plugin rechnet je Schritt (setAnimation) ---

const animationPanel = document.createElement('div')
animationPanel.className = 'animation-panel'
animationPanel.hidden = true
animationPanel.innerHTML = `
  <button type="button" class="play"></button>
  <input type="range" min="0" step="1">
  <span class="value"></span>
  <input type="number" class="steps" min="2" max="360">`
document.body.appendChild(animationPanel)
const animationSlider = animationPanel.querySelector('input[type=range]')
const animationSteps = animationPanel.querySelector('.steps')
animationSteps.dataset.tip = labels['animation.steps'] || 'Steps'
let timeAnimation = null
const playIcon = '<svg viewBox="0 0 16 16" fill="currentColor"><path d="M4.5 2.8v10.4L13 8z"/></svg>'
const pauseIcon = '<svg viewBox="0 0 16 16" fill="currentColor"><rect x="4" y="3" width="2.6" height="10" rx=".6"/><rect x="9.4" y="3" width="2.6" height="10" rx=".6"/></svg>'
animationPanel.querySelector('.play').addEventListener('click', () => {
  if (timeAnimation) host.send({ type: 'animation', action: timeAnimation.playing ? 'pause' : 'play' })
})
animationSlider.addEventListener('input', () => {
  updateAnimationValue(Number(animationSlider.value))
  host.send({ type: 'animation', action: 'frame', value: Number(animationSlider.value) })
})
animationSteps.addEventListener('change', () => host.send({ type: 'animation', action: 'steps', value: Number(animationSteps.value) }))

function updateAnimationValue(frame) {
  const t = frame / timeAnimation.steps
  animationPanel.querySelector('.value').textContent = `$t = ${t.toLocaleString(init.locale || undefined, { minimumFractionDigits: 2, maximumFractionDigits: 3 })}`
}

/** null blendet die Leiste aus, sonst { frame, steps, playing }. */
function setAnimation(state) {
  timeAnimation = state
  animationPanel.hidden = !state
  if (state?.playing && measure.on) toggleMeasure()
  updateToolbar()
  if (!state) return
  animationSlider.max = String(state.steps - 1)
  animationSlider.value = String(state.frame)
  if (document.activeElement !== animationSteps) animationSteps.value = String(state.steps)
  const play = animationPanel.querySelector('.play')
  // Nur bei Wechsel ersetzen: Beim Abspielen kommt jedes Bild hier vorbei, ein ausgetauschtes Symbol unter dem Zeiger
  // verschluckt sonst den Klick (Element zwischen Drücken und Loslassen ersetzt)
  if (play.dataset.state !== String(state.playing)) {
    play.dataset.state = String(state.playing)
    play.innerHTML = state.playing ? pauseIcon : playIcon
  }
  play.dataset.tip = state.playing ? (labels['animation.pause'] || 'Pause') : (labels['animation.play'] || 'Play')
  updateAnimationValue(state.frame)
}

function sectionLimits() {
  if (!hasModel) return [0, 1]
  return [bounds.min[section.axis], bounds.max[section.axis]]
}

function updateSectionRange() {
  const [min, max] = sectionLimits()
  section.value = min + (max - min) * section.fraction
  sectionSlider.value = String(Math.round(section.fraction * 1000))
  const number = (value) => value.toLocaleString(init.locale || undefined, { maximumFractionDigits: 2 })
  sectionPanel.querySelector('.value').textContent = `${section.axis} = ${number(section.value)} mm`
  for (const button of sectionPanel.querySelectorAll('[data-axis]')) button.classList.toggle('on', button.dataset.axis === section.axis)
}

function toggleSection() {
  section.on = !section.on
  sectionPanel.hidden = !section.on
  updateSectionRange()
  applySection()
  if (edges) rebuildEdges()
  updateToolbar()
}

function forEachMaterial(object, action) {
  object?.traverse((child) => {
    if (!child.isMesh) return
    for (const material of Array.isArray(child.material) ? child.material : [child.material]) action(material)
  })
}

/** Ebene setzen: sichtbar bleibt die Seite unterhalb des Werts (bzw. oberhalb mit flip). */
function applySection() {
  const normal = new Vector3(section.axis === 'x' ? 1 : 0, section.axis === 'y' ? 1 : 0, section.axis === 'z' ? 1 : 0)
  if (!section.flip) normal.negate()
  clipPlane.set(normal, section.flip ? -section.value : section.value)
  const planes = section.on ? [clipPlane] : null
  // Im Schnitt nur Vorderseiten zeichnen, die Rückseiten übernehmen die Deckel
  forEachMaterial(model, (material) => {
    material.clippingPlanes = planes
    material.side = section.on ? FrontSide : DoubleSide
    material.needsUpdate = true
  })
  forEachMaterial(overlay, (material) => { material.clippingPlanes = planes; material.needsUpdate = true })
  edges?.traverse((child) => { if (child.material) { child.material.clippingPlanes = planes; child.material.needsUpdate = true } })
  if (section.on && !caps) buildCaps()
  if (!section.on) removeCaps()
  requestRender()
}

function buildCaps() {
  if (!model) return
  caps = new Group()
  const material = new MeshBasicMaterial({ color: theme.dark ? '#5c606a' : '#8c909c', side: BackSide, clippingPlanes: [clipPlane] })
  // Schnittfläche schraffiert (45°, in Bildschirm-Pixeln - gleich dicht bei jedem Zoom), wie in einer Schnittzeichnung
  const spacing = (8 * renderer.getPixelRatio()).toFixed(1)
  const line = (1.2 * renderer.getPixelRatio()).toFixed(1)
  const stripe = theme.dark ? 'vec3(0.25, 0.26, 0.29)' : 'vec3(0.38, 0.39, 0.43)'
  material.onBeforeCompile = (shader) => {
    shader.fragmentShader = shader.fragmentShader.replace(
      '#include <dithering_fragment>',
      `#include <dithering_fragment>
      if (mod(gl_FragCoord.x + gl_FragCoord.y, ${spacing}) < ${line}) gl_FragColor.rgb = ${stripe};`,
    )
  }
  material.customProgramCacheKey = () => `cap-hatch-${spacing}-${theme.dark}`
  model.updateMatrixWorld(true)
  model.traverse((child) => {
    if (!child.isMesh) return
    const cap = new Mesh(child.geometry, material)
    cap.matrixAutoUpdate = false
    cap.matrix.copy(child.matrixWorld)
    caps.add(cap)
  })
  scene.add(caps)
}

function removeCaps() {
  if (!caps) return
  scene.remove(caps)
  caps.children[0]?.material.dispose()
  caps = null
}

function rebuildCaps() {
  if (!caps) return
  removeCaps()
  buildCaps()
}

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
  updateBed()
  rebuildCaps()
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
    localStorage.setItem('scadsphere.settings', JSON.stringify({ colors: settings.colors, edges: settings.edges, grid: settings.grid, ortho: settings.ortho, bed: settings.bed }))
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
    bed: bedProblem(),
    measure: measure.points.map((p) => p.toArray()),
    section: section.on ? { axis: section.axis, value: section.value, flip: section.flip } : null,
    overlay: !!overlay,
  }
}

/** Meldungsliste aufklappen (sonst nur bei Fehlern von selbst). */
function expandMessages() {
  $('messages').classList.add('open')
}

/** Für Screenshots und Tests: Schnitt setzen ({ axis, fraction, flip }) bzw. ausschalten (null). */
function setSection(next) {
  if (!next) {
    if (section.on) toggleSection()
    return
  }
  Object.assign(section, { axis: next.axis || 'z', fraction: next.fraction ?? 0.5, flip: !!next.flip })
  if (!section.on) toggleSection()
  updateSectionRange()
  applySection()
}

/** Für Screenshots und Tests: Messung zwischen zwei Punkten ([x, y, z]) zeigen. */
function setMeasure(points) {
  clearMeasure()
  for (const point of points || []) addMeasurePoint(new Vector3(...point))
  requestRender()
}

window.scadSphere = { load, setTools, setTheme, setStatus, showEmpty, setMessages, expandMessages, fit, snapshot, state, setBed, setSection, setMeasure, setAnimation }

if (init.theme) setTheme(init.theme)
else scene.background = new Color('#f7f8fa')
buildToolbar()
resize()
updateGrid()
host.send({ type: 'ready' })
