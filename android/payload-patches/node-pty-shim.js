/**
 * Pure-JS node-pty replacement for Android (bionic libc).
 *
 * node-pty ships prebuilt .node binaries for glibc Linux / macOS / Windows
 * only, so it cannot load inside the app's Node runtime. dsh-subprocess-local
 * uses exactly one entry point from it -- `spawn()` -- and only ever calls
 * pid / onData / onExit / write / kill / resize on the returned object.
 *
 * This shim implements that surface on top of child_process.spawn with pipes.
 * The practical difference versus a real PTY: no tty semantics (no job
 * control, no interactive line editing, programs see a pipe not a terminal).
 * Non-interactive command execution -- which is what the agent does -- works.
 */

'use strict'

const { spawn: cpSpawn } = require('node:child_process')

class Disposable {
  constructor(off) { this._off = off }
  dispose() { if (this._off) { this._off(); this._off = null } }
}

class PipePty {
  constructor(file, args, options) {
    const opts = options || {}
    this._dataHandlers = new Set()
    this._exitHandlers = new Set()
    this._exited = false

    this.cols = opts.cols || 80
    this.rows = opts.rows || 24
    this.process = file

    const env = Object.assign({}, opts.env || process.env)
    // Programs branch on TERM; 'dumb' keeps them from emitting cursor escapes
    // that would be meaningless without a real terminal.
    env.TERM = env.TERM || 'dumb'

    this._child = cpSpawn(file, args || [], {
      cwd: opts.cwd,
      env,
      stdio: ['pipe', 'pipe', 'pipe'],
      // Own the process group so kill() can reach the whole tree, mirroring
      // the session ownership a PTY leader would give us.
      detached: process.platform !== 'win32',
    })

    this.pid = this._child.pid

    const emitData = (chunk) => {
      const text = chunk.toString('utf8')
      for (const handler of this._dataHandlers) handler(text)
    }
    // A PTY merges stdout and stderr onto one stream; callers of this seam
    // expect that single interleaved view.
    if (this._child.stdout) this._child.stdout.on('data', emitData)
    if (this._child.stderr) this._child.stderr.on('data', emitData)

    const settle = (exitCode, signal) => {
      if (this._exited) return
      this._exited = true
      const payload = { exitCode: exitCode == null ? 0 : exitCode, signal: signal || undefined }
      for (const handler of this._exitHandlers) handler(payload)
    }
    this._child.on('exit', (code, signal) => {
      settle(code, typeof signal === 'string' ? signalNumber(signal) : signal)
    })
    // A spawn failure (ENOENT on the shell, EACCES) never emits 'exit'; the
    // seam still needs a settlement or the caller waits forever.
    this._child.on('error', () => { settle(1, undefined) })
  }

  onData(handler) {
    this._dataHandlers.add(handler)
    return new Disposable(() => this._dataHandlers.delete(handler))
  }

  onExit(handler) {
    this._exitHandlers.add(handler)
    return new Disposable(() => this._exitHandlers.delete(handler))
  }

  write(data) {
    if (this._exited) return
    const stdin = this._child.stdin
    if (!stdin || stdin.destroyed) return
    try { stdin.write(data) } catch { /* the child closed its end first */ }
  }

  // Without a tty there is no winsize to set; record it so `cols`/`rows`
  // reads stay truthful and let the call succeed.
  resize(cols, rows) {
    this.cols = cols
    this.rows = rows
  }

  clear() {}

  kill(signal) {
    const sig = signal || 'SIGHUP'
    try {
      // Negative pid targets the detached process group, so children of the
      // spawned shell die with it the way PTY hangup would take them.
      if (process.platform !== 'win32' && this._child.pid) process.kill(-this._child.pid, sig)
      else this._child.kill(sig)
    } catch {
      try { this._child.kill(sig) } catch { /* already reaped */ }
    }
  }

  pause() { if (this._child.stdout) this._child.stdout.pause() }
  resume() { if (this._child.stdout) this._child.stdout.resume() }
}

function signalNumber(name) {
  const { constants } = require('node:os')
  return constants.signals[name]
}

function spawn(file, args, options) {
  return new PipePty(file, args, options)
}

exports.spawn = spawn
exports.fork = spawn
exports.createTerminal = spawn
exports.open = function open() {
  throw new Error('node-pty-shim: open() is not supported without a real PTY')
}
