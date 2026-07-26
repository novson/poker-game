export const VOICE_EMOTES = Object.freeze([
  { id: 'nice-hand', icon: '👍', label: '打得不错' },
  { id: 'good-luck', icon: '🍀', label: '祝你好运' },
  { id: 'thinking', icon: '🤔', label: '让我想想' },
  { id: 'call-you', icon: '🔥', label: '跟你到底' },
  { id: 'wow', icon: '🃏', label: '好牌' },
  { id: 'cheers', icon: '🎉', label: '干得漂亮' }
])

const MUSIC_KEY = 'poker.audio.music'
const VOLUME_KEY = 'poker.audio.volume'
const VOICE_KEY = 'poker.audio.voice'

export function readAudioPreferences(storage = window.localStorage) {
  const storedVolume = storage.getItem(VOLUME_KEY)
  const volume = Number(storedVolume)
  return {
    musicEnabled: storage.getItem(MUSIC_KEY) === 'true',
    volume: storedVolume !== null && Number.isFinite(volume) && volume >= 0 && volume <= 100 ? volume : 32,
    voiceEnabled: storage.getItem(VOICE_KEY) !== 'false'
  }
}

export function saveAudioPreferences(preferences, storage = window.localStorage) {
  storage.setItem(MUSIC_KEY, String(Boolean(preferences.musicEnabled)))
  storage.setItem(VOLUME_KEY, String(Math.max(0, Math.min(100, Number(preferences.volume) || 0))))
  storage.setItem(VOICE_KEY, String(Boolean(preferences.voiceEnabled)))
}

export function emoteById(id) {
  return VOICE_EMOTES.find(emote => emote.id === id) || null
}

export function createPokerAudio(environment = globalThis) {
  const AudioContext = environment.AudioContext || environment.webkitAudioContext
  let context
  let master
  let timer
  let startPromise
  let musicWanted = false
  let chordIndex = 0
  let currentVolume = 32
  const chords = [
    [130.81, 164.81, 196],
    [110, 146.83, 174.61],
    [98, 130.81, 164.81],
    [116.54, 146.83, 196]
  ]

  function ensureContext() {
    if (!AudioContext) return null
    if (!context) {
      try { context = new AudioContext() } catch (_) { return null }
      master = context.createGain()
      master.gain.value = 0
      master.connect(context.destination)
    }
    return context
  }

  function setVolume(value) {
    currentVolume = Math.max(0, Math.min(100, Number(value) || 0))
    if (!master || !context) return
    master.gain.cancelScheduledValues(context.currentTime)
    const audibleGain = currentVolume === 0 ? 0 : 0.08 + (currentVolume / 100) * 0.34
    master.gain.setTargetAtTime(audibleGain, context.currentTime, 0.12)
  }

  function note(frequency, startsAt, duration, level) {
    const oscillator = context.createOscillator()
    const gain = context.createGain()
    oscillator.type = 'triangle'
    oscillator.frequency.value = frequency
    gain.gain.setValueAtTime(0.0001, startsAt)
    gain.gain.exponentialRampToValueAtTime(level, startsAt + 0.12)
    gain.gain.exponentialRampToValueAtTime(0.0001, startsAt + duration)
    oscillator.connect(gain)
    gain.connect(master)
    oscillator.start(startsAt)
    oscillator.stop(startsAt + duration + 0.05)
  }

  function playBar() {
    if (!context || !master) return
    const startsAt = context.currentTime + 0.04
    const chord = chords[chordIndex++ % chords.length]
    chord.forEach((frequency, index) => note(frequency, startsAt + index * 0.22, 2.8, 0.16))
    note(chord[0] / 2, startsAt, 3.1, 0.11)
    note(chord[0] * 2, startsAt + 1.35, 1.2, 0.09)
  }

  function startMusic(volume = currentVolume) {
    musicWanted = true
    if (!ensureContext()) return Promise.resolve(false)
    setVolume(volume)
    if (timer) return Promise.resolve(context.state === 'running')
    if (startPromise) return startPromise
    const pending = (async () => {
      try {
        if (context.state !== 'running') await context.resume()
        if (!musicWanted || context.state !== 'running') return false
        setVolume(currentVolume)
        playBar()
        timer = environment.setInterval(playBar, 3600)
        return true
      } catch (_) {
        return false
      }
    })()
    startPromise = pending
    pending.finally(() => {
      if (startPromise === pending) startPromise = null
    })
    return pending
  }

  function stopMusic() {
    musicWanted = false
    if (timer) environment.clearInterval(timer)
    timer = null
    if (master && context) {
      master.gain.cancelScheduledValues(context.currentTime)
      master.gain.setTargetAtTime(0, context.currentTime, 0.08)
    }
  }

  function speak(text, volume = currentVolume) {
    if (!environment.speechSynthesis || !environment.SpeechSynthesisUtterance) return false
    const utterance = new environment.SpeechSynthesisUtterance(text)
    const voices = environment.speechSynthesis.getVoices?.() || []
    utterance.voice = voices.find(voice => voice.lang?.toLowerCase().startsWith('zh')) || null
    utterance.lang = 'zh-CN'
    utterance.rate = 0.96
    utterance.pitch = 0.92
    utterance.volume = Math.max(0.2, Math.min(1, volume / 100))
    environment.speechSynthesis.cancel()
    environment.speechSynthesis.speak(utterance)
    return true
  }

  function destroy() {
    stopMusic()
    environment.speechSynthesis?.cancel()
    context?.close().catch(() => {})
    context = null
    master = null
  }

  return { destroy, setVolume, speak, startMusic, stopMusic }
}
