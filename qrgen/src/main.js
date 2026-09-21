import qrcode from 'qrcode-generator'
import { buildSipPayload, maskSipPayload, SipSettingsError } from './sip-uri.js'

const form = document.querySelector('#sip-form')
const canvas = document.querySelector('#qr-canvas')
const placeholder = document.querySelector('#qr-placeholder')
const payloadCard = document.querySelector('#payload-card')
const payloadPreview = document.querySelector('#payload-preview')
const status = document.querySelector('#status')
const downloadButton = document.querySelector('#download-button')
const copyButton = document.querySelector('#copy-button')
const printButton = document.querySelector('#print-button')
const togglePassword = document.querySelector('#toggle-password')
const togglePayload = document.querySelector('#toggle-payload')
const passwordInput = document.querySelector('#password')

let currentPayload = ''
let payloadVisible = false

function formValues() {
  const data = new FormData(form)
  return Object.fromEntries(data.entries())
}

function clearErrors() {
  form.querySelectorAll('input').forEach((input) => input.removeAttribute('aria-invalid'))
  form.querySelectorAll('.error').forEach((element) => { element.textContent = '' })
}

function showError(error) {
  const input = document.querySelector(`#${error.field}`)
  const output = document.querySelector(`#${error.field}-error`)
  if (input) {
    input.setAttribute('aria-invalid', 'true')
    input.focus()
  }
  if (output) output.textContent = error.message
  status.textContent = '入力内容を確認してください。'
}

function drawQr(payload) {
  const qr = qrcode(0, 'M')
  qr.addData(payload, 'Byte')
  qr.make()

  const moduleCount = qr.getModuleCount()
  const quietZone = 4
  const availableSize = 360
  const cellSize = Math.max(1, Math.floor(availableSize / (moduleCount + quietZone * 2)))
  const drawingSize = cellSize * (moduleCount + quietZone * 2)
  const pixelRatio = Math.max(1, Math.min(window.devicePixelRatio || 1, 3))
  const context = canvas.getContext('2d')

  canvas.width = drawingSize * pixelRatio
  canvas.height = drawingSize * pixelRatio
  canvas.style.width = `${drawingSize}px`
  canvas.style.height = `${drawingSize}px`
  context.setTransform(pixelRatio, 0, 0, pixelRatio, 0, 0)
  context.imageSmoothingEnabled = false
  context.fillStyle = '#ffffff'
  context.fillRect(0, 0, drawingSize, drawingSize)
  context.fillStyle = '#102a43'

  for (let row = 0; row < moduleCount; row += 1) {
    for (let column = 0; column < moduleCount; column += 1) {
      if (qr.isDark(row, column)) {
        context.fillRect(
          (column + quietZone) * cellSize,
          (row + quietZone) * cellSize,
          cellSize,
          cellSize,
        )
      }
    }
  }
}

function updatePayloadPreview() {
  payloadPreview.textContent = payloadVisible ? currentPayload : maskSipPayload(currentPayload)
}

function setOutputEnabled(enabled) {
  downloadButton.disabled = !enabled
  copyButton.disabled = !enabled
  printButton.disabled = !enabled
}

form.addEventListener('submit', (event) => {
  event.preventDefault()
  clearErrors()

  try {
    currentPayload = buildSipPayload(formValues())
    drawQr(currentPayload)
    placeholder.hidden = true
    canvas.hidden = false
    payloadCard.hidden = false
    updatePayloadPreview()
    setOutputEnabled(true)
    status.textContent = 'QRコードを生成しました。'
  } catch (error) {
    if (error instanceof SipSettingsError) {
      showError(error)
      return
    }
    status.textContent = 'QRコードを生成できませんでした。入力内容を短くして再度お試しください。'
  }
})

togglePassword.addEventListener('click', () => {
  const showing = passwordInput.type === 'text'
  passwordInput.type = showing ? 'password' : 'text'
  togglePassword.textContent = showing ? '表示' : '隠す'
  togglePassword.setAttribute('aria-pressed', String(!showing))
  togglePassword.setAttribute('aria-label', showing ? 'パスワードを表示' : 'パスワードを隠す')
})

togglePayload.addEventListener('click', () => {
  payloadVisible = !payloadVisible
  togglePayload.textContent = payloadVisible ? 'パスワードを隠す' : 'パスワードを表示'
  togglePayload.setAttribute('aria-pressed', String(payloadVisible))
  updatePayloadPreview()
})

downloadButton.addEventListener('click', () => {
  const link = document.createElement('a')
  link.download = 'simple-phone-sip-settings.png'
  link.href = canvas.toDataURL('image/png')
  link.click()
  status.textContent = 'QRコードをPNGで保存しました。'
})

copyButton.addEventListener('click', async () => {
  try {
    await navigator.clipboard.writeText(currentPayload)
    status.textContent = '設定文字列をクリップボードにコピーしました。'
  } catch {
    status.textContent = 'コピーできませんでした。ブラウザの権限設定を確認してください。'
  }
})

printButton.addEventListener('click', () => window.print())

form.addEventListener('input', (event) => {
  const input = event.target
  if (!(input instanceof HTMLInputElement)) return
  input.removeAttribute('aria-invalid')
  const output = document.querySelector(`#${input.id}-error`)
  if (output) output.textContent = ''
  if (currentPayload) status.textContent = '入力内容が変更されました。もう一度生成してください。'
})
