interface Panel {
  readonly id: string
  readonly paneltype: string
  enabled: boolean
  visible: boolean
  text: string
  readonly steamid: string
  readonly contentwidth: number
  readonly actualuiscale_x: number
  readonly style: Record<string, string | null>
  IsValid(): boolean
  GetParent(): Panel | null
  GetChildCount(): number
  GetChild(index: number): Panel | null
  MoveChildBefore(child: Panel, before: Panel): void
  MoveChildAfter(child: Panel, after: Panel): void
  SetURL(url: string): void
  SetPanelEvent(event: string, handler: () => void): void
  GetAttributeString(name: string, fallback: string): string
  BLoadLayout(path: string, overrideExisting: boolean, partial: boolean): boolean
  FindChildTraverse(id: string): Panel | null
  BHasClass(name: string): boolean
  RemoveClass(name: string): void
  SetHasClass(name: string, has: boolean): void
  RemoveAndDeleteChildren(): void
  DeleteAsync(seconds: number): void
}

declare const $: {
  Msg(...values: unknown[]): void
  GetContextPanel(): Panel
  CreatePanel(type: string, parent: Panel, id: string, properties?: Record<string, string>): Panel
  RegisterEventHandler(event: string, panel: Panel, handler: (...args: never[]) => boolean | void): void
  Schedule(seconds: number, callback: () => void): number
  CancelScheduled(handle: number): void
  DispatchEvent(event: string, ...args: unknown[]): void
}
