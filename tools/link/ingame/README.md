# In-game UI for the menus pack

`dist/` is what `hideout-link menus` embeds (`//go:embed all:ingame/dist` in `ingame.go`) and compiles into the menus pack on the player's machine. It lives inside `tools/link` because `go:embed` can't reach outside the module. The UI's build writes its output here. The files here now are placeholders.

`dist/` mirrors the pack, as Panorama sources:

- `panorama/scripts/hideout/boot.js` runs in the overlay root, `citadel_hud_and_db_overlay.xml`, from game start.
- `panorama/scripts/hideout/popup.js` runs in the stock Custom Match popup, `popups/citadel_popup_custom_match.xml`.
- `panorama/layout/hideout/*.xml` and `panorama/styles/hideout/*.css` hold any other layouts and sheets.

Rules the build enforces:

- Only `.xml`, `.js` and `.css` under `panorama/`. Anything else would ship raw, and a raw file under `panorama/` fails the build's tests, because the game crashes when a layout includes one.
- Don't write `panorama/scripts/hideout/config.js` or either shadowed layout. The helper generates them, and a second copy fails the build.
- `config.js` is included before `boot.js` and `popup.js`, and defines `HideoutConfig = {protocol, port, page, token}`. `page` is the bridge page with `?port=` set to the endpoint's port. `token` is new for every pack.
- A sheet may name a stock texture as `url("s2r://panorama/images/....vtex")`. The build reads its size from the player's install and doesn't copy it into the pack.
