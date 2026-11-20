"use client";

import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent, type ReactNode } from "react";
import { EditorContent, useEditor, useEditorState, type Editor } from "@tiptap/react";
import StarterKit from "@tiptap/starter-kit";
import Underline from "@tiptap/extension-underline";
import Placeholder from "@tiptap/extension-placeholder";
import "katex/dist/katex.min.css";
import {
  Bold,
  Code,
  Heading1,
  Heading2,
  Image as ImageIcon,
  Italic,
  List,
  Loader2,
  Sigma,
  Strikethrough,
  Underline as UnderlineIcon,
} from "lucide-react";
import { ExamImage, MathBlock, MathInline } from "@/components/exams/rich-text-extensions";
import { renderLatex } from "@/lib/exams/rich-content";
import { useUploadExamMedia } from "@/lib/api/question-bank";
import { isApiClientError } from "@/lib/api/error";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

const IMAGE_TYPES = ["image/png", "image/jpeg", "image/gif", "image/webp"];
const IMAGE_MAX_BYTES = 5 * 1024 * 1024;

export interface RichTextEditorProps {
  /** Initial HTML (server format). Later changes to this prop are not pushed into the editor. */
  value: string;
  onChange: (html: string) => void;
  /** Accessible name for the editable region (use when there is no visible label element). */
  ariaLabel?: string;
  ariaLabelledBy?: string;
  ariaDescribedBy?: string;
  invalid?: boolean;
  placeholder?: string;
  disabled?: boolean;
  testId?: string;
  className?: string;
}

type ToolId = "bold" | "italic" | "underline" | "strike" | "code" | "h1" | "h2" | "bulletList" | "image" | "math";

interface ToolDef {
  id: ToolId;
  label: string;
  icon: ReactNode;
  /** Toggle tools expose aria-pressed. */
  toggle: boolean;
}

const TOOLS: ToolDef[] = [
  { id: "bold", label: "Bold", icon: <Bold aria-hidden="true" />, toggle: true },
  { id: "italic", label: "Italic", icon: <Italic aria-hidden="true" />, toggle: true },
  { id: "underline", label: "Underline", icon: <UnderlineIcon aria-hidden="true" />, toggle: true },
  { id: "strike", label: "Strikethrough", icon: <Strikethrough aria-hidden="true" />, toggle: true },
  { id: "code", label: "Code", icon: <Code aria-hidden="true" />, toggle: true },
  { id: "h1", label: "Heading 1", icon: <Heading1 aria-hidden="true" />, toggle: true },
  { id: "h2", label: "Heading 2", icon: <Heading2 aria-hidden="true" />, toggle: true },
  { id: "bulletList", label: "Bullet list", icon: <List aria-hidden="true" />, toggle: true },
  { id: "image", label: "Insert image", icon: <ImageIcon aria-hidden="true" />, toggle: false },
  { id: "math", label: "Insert math", icon: <Sigma aria-hidden="true" />, toggle: false },
];

function runTool(editor: Editor, id: ToolId) {
  const chain = editor.chain().focus();
  switch (id) {
    case "bold":
      chain.toggleBold().run();
      break;
    case "italic":
      chain.toggleItalic().run();
      break;
    case "underline":
      chain.toggleUnderline().run();
      break;
    case "strike":
      chain.toggleStrike().run();
      break;
    case "code":
      chain.toggleCode().run();
      break;
    case "h1":
      chain.toggleHeading({ level: 1 }).run();
      break;
    case "h2":
      chain.toggleHeading({ level: 2 }).run();
      break;
    case "bulletList":
      chain.toggleBulletList().run();
      break;
    default:
      break;
  }
}

/**
 * Shared exam rich-text editor (Tiptap): question bodies, answer options and
 * explanations. Output follows the server's allowlisted HTML format; images
 * upload through `POST /api/v1/exam-media` and are inserted as
 * `<img data-media-id>`; math is inserted as KaTeX-rendered inline/block
 * nodes carrying `data-latex`.
 *
 * Toolbar: a WAI-ARIA `toolbar` with a roving tab stop (arrow keys, Home,
 * End), labelled icon buttons, `aria-pressed` on formatting toggles.
 */
export function RichTextEditor({
  value,
  onChange,
  ariaLabel,
  ariaLabelledBy,
  ariaDescribedBy,
  invalid = false,
  placeholder = "Start writing from here",
  disabled = false,
  testId,
  className,
}: RichTextEditorProps) {
  const reactId = useId();
  const onChangeRef = useRef(onChange);
  const lastHtmlRef = useRef(value || "");
  useEffect(() => {
    onChangeRef.current = onChange;
  }, [onChange]);

  const upload = useUploadExamMedia();
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [mathOpen, setMathOpen] = useState(false);
  const [focusIndex, setFocusIndex] = useState(0);
  const toolRefs = useRef<Array<HTMLButtonElement | null>>([]);

  const editor = useEditor({
    immediatelyRender: false,
    editable: !disabled,
    extensions: [
      StarterKit.configure({
        heading: { levels: [1, 2] },
        horizontalRule: false,
        link: false,
        underline: false,
      }),
      Underline,
      ExamImage,
      MathInline,
      MathBlock,
      Placeholder.configure({ placeholder }),
    ],
    content: value || "",
    editorProps: {
      attributes: {
        role: "textbox",
        "aria-multiline": "true",
        ...(ariaLabel ? { "aria-label": ariaLabel } : {}),
        ...(ariaLabelledBy ? { "aria-labelledby": ariaLabelledBy } : {}),
        ...(ariaDescribedBy ? { "aria-describedby": ariaDescribedBy } : {}),
        "aria-invalid": invalid ? "true" : "false",
        class: "rich-content min-h-16 px-4 py-3 text-sm outline-none",
        ...(testId ? { "data-testid": testId } : {}),
      },
    },
    // Only real content changes propagate (not focus/editable toggles), so a
    // server-side field error stays until the user edits the field.
    onUpdate: ({ editor: current }) => {
      const html = current.getHTML();
      if (html === lastHtmlRef.current) return;
      lastHtmlRef.current = html;
      onChangeRef.current(html);
    },
  });

  // Keep the a11y attributes (invalid / describedby) in sync after mount.
  useEffect(() => {
    if (!editor) return;
    editor.setOptions({
      editorProps: {
        attributes: {
          role: "textbox",
          "aria-multiline": "true",
          ...(ariaLabel ? { "aria-label": ariaLabel } : {}),
          ...(ariaLabelledBy ? { "aria-labelledby": ariaLabelledBy } : {}),
          ...(ariaDescribedBy ? { "aria-describedby": ariaDescribedBy } : {}),
          "aria-invalid": invalid ? "true" : "false",
          class: "rich-content min-h-16 px-4 py-3 text-sm outline-none",
          ...(testId ? { "data-testid": testId } : {}),
        },
      },
    });
  }, [editor, ariaLabel, ariaLabelledBy, ariaDescribedBy, invalid, testId]);

  useEffect(() => {
    editor?.setEditable(!disabled, false);
  }, [editor, disabled]);

  const active = useEditorState({
    editor,
    selector: ({ editor: current }) =>
      current
        ? {
            bold: current.isActive("bold"),
            italic: current.isActive("italic"),
            underline: current.isActive("underline"),
            strike: current.isActive("strike"),
            code: current.isActive("code"),
            h1: current.isActive("heading", { level: 1 }),
            h2: current.isActive("heading", { level: 2 }),
            bulletList: current.isActive("bulletList"),
          }
        : null,
  });

  const handleTool = (id: ToolId) => {
    if (!editor) return;
    if (id === "image") {
      setUploadError(null);
      fileInputRef.current?.click();
      return;
    }
    if (id === "math") {
      setMathOpen((open) => !open);
      return;
    }
    runTool(editor, id);
  };

  const onToolbarKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    const count = TOOLS.length;
    let next = focusIndex;
    if (event.key === "ArrowRight") next = (focusIndex + 1) % count;
    else if (event.key === "ArrowLeft") next = (focusIndex - 1 + count) % count;
    else if (event.key === "Home") next = 0;
    else if (event.key === "End") next = count - 1;
    else return;
    event.preventDefault();
    setFocusIndex(next);
    toolRefs.current[next]?.focus();
  };

  const onFileSelected = async (file: File | undefined) => {
    if (!file || !editor) return;
    if (!IMAGE_TYPES.includes(file.type)) {
      setUploadError("Choose a PNG, JPEG, GIF or WebP image.");
      return;
    }
    if (file.size > IMAGE_MAX_BYTES) {
      setUploadError("Images must be 5 MB or smaller.");
      return;
    }
    try {
      const uploaded = await upload.mutateAsync(file);
      editor
        .chain()
        .focus()
        .insertContent({ type: "image", attrs: { src: uploaded.url, alt: "", mediaId: uploaded.mediaId } })
        .run();
      setUploadError(null);
    } catch (error) {
      setUploadError(isApiClientError(error) ? error.message : "The image could not be uploaded. Try again.");
    }
  };

  const uploading = upload.isPending;
  const statusId = `${reactId}-status`;

  return (
    <div
      className={cn(
        "rounded-lg border border-input bg-background transition-colors focus-within:border-ring focus-within:ring-3 focus-within:ring-ring/30",
        invalid && "border-destructive focus-within:border-destructive focus-within:ring-destructive/20",
        disabled && "opacity-60",
        className
      )}
      data-testid={testId ? `${testId}-container` : undefined}
    >
      {editor ? (
        <EditorContent editor={editor} />
      ) : (
        <div className="min-h-16 px-4 py-3 text-sm text-muted-foreground" aria-hidden="true">
          {placeholder}
        </div>
      )}

      {mathOpen && editor ? (
        <MathInsertPanel
          onCancel={() => {
            setMathOpen(false);
            toolRefs.current[TOOLS.length - 1]?.focus();
          }}
          onInsert={(latex, block) => {
            setMathOpen(false);
            if (block) {
              editor.chain().focus().insertContent({ type: "mathBlock", attrs: { latex } }).run();
            } else {
              editor.chain().focus().insertContent({ type: "mathInline", attrs: { latex } }).run();
            }
          }}
        />
      ) : null}

      <div
        role="toolbar"
        aria-label={ariaLabel ? `${ariaLabel} formatting` : "Formatting"}
        onKeyDown={onToolbarKeyDown}
        className="flex flex-wrap items-center gap-0.5 px-2 pb-2"
      >
        {TOOLS.map((tool, index) => {
          const pressed = tool.toggle && active ? Boolean(active[tool.id as keyof typeof active]) : undefined;
          return (
            <button
              key={tool.id}
              ref={(element) => {
                toolRefs.current[index] = element;
              }}
              type="button"
              tabIndex={index === focusIndex ? 0 : -1}
              aria-label={tool.label}
              title={tool.label}
              aria-pressed={tool.toggle ? Boolean(pressed) : undefined}
              aria-expanded={tool.id === "math" ? mathOpen : undefined}
              disabled={disabled || !editor || (tool.id === "image" && uploading)}
              onFocus={() => setFocusIndex(index)}
              onMouseDown={(event) => event.preventDefault()}
              onClick={() => handleTool(tool.id)}
              className={cn(
                "inline-flex size-8 items-center justify-center rounded-md text-foreground outline-none transition-colors hover:bg-muted focus-visible:ring-3 focus-visible:ring-ring/50 disabled:pointer-events-none disabled:opacity-50 [&_svg]:size-4",
                pressed && "bg-primary/10 text-primary"
              )}
              data-testid={testId ? `${testId}-tool-${tool.id}` : undefined}
            >
              {tool.id === "image" && uploading ? <Loader2 className="animate-spin" aria-hidden="true" /> : tool.icon}
            </button>
          );
        })}
        <input
          ref={fileInputRef}
          type="file"
          accept={IMAGE_TYPES.join(",")}
          className="sr-only"
          tabIndex={-1}
          aria-hidden="true"
          onChange={(event) => {
            const file = event.target.files?.[0];
            event.target.value = "";
            void onFileSelected(file);
          }}
          data-testid={testId ? `${testId}-image-input` : undefined}
        />
      </div>
      <div id={statusId} aria-live="polite" className="px-3 empty:hidden">
        {uploading ? <p className="pb-2 text-xs text-muted-foreground">Uploading image…</p> : null}
        {uploadError ? (
          <p role="alert" className="pb-2 text-xs text-destructive">
            {uploadError}
          </p>
        ) : null}
      </div>
    </div>
  );
}

function MathInsertPanel({
  onInsert,
  onCancel,
}: {
  onInsert: (latex: string, block: boolean) => void;
  onCancel: () => void;
}) {
  const id = useId();
  const [latex, setLatex] = useState("");
  const [block, setBlock] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const preview = useMemo(() => (latex.trim() ? renderLatex(latex.trim(), block) : ""), [latex, block]);

  useEffect(() => {
    inputRef.current?.focus();
  }, []);

  const submit = () => {
    if (!latex.trim()) {
      setError("Enter a LaTeX expression.");
      inputRef.current?.focus();
      return;
    }
    onInsert(latex.trim(), block);
  };

  return (
    <div
      className="mx-2 mb-2 flex flex-col gap-2 rounded-md border border-border bg-muted/40 p-3"
      role="group"
      aria-labelledby={`${id}-title`}
      onKeyDown={(event) => {
        if (event.key === "Escape") {
          event.preventDefault();
          onCancel();
        }
      }}
      data-testid="math-insert-panel"
    >
      <p id={`${id}-title`} className="text-xs font-medium text-foreground">
        Insert math
      </p>
      <div className="flex flex-col gap-1">
        <label htmlFor={`${id}-latex`} className="text-xs text-muted-foreground">
          LaTeX
        </label>
        <input
          ref={inputRef}
          id={`${id}-latex`}
          type="text"
          value={latex}
          placeholder="e.g. \frac{a}{b}"
          onChange={(event) => {
            setLatex(event.target.value);
            setError(null);
          }}
          onKeyDown={(event) => {
            if (event.key === "Enter") {
              event.preventDefault();
              submit();
            }
          }}
          aria-invalid={Boolean(error)}
          aria-describedby={error ? `${id}-error` : undefined}
          className="h-8 w-full min-w-0 rounded-lg border border-input bg-background px-2 font-mono text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50"
          data-testid="math-latex-input"
        />
        {error ? (
          <p id={`${id}-error`} role="alert" className="text-xs text-destructive">
            {error}
          </p>
        ) : null}
      </div>
      <fieldset className="flex flex-wrap gap-4 text-xs">
        <legend className="sr-only">Math display</legend>
        <label className="inline-flex items-center gap-1.5">
          <input type="radio" name={`${id}-mode`} checked={!block} onChange={() => setBlock(false)} />
          Inline
        </label>
        <label className="inline-flex items-center gap-1.5">
          <input type="radio" name={`${id}-mode`} checked={block} onChange={() => setBlock(true)} />
          Block (own line)
        </label>
      </fieldset>
      {preview ? (
        <div className="overflow-x-auto rounded border border-border bg-background px-2 py-1" aria-label="Preview">
          <span dangerouslySetInnerHTML={{ __html: preview }} />
        </div>
      ) : null}
      <div className="flex justify-end gap-2">
        <Button type="button" size="sm" variant="outline" onClick={onCancel}>
          Cancel
        </Button>
        <Button type="button" size="sm" onClick={submit} data-testid="math-insert-submit">
          Insert
        </Button>
      </div>
    </div>
  );
}
