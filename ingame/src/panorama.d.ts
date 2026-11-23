// The parts of the game's Panorama script API the in-game UI uses.

interface Panel {
  readonly id: string
  readonly paneltype: string
  hittest: boolean
  enabled: boolean
  visible: boolean
  text: string
  readonly steamid: string
  readonly contentwidth: number
  readonly actuallayoutwidth: number
  readonly actualuiscale_x: number
  readonly style: Record<string, string | null>
  IsValid(): boolean
  GetParent(): Panel | null
  GetChildCount(): number
  GetChild(index: number): Panel | null
  MoveChildBefore(child: Panel, before: Panel): void
  MoveChildAfter(child: Panel, after: Panel): void
  SetImage(path: string): void
  SetURL(url: string): void
  SetPanelEvent(event: string, handler: () => void): void
  SetAttributeString(name: string, value: string): void
  GetAttributeString(name: string, fallback: string): string
  BLoadLayout(path: string, overrideExisting: boolean, partial: boolean): boolean
  FindChildTraverse(id: string): Panel | null
  FindChildrenWithClassTraverse(name: string): Panel[]
  BHasClass(name: string): boolean
  AddClass(name: string): void
  RemoveClass(name: string): void
  SetHasClass(name: string, has: boolean): void
  RemoveAndDeleteChildren(): void
  DeleteAsync(seconds: number): void
  SetDialogVariable(name: string, value: string): void
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
