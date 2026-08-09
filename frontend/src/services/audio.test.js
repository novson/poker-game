import { describe, expect, it } from 'vitest'
import { createPokerAudio, emoteById, readAudioPreferences, saveAudioPreferences, VOICE_EMOTES } from './audio'

function memoryStorage(initial = {}) {
  const values = new Map(Object.entries(initial))
  return {
    getItem: key => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value)
  }
}

describe('poker audio preferences', () => {
  it('uses quiet, opt-in music defaults and enables voice emotes', () => {
    expect(readAudioPreferences(memoryStorage())).toEqual({
      musicEnabled: false,
      volume: 32,
      voiceEnabled: true,
      effectsEnabled: true
    })
  })

  it('persists settings and clamps volume', () => {
    const storage = memoryStorage()
    saveAudioPreferences({
      musicEnabled: true,
      volume: 150,
      voiceEnabled: false,
      effectsEnabled: false
    }, storage)

    expect(readAudioPreferences(storage)).toEqual({
      musicEnabled: true,
      volume: 100,
      voiceEnabled: false,
      effectsEnabled: false
    })
  })

  it('exposes only known preset emotes', () => {
    expect(VOICE_EMOTES).toHaveLength(6)
    expect(emoteById('wow')).toMatchObject({ label: '好牌' })
    expect(emoteById('custom-message')).toBeNull()
  })

  it('waits for the audio context and starts music at an audible gain', async () => {
    const oscillators = []
    const gains = []
    class AudioContext {
      constructor() {
        this.state = 'suspended'
        this.currentTime = 0
        this.destination = {}
      }
      createGain() {
        const gain = {
          value: 0,
          cancelScheduledValues() {},
          setTargetAtTime(value) { this.target = value },
          setValueAtTime() {},
          exponentialRampToValueAtTime() {}
        }
        gains.push(gain)
        return { gain, connect() {} }
      }
      createOscillator() {
        const oscillator = {
          frequency: {},
          connect() {},
          start() { oscillator.started = true },
          stop() {}
        }
        oscillators.push(oscillator)
        return oscillator
      }
      async resume() { this.state = 'running' }
      close() { return Promise.resolve() }
    }
    const environment = {
      AudioContext,
      setInterval: () => 1,
      clearInterval() {}
    }
    const audio = createPokerAudio(environment)

    await expect(audio.startMusic(32)).resolves.toBe(true)
    expect(gains[0].target).toBeGreaterThan(0.15)
    expect(oscillators.filter(oscillator => oscillator.started)).toHaveLength(5)
    audio.destroy()
  })

  it('plays a short urgent tick after audio has been unlocked', async () => {
    const oscillators = []
    class AudioContext {
      constructor() {
        this.state = 'suspended'
        this.currentTime = 0
        this.destination = {}
      }
      createGain() {
        return {
          gain: {
            cancelScheduledValues() {},
            setTargetAtTime() {},
            setValueAtTime() {},
            exponentialRampToValueAtTime() {}
          },
          connect() {}
        }
      }
      createOscillator() {
        const oscillator = {
          frequency: {},
          connect() {},
          start() { oscillator.started = true },
          stop() {}
        }
        oscillators.push(oscillator)
        return oscillator
      }
      async resume() { this.state = 'running' }
      close() { return Promise.resolve() }
    }
    const audio = createPokerAudio({ AudioContext })

    await expect(audio.unlock(32)).resolves.toBe(true)
    expect(audio.tick(2, 32)).toBe(true)
    expect(oscillators).toHaveLength(1)
    expect(oscillators[0].type).toBe('square')
    audio.destroy()
  })
})
