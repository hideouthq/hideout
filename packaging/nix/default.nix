{ pkgs ? import <nixpkgs> {}, appImage, version, steamRun ? pkgs.steam-run }:
let
  extracted = pkgs.appimageTools.extract {
    pname = "hideout";
    inherit version;
    src = builtins.path { path = appImage; name = "Hideout.AppImage"; };
  };
in pkgs.stdenvNoCC.mkDerivation {
  pname = "hideout";
  inherit version;
  src = extracted;
  nativeBuildInputs = with pkgs; [ autoPatchelfHook makeWrapper patchelf ];
  buildInputs = with pkgs; [
    alsa-lib fontconfig freetype glib gtk3 libglvnd libxkbcommon
    libX11 libXext libXi libXrender libXtst libxcb stdenv.cc.cc.lib zlib
  ];
  dontStrip = true;
  dontAutoPatchelf = true;

  installPhase = ''
    runHook preInstall
    mkdir -p "$out/lib/hideout" "$out/bin" "$out/share/applications" \
      "$out/share/icons/hicolor/512x512/apps"
    cp -a . "$out/lib/hideout/"
    chmod -R u+w "$out/lib/hideout"
    makeWrapper "$out/lib/hideout/Hideout" "$out/bin/hideout" \
      --prefix PATH : ${pkgs.lib.makeBinPath [ steamRun ]}
    cp gg.hideouthq.Hideout.desktop "$out/share/applications/"
    substituteInPlace "$out/share/applications/gg.hideouthq.Hideout.desktop" \
      --replace-fail "Exec=Hideout" "Exec=$out/bin/hideout"
    cp gg.hideouthq.Hideout.png "$out/share/icons/hicolor/512x512/apps/"
    runHook postInstall
  '';

  # autoPatchelf sets a binary's interpreter and then its rpath, and in that order the native image crashes in the
  # dynamic loader. Rpath first runs, so the executable is patched by hand and only the libraries beside it by the hook.
  postFixup = ''
    mapfile -t libraries < <(find "$out/lib/hideout" -type f -name '*.so*')
    autoPatchelf -- "''${libraries[@]}"
    patchelf --set-rpath ${pkgs.lib.makeLibraryPath [ pkgs.zlib ]} "$out/lib/hideout/Hideout"
    patchelf --set-interpreter "$(cat ${pkgs.stdenv.cc}/nix-support/dynamic-linker)" "$out/lib/hideout/Hideout"
  '';

  meta = {
    description = "Hideout desktop application with Steam tracking";
    mainProgram = "hideout";
    platforms = [ "x86_64-linux" ];
  };
}
