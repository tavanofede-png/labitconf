/** Read screen text without decorative avatar initials or hidden screens. */
export function juryScreenContext(screen: Element | null): string {
  if (!screen || screen.closest('[hidden], [aria-hidden="true"]')) return '';
  const snapshot = screen.cloneNode(true) as Element;
  snapshot.querySelectorAll('[hidden], [aria-hidden="true"], .jp-avatar, .d-avatar, svg, img, script, style')
    .forEach(element => element.remove());
  const parts: string[] = [];
  const read = (node: Node) => {
    if (node.nodeType === node.TEXT_NODE) {
      const text = node.textContent?.trim();
      if (text) parts.push(text);
    } else node.childNodes.forEach(read);
  };
  read(snapshot);
  return parts.join(' ').replace(/\s+/g, ' ').slice(0, 2000);
}
