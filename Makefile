PEBBLE = nix develop --command pebble-env pebble
# Pebble SDK version; installed into ~/.pebble-sdk on first build.
SDK = 4.44

.PHONY: sdk build emu install test companion companion-release companion-install

sdk:
	$(PEBBLE) sdk list | grep -q '^$(SDK) (active)' || $(PEBBLE) sdk install $(SDK) || $(PEBBLE) sdk activate $(SDK)

build: sdk
	$(PEBBLE) build

emu: build
	$(PEBBLE) install --emulator emery --logs

# Needs the Core Devices app's Dev Connection on and the phone reachable by adb.
install: build
	$(PEBBLE) install --adb --logs

test:
	nix develop --command bash -c 'cd companion && ./gradlew :app:testDebugUnitTest'

companion:
	nix develop --command bash -c 'cd companion && ./gradlew :app:assembleDebug'

# Signed release APK; needs RELEASE_KEYSTORE and RELEASE_KEYSTORE_PASSWORD.
companion-release:
	nix develop --command bash -c 'cd companion && ./gradlew :app:assembleRelease'

# Set ANDROID_SERIAL when the phone is attached over both USB and network.
companion-install:
	nix develop --command bash -c 'cd companion && ./gradlew :app:installDebug'

