export function safeRedirectTarget(value?: string): string {
  if (!value || !value.startsWith('/') || value.startsWith('//')) return '/'

  let decoded = value
  try {
    for (let pass = 0; pass < 3; pass += 1) {
      const next = decodeURIComponent(decoded)
      if (next === decoded) break
      decoded = next
    }
  } catch {
    return '/'
  }
  if (
    decoded.startsWith('//') ||
    decoded.includes('\\') ||
    containsAsciiControl(decoded)
  ) {
    return '/'
  }
  return value
}

function containsAsciiControl(value: string): boolean {
  return Array.from(value).some((character) => {
    const codePoint = character.codePointAt(0) ?? 0
    return codePoint <= 0x1f || codePoint === 0x7f
  })
}
