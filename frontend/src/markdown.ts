import DOMPurify from 'dompurify'
import { marked } from 'marked'

marked.use({
  gfm: true,
  breaks: false,
})

let linksOpened = false

function openLinksInNewTab() {
  if (linksOpened) return
  linksOpened = true
  DOMPurify.addHook('afterSanitizeAttributes', (node) => {
    if (node.tagName !== 'A' || !node.hasAttribute('href')) return
    node.setAttribute('target', '_blank')
    node.setAttribute('rel', 'noopener noreferrer')
  })
}

export function renderMarkdown(source: string): string {
  openLinksInNewTab()
  const html = marked.parse(source, { async: false })
  return DOMPurify.sanitize(html, {
    ADD_TAGS: ['input'],
    ADD_ATTR: ['disabled', 'checked', 'type'],
  })
}
