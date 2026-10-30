import katex from "katex";

/**
 * Helpers for the exam rich-content format (`docs/api/exam-management-v2.md`
 * "Rich content format"). The server is the sanitizing authority (jsoup); the
 * client-side allowlist below only mirrors it as defence in depth for
 * rendering, and to produce clean request bodies. Nothing here is a security
 * boundary.
 */

const ALLOWED_TAGS = new Set([
  "P", "BR", "STRONG", "B", "EM", "I", "U", "S", "CODE", "PRE", "H1", "H2",
  "UL", "OL", "LI", "BLOCKQUOTE", "IMG", "SPAN", "DIV",
]);

function parse(html: string): Document | null {
  if (typeof DOMParser === "undefined") return null;
  return new DOMParser().parseFromString(`<body>${html}</body>`, "text/html");
}

function isMathElement(element: Element): boolean {
  const kind = element.getAttribute("data-math");
  if (element.tagName === "SPAN") return kind === "inline";
  if (element.tagName === "DIV") return kind === "block";
  return false;
}

/**
 * "Non-empty content" = visible text, at least one image, or at least one
 * math node (same rule as the server).
 */
export function isRichContentEmpty(html: string | null | undefined): boolean {
  if (!html || !html.trim()) return true;
  const doc = parse(html);
  if (!doc) {
    if (/<img\b[^>]*data-media-id/i.test(html) || /data-math=/i.test(html)) return false;
    return html.replace(/<[^>]*>/g, "").replace(/&nbsp;/g, " ").trim().length === 0;
  }
  if (doc.body.querySelector("img[data-media-id]")) return false;
  const math = Array.from(doc.body.querySelectorAll("[data-math]")).some(
    (element) => isMathElement(element) && (element.getAttribute("data-latex") ?? "").trim().length > 0
  );
  if (math) return false;
  return (doc.body.textContent ?? "").replace(/ /g, " ").trim().length === 0;
}

/**
 * Prepares editor HTML for a request body: images keep only `data-media-id`
 * (the signed `src` is a read-time decoration and must not be sent back),
 * and an empty document becomes "".
 */
export function toServerHtml(html: string): string {
  if (isRichContentEmpty(html)) return "";
  const doc = parse(html);
  if (!doc) return html;
  doc.body.querySelectorAll("img").forEach((img) => {
    const mediaId = img.getAttribute("data-media-id");
    if (!mediaId) {
      img.remove();
      return;
    }
    for (const attr of Array.from(img.attributes)) {
      if (attr.name !== "data-media-id") img.removeAttribute(attr.name);
    }
  });
  return doc.body.innerHTML;
}

/** Accepts only http(s) or same-origin absolute paths as image sources. */
function safeImageSrc(src: string | null): string | null {
  if (!src) return null;
  const value = src.trim();
  if (value.startsWith("/") && !value.startsWith("//")) return value;
  try {
    const url = new URL(value);
    return url.protocol === "https:" || url.protocol === "http:" ? url.toString() : null;
  } catch {
    return null;
  }
}

export function renderLatex(latex: string, displayMode: boolean): string {
  return katex.renderToString(latex, { displayMode, throwOnError: false, strict: "ignore", trust: false });
}

function cleanNode(node: Node, doc: Document): Node | null {
  if (node.nodeType === Node.TEXT_NODE) return doc.createTextNode(node.textContent ?? "");
  if (node.nodeType !== Node.ELEMENT_NODE) return null;
  const element = node as Element;
  const tag = element.tagName;
  if (!ALLOWED_TAGS.has(tag)) {
    // Unknown wrapper: keep its text content only (mirrors jsoup unwrapping).
    const fragment = doc.createDocumentFragment();
    element.childNodes.forEach((child) => {
      const cleaned = cleanNode(child, doc);
      if (cleaned) fragment.appendChild(cleaned);
    });
    return fragment;
  }
  if (tag === "IMG") {
    const src = safeImageSrc(element.getAttribute("src"));
    if (!src) return null;
    const img = doc.createElement("img");
    img.setAttribute("src", src);
    img.setAttribute("alt", "Question image");
    img.setAttribute("loading", "lazy");
    const mediaId = element.getAttribute("data-media-id");
    if (mediaId) img.setAttribute("data-media-id", mediaId);
    return img;
  }
  if (tag === "SPAN" || tag === "DIV") {
    if (!isMathElement(element)) {
      const fragment = doc.createDocumentFragment();
      element.childNodes.forEach((child) => {
        const cleaned = cleanNode(child, doc);
        if (cleaned) fragment.appendChild(cleaned);
      });
      return fragment;
    }
    const latex = element.getAttribute("data-latex") ?? "";
    const block = tag === "DIV";
    const math = doc.createElement(block ? "div" : "span");
    math.setAttribute("data-math", block ? "block" : "inline");
    math.setAttribute("data-latex", latex);
    math.className = block ? "math-block" : "math-inline";
    // KaTeX output is generated from the latex string only (trust: false).
    math.innerHTML = renderLatex(latex, block);
    return math;
  }
  const clean = doc.createElement(tag.toLowerCase());
  element.childNodes.forEach((child) => {
    const cleaned = cleanNode(child, doc);
    if (cleaned) clean.appendChild(cleaned);
  });
  return clean;
}

/**
 * Server HTML → display HTML: re-applies the allowlist, keeps the
 * server-provided `src` only on images, and renders math nodes with KaTeX.
 */
export function prepareDisplayHtml(html: string | null | undefined): string {
  if (!html) return "";
  const doc = parse(html);
  if (!doc) return "";
  const container = doc.createElement("div");
  doc.body.childNodes.forEach((child) => {
    const cleaned = cleanNode(child, doc);
    if (cleaned) container.appendChild(cleaned);
  });
  return container.innerHTML;
}
