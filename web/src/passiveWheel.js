// Mausrad-Listener immer passiv anmelden. Die IDE schickt Radereignisse ohne Gesten-Phasen; bei einem nicht
// passiven Listener wartet Chromium (JCEF, offscreen) bei jedem Ereignis blockierend auf die Seite, das staut
// sich auf Sekunden. OrbitControls meldet seinen Listener mit { passive: false } an, um preventDefault
// aufzurufen - die Seite scrollt nie, also ist das hier unnötig.
const original = EventTarget.prototype.addEventListener

EventTarget.prototype.addEventListener = function (type, listener, options) {
  if (type === 'wheel' || type === 'mousewheel') {
    const capture = typeof options === 'boolean' ? options : !!options?.capture
    return original.call(this, type, listener, { ...(typeof options === 'object' ? options : {}), capture, passive: true })
  }
  return original.call(this, type, listener, options)
}
