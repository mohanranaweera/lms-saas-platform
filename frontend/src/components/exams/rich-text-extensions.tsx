"use client";

import { useEffect, useRef, useState, type KeyboardEvent } from "react";
import {
  Node,
  NodeViewWrapper,
  ReactNodeViewRenderer,
  mergeAttributes,
  type NodeViewProps,
} from "@tiptap/react";
import Image from "@tiptap/extension-image";
import { renderLatex } from "@/lib/exams/rich-content";
import { cn } from "@/lib/utils";

/**
 * Tiptap extensions for the exam rich-content format
 * (`docs/api/exam-management-v2.md`):
 * - `ExamImage`: `<img data-media-id>`; `src` is only the signed preview URL.
 * - `MathInline` / `MathBlock`: `span[data-math=inline][data-latex]` and
 *   `div[data-math=block][data-latex]`, rendered with KaTeX and editable via
 *   a small LaTeX input in the node view.
 */

export const ExamImage = Image.extend({
  addAttributes() {
    return {
      ...this.parent?.(),
      mediaId: {
        default: null,
        parseHTML: (element: HTMLElement) => element.getAttribute("data-media-id"),
        renderHTML: (attributes: Record<string, unknown>) =>
          attributes.mediaId ? { "data-media-id": String(attributes.mediaId) } : {},
      },
    };
  },
  parseHTML() {
    return [{ tag: "img[data-media-id]" }];
  },
}).configure({ inline: false, allowBase64: false });

/** Marks DOM inside a math node view whose events ProseMirror must ignore. */
const MATH_UI_ATTR = "data-math-ui";

function stopMathUiEvent({ event }: { event: Event }): boolean {
  const target = event.target;
  return target instanceof Element && target.closest(`[${MATH_UI_ATTR}]`) !== null;
}

function MathNodeView({ node, updateAttributes, deleteNode, editor, selected }: NodeViewProps) {
  const block = node.type.name === "mathBlock";
  const latex = String(node.attrs.latex ?? "");
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(latex);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (editing) inputRef.current?.focus();
  }, [editing]);

  const commit = () => {
    const next = draft.trim();
    setEditing(false);
    if (!next) {
      deleteNode();
      return;
    }
    if (next !== latex) updateAttributes({ latex: next });
    editor.commands.focus();
  };

  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === "Enter") {
      event.preventDefault();
      commit();
    } else if (event.key === "Escape") {
      event.preventDefault();
      setDraft(latex);
      setEditing(false);
      editor.commands.focus();
    }
  };

  return (
    <NodeViewWrapper
      as={block ? "div" : "span"}
      data-math={block ? "block" : "inline"}
      data-latex={latex}
      className={cn(block ? "math-block my-2" : "math-inline", selected && "math-selected")}
      contentEditable={false}
    >
      {editing ? (
        <span {...{ [MATH_UI_ATTR]: "" }} className="inline-flex max-w-full flex-wrap items-center gap-1 rounded-md border border-border bg-background p-1 align-middle">
          <input
            ref={inputRef}
            type="text"
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            onKeyDown={onKeyDown}
            aria-label={block ? "Block math LaTeX" : "Inline math LaTeX"}
            className="h-7 w-48 max-w-full min-w-0 rounded border border-input bg-transparent px-1.5 font-mono text-xs outline-none focus-visible:ring-2 focus-visible:ring-ring/50"
            data-testid="math-edit-input"
          />
          <button
            type="button"
            onClick={commit}
            className="rounded px-1.5 py-0.5 text-xs font-medium text-primary hover:bg-muted"
          >
            Done
          </button>
          <button
            type="button"
            onClick={() => deleteNode()}
            className="rounded px-1.5 py-0.5 text-xs font-medium text-destructive hover:bg-muted"
          >
            Remove
          </button>
        </span>
      ) : (
        <button
          type="button"
          {...{ [MATH_UI_ATTR]: "" }}
          disabled={!editor.isEditable}
          onClick={() => {
            setDraft(latex);
            setEditing(true);
          }}
          aria-label={`Edit math: ${latex}`}
          className={cn(
            "max-w-full cursor-pointer overflow-x-auto rounded px-0.5 align-middle hover:bg-muted focus-visible:ring-2 focus-visible:ring-ring/50 focus-visible:outline-none",
            block ? "block w-full text-center" : "inline"
          )}
          data-testid="math-node"
        >
          <span aria-hidden="true" dangerouslySetInnerHTML={{ __html: renderLatex(latex, block) }} />
        </button>
      )}
    </NodeViewWrapper>
  );
}

const latexAttribute = {
  latex: {
    default: "",
    parseHTML: (element: HTMLElement) => element.getAttribute("data-latex") ?? "",
    renderHTML: (attributes: Record<string, unknown>) => ({ "data-latex": String(attributes.latex ?? "") }),
  },
};

export const MathInline = Node.create({
  name: "mathInline",
  group: "inline",
  inline: true,
  atom: true,
  selectable: true,
  addAttributes() {
    return latexAttribute;
  },
  parseHTML() {
    return [{ tag: 'span[data-math="inline"]' }];
  },
  renderHTML({ node, HTMLAttributes }) {
    // The LaTeX source doubles as the text content, so the HTML degrades
    // readably anywhere KaTeX is not applied.
    return ["span", mergeAttributes(HTMLAttributes, { "data-math": "inline" }), String(node.attrs.latex ?? "")];
  },
  addNodeView() {
    return ReactNodeViewRenderer(MathNodeView, { as: "span", stopEvent: stopMathUiEvent });
  },
});

export const MathBlock = Node.create({
  name: "mathBlock",
  group: "block",
  atom: true,
  selectable: true,
  addAttributes() {
    return latexAttribute;
  },
  parseHTML() {
    return [{ tag: 'div[data-math="block"]' }];
  },
  renderHTML({ node, HTMLAttributes }) {
    return ["div", mergeAttributes(HTMLAttributes, { "data-math": "block" }), String(node.attrs.latex ?? "")];
  },
  addNodeView() {
    return ReactNodeViewRenderer(MathNodeView, { stopEvent: stopMathUiEvent });
  },
});
