// Filamente einer 3MF aus Bambu Studio/OrcaSlicer oder aus dem Mehrfarb-Export von SCAD Sphere: In
// `Metadata/model_settings.config` steht je Objekt und Teil das Filament (`extruder`), die Farben der Filamente stehen nur
// in Slicer-Projekten (`Metadata/project_settings.config`, `filament_colour`). Ohne sie bekommt jedes Filament eine
// eigene Farbe aus einer festen Reihe; Teile aus dem Export nach Farben heißen wie ihre Farbe und behalten diese.
import { Color } from 'three'
import { unzipSync, strFromU8 } from 'three/addons/libs/fflate.module.js'

export const FILAMENT_COLORS = ['#f9d72c', '#3d8bff', '#e5484d', '#30a46c', '#8e4ec6', '#f76b15', '#f2f2f2', '#3b3b3b', '#12a5b0', '#d6409f']

/**
 * Filament und Farbe je Teil, oder null, wenn die Datei keine Teile-Einstellungen hat.
 * Ergebnis: { items: [{ objectId, extruder, components: [{ objectId, extruder, name }] }], colours: [...], ownExport }
 */
export function readFilaments(data) {
  let files
  try {
    files = unzipSync(new Uint8Array(data))
  } catch {
    return null
  }
  const find = (name) => Object.keys(files).find((key) => key.replace(/^\//, '').toLowerCase() === name.toLowerCase())
  const settingsKey = find('Metadata/model_settings.config')
  if (!settingsKey) return null
  const xml = (key) => new DOMParser().parseFromString(strFromU8(files[key]), 'application/xml')
  const settings = xml(settingsKey)
  const metadata = (element) => {
    const result = {}
    for (const child of element.children) if (child.localName === 'metadata') result[child.getAttribute('key')] = child.getAttribute('value')
    return result
  }
  const objects = new Map()
  for (const object of settings.getElementsByTagName('object')) {
    const meta = metadata(object)
    const extruder = Number(meta.extruder) || 1
    const parts = new Map()
    for (const part of object.getElementsByTagName('part')) {
      const partMeta = metadata(part)
      parts.set(part.getAttribute('id'), { name: partMeta.name || '', extruder: Number(partMeta.extruder) || extruder })
    }
    objects.set(object.getAttribute('id'), { extruder, parts })
  }
  let colours = []
  const projectKey = find('Metadata/project_settings.config')
  if (projectKey) {
    try {
      const value = JSON.parse(strFromU8(files[projectKey])).filament_colour
      if (Array.isArray(value)) colours = value.map(String)
    } catch {
      // ohne Farben aus dem Projekt
    }
  }
  // Hauptmodell: Build-Einträge (in dieser Reihenfolge legt der Loader die Kinder an) und die Bauteile der Objekte
  const modelKey = find('3D/3dmodel.model') || Object.keys(files).find((key) => key.toLowerCase().endsWith('.model'))
  if (!modelKey) return null
  const model = xml(modelKey)
  const components = new Map()
  for (const object of model.getElementsByTagNameNS('*', 'object')) {
    const list = [...object.getElementsByTagNameNS('*', 'component')].map((c) => c.getAttribute('objectid'))
    components.set(object.getAttribute('id'), list)
  }
  const ownExport = [...model.getElementsByTagNameNS('*', 'metadata')].some((m) => m.getAttribute('name') === 'Application' && m.textContent.trim().startsWith('SCAD Sphere'))
  const items = [...model.getElementsByTagNameNS('*', 'item')].map((item) => {
    const objectId = item.getAttribute('objectid')
    const object = objects.get(objectId) || { extruder: 1, parts: new Map() }
    return {
      objectId,
      extruder: object.extruder,
      components: (components.get(objectId) || []).map((id) => ({ objectId: id, ...(object.parts.get(id) || { name: '', extruder: object.extruder }) })),
    }
  })
  return { items, colours, ownExport }
}

/** Farbe eines Teilnamens aus dem Export nach Farben (`red`, `#ff8800`), sonst null. */
function colorOfName(name) {
  const text = String(name || '').trim().toLowerCase()
  if (/^#[0-9a-f]{6}$/.test(text)) return text
  return Object.prototype.hasOwnProperty.call(Color.NAMES, text) ? '#' + new Color(Color.NAMES[text]).getHexString() : null
}

/** Farbe für Filament [extruder] (ab 1). */
export function filamentColor(info, extruder) {
  const colour = info.colours[extruder - 1]
  if (colour && /^#[0-9a-f]{6}/i.test(colour)) return colour.slice(0, 7)
  return FILAMENT_COLORS[(extruder - 1) % FILAMENT_COLORS.length]
}

/**
 * Meshes der vom ThreeMFLoader geladenen Gruppe nach Filament einfärben. Kinder der Gruppe = Build-Einträge, deren
 * Kinder = Bauteile (Teile). [makeMaterial] bekommt Farbe und Filament und liefert das Material. Ohne Projektfarben bleibt
 * Filament 1 in der Modellfarbe ([keep] true). Gibt die Zahl der eingefärbten Meshes zurück.
 */
export function applyFilaments(group, info, makeMaterial) {
  if (!info) return 0
  const used = new Set()
  info.items.forEach((item) => {
    if (item.components.length) item.components.forEach((c) => used.add(c.extruder))
    else used.add(item.extruder)
  })
  // Nur ein Filament und keine Projektfarben: nichts zu unterscheiden, Modellfarbe behalten
  if (used.size < 2 && !info.colours.length && !info.ownExport) return 0
  let count = 0
  const paint = (object, extruder, name) => {
    const own = info.ownExport ? colorOfName(name) : null
    const keep = !own && !info.colours.length && extruder === 1
    object.traverse((child) => {
      if (!child.isMesh) return
      child.userData.filament = extruder
      if (keep) return
      child.material = makeMaterial(own || filamentColor(info, extruder), extruder, child.material)
      count++
    })
  }
  info.items.forEach((item, i) => {
    const node = group.children[i]
    if (!node) return
    if (item.components.length && node.children.length === item.components.length) {
      item.components.forEach((component, j) => paint(node.children[j], component.extruder, component.name))
    } else {
      paint(node, item.extruder, '')
    }
  })
  return count
}
