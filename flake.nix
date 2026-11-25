{
  description = "Get After It: run, ride and workout tracker for Pebble Time 2";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  };

  outputs = { nixpkgs, ... }@inputs:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        # The Android SDK is unfree and needs its license accepted.
        config = {
          allowUnfree = true;
          android_sdk.accept_license = true;
        };
      };
      # compileSdk 36 is required by PebbleKitAndroid2's androidx deps; AGP also
      # wants the older build-tools present because it can't install into the store.
      androidSdk = (pkgs.androidenv.composeAndroidPackages {
        platformVersions = [ "36" "35" "34" ];
        buildToolsVersions = [ "36.0.0" "35.0.0" "34.0.0" ];
        includeNDK = false;
        includeSystemImages = false;
      }).androidsdk;
      # The Pebble SDK downloads a prebuilt ARM toolchain and QEMU that expect a
      # standard Linux filesystem, so pebble commands run in an FHS environment:
      # `pebble-env pebble build`. pebble-tool is not in nixpkgs, so it is pinned
      # into a project-local venv.
      pebble-env = pkgs.buildFHSEnv {
        name = "pebble-env";
        targetPkgs = p: [
          p.python313
          p.uv
          p.nodejs_22
          p.git
          p.zlib
          p.ncurses
          p.glib
          p.pixman
          p.SDL2
          p.libpng
          p.freetype
          p.fontconfig
          p.libGL
          p.systemdLibs
          p.alsa-lib
          p.libpulseaudio
          p.sndio
          p.bzip2.out
          p.android-tools
        ];
        runScript = pkgs.writeShellScript "pebble-env-run" ''
          set -e
          if [ ! -x .venv/bin/pebble ]; then
            uv venv --quiet --python ${pkgs.python313}/bin/python3 .venv
            uv pip install --quiet --python .venv/bin/python pebble-tool==5.3.0
          fi
          export PATH="$PWD/.venv/bin:$PATH"
          # The SDK's qemu-pebble links Debian's libbz2.so.1.0 soname, which the
          # loader cache does not index (nixpkgs' soname is libbz2.so.1).
          export LD_LIBRARY_PATH=/usr/lib64
          # pebble-tool records the running emulator in pb-emulator.json in the temp dir.
          # nix develop sets a fresh temp dir per shell, which would start a new
          # emulator on every command; use /tmp so it is found again.
          unset TMPDIR TMP TEMP TEMPDIR
          exec "$@"
        '';
      };
    in
    {
      devShells.${system}.default = pkgs.mkShellNoCC {
        packages = [
          pebble-env
          pkgs.nodejs_22
          pkgs.jdk21
          pkgs.librsvg  # rsvg-convert renders resources/images/*.svg to the PNGs the watchapp uses
          androidSdk
        ];
        ANDROID_HOME = "${androidSdk}/libexec/android-sdk";
        JAVA_HOME = "${pkgs.jdk21}/lib/openjdk";
        shellHook = ''
          PS1="(nix:get-after-it) $PS1"
        '';
      };
    };
}
