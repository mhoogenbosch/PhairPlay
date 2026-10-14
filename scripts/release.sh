#!/usr/bin/env bash
# PhairPlay local release script
#
# Builds signed release APKs for GoogleTV and FireTV locally, then publishes
# a GitHub Release with both APKs attached.
#
# Usage:
#   ./scripts/release.sh v1.2.0
#
# First-time setup — run once to create your release keystore:
#   ./scripts/release.sh --setup
#
# Credentials are stored in ~/.config/phairplay/release.env (outside the repo).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CREDS_FILE="${HOME}/.config/phairplay/release.env"
KEYSTORE_FILE="${HOME}/.config/phairplay/phairplay-release.jks"

# ── helpers ─────────────────────────────────────────────────────────────────

red()   { printf '\033[0;31m%s\033[0m\n' "$*"; }
green() { printf '\033[0;32m%s\033[0m\n' "$*"; }
bold()  { printf '\033[1m%s\033[0m\n' "$*"; }
die()   { red "ERROR: $*"; exit 1; }

require_cmd() {
    command -v "$1" >/dev/null 2>&1 || die "'$1' not found. Install it and try again."
}

# ── first-time keystore setup ────────────────────────────────────────────────

cmd_setup() {
    bold "=== PhairPlay release keystore setup ==="
    echo ""

    if [[ -f "$KEYSTORE_FILE" ]]; then
        echo "Keystore already exists at $KEYSTORE_FILE"
        read -rp "Overwrite? [y/N] " overwrite
        [[ "$overwrite" =~ ^[Yy]$ ]] || { echo "Aborted."; exit 0; }
    fi

    mkdir -p "$(dirname "$KEYSTORE_FILE")"

    echo "You will be asked a few questions to create your signing key."
    echo "Remember the passwords — they will be saved to $CREDS_FILE"
    echo ""

    read -rp "Key alias (e.g. phairplay): " KEY_ALIAS
    read -rsp "Keystore password: " KEYSTORE_PASSWORD; echo
    read -rsp "Key password (Enter = same as keystore): " KEY_PASSWORD; echo
    [[ -z "$KEY_PASSWORD" ]] && KEY_PASSWORD="$KEYSTORE_PASSWORD"

    keytool -genkey -v \
        -keystore "$KEYSTORE_FILE" \
        -alias "$KEY_ALIAS" \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass "$KEYSTORE_PASSWORD" \
        -keypass "$KEY_PASSWORD" \
        -dname "CN=PhairPlay, O=PhairPlay, C=DE"

    mkdir -p "$(dirname "$CREDS_FILE")"
    chmod 700 "$(dirname "$CREDS_FILE")"
    cat > "$CREDS_FILE" <<EOF
KEYSTORE_PATH=${KEYSTORE_FILE}
KEYSTORE_PASSWORD=${KEYSTORE_PASSWORD}
KEY_ALIAS=${KEY_ALIAS}
KEY_PASSWORD=${KEY_PASSWORD}
EOF
    chmod 600 "$CREDS_FILE"

    green "Done! Credentials saved to $CREDS_FILE"
    echo ""
    echo "Run ./scripts/release.sh v1.0.0 to build and publish your first release."
}

# ── main release flow ─────────────────────────────────────────────────────────

cmd_release() {
    local VERSION="$1"

    # Validate tag format
    [[ "$VERSION" =~ ^v[0-9]+\.[0-9]+\.[0-9] ]] \
        || die "Version must start with 'v' and follow semver, e.g. v1.2.0 (got: $VERSION)"

    require_cmd gh
    require_cmd keytool
    require_cmd git

    # Load signing credentials and export them so Gradle picks them up
    [[ -f "$CREDS_FILE" ]] || die "No credentials found. Run: ./scripts/release.sh --setup"
    set -a; source "$CREDS_FILE"; set +a
    [[ -f "${KEYSTORE_PATH:-}" ]] || die "Keystore not found at $KEYSTORE_PATH"

    # Auto-detect Android SDK if ANDROID_HOME is not set
    if [[ -z "${ANDROID_HOME:-}" ]]; then
        if [[ -d "$HOME/Android/Sdk" ]]; then
            export ANDROID_HOME="$HOME/Android/Sdk"
        elif [[ -d "$HOME/Library/Android/sdk" ]]; then
            export ANDROID_HOME="$HOME/Library/Android/sdk"
        else
            die "ANDROID_HOME not set and no SDK found. Install Android Studio or set ANDROID_HOME."
        fi
    fi

    # Auto-detect Homebrew JDK if JAVA_HOME is not set
    if [[ -z "${JAVA_HOME:-}" ]]; then
        for jdk in /opt/homebrew/opt/openjdk@17 /opt/homebrew/opt/openjdk /usr/libexec/java_home; do
            if [[ -d "$jdk" ]]; then export JAVA_HOME="$jdk"; break; fi
        done
    fi
    [[ -n "${JAVA_HOME:-}" ]] && export PATH="$JAVA_HOME/bin:$PATH"

    cd "$REPO_ROOT"

    # Write local.properties so Gradle finds the Android SDK
    echo "sdk.dir=$ANDROID_HOME" > local.properties

    # Ensure working tree is clean (local.properties is gitignored)
    if ! git diff --quiet -- ':!local.properties' || ! git diff --cached --quiet; then
        die "Working tree has uncommitted changes. Commit or stash them first."
    fi

    # Check gh auth
    gh auth status >/dev/null 2>&1 || die "Not logged in to GitHub. Run: gh auth login"

    bold "=== Building PhairPlay $VERSION ==="
    echo ""

    # Build both flavors
    echo "▸ Building GoogleTV release APK..."
    KEYSTORE_PATH="$KEYSTORE_PATH" \
    KEYSTORE_PASSWORD="$KEYSTORE_PASSWORD" \
    KEY_ALIAS="$KEY_ALIAS" \
    KEY_PASSWORD="$KEY_PASSWORD" \
    ./gradlew :app:assembleGoogletvRelease --quiet --stacktrace

    echo "▸ Building FireTV release APK..."
    KEYSTORE_PATH="$KEYSTORE_PATH" \
    KEYSTORE_PASSWORD="$KEYSTORE_PASSWORD" \
    KEY_ALIAS="$KEY_ALIAS" \
    KEY_PASSWORD="$KEY_PASSWORD" \
    ./gradlew :app:assembleFiretvRelease --quiet --stacktrace

    # Locate and rename APKs
    GOOGLETV_SRC="app/build/outputs/apk/googletv/release/app-googletv-release.apk"
    FIRETV_SRC="app/build/outputs/apk/firetv/release/app-firetv-release.apk"
    GOOGLETV_APK="PhairPlay-${VERSION}-googletv.apk"
    FIRETV_APK="PhairPlay-${VERSION}-firetv.apk"

    [[ -f "$GOOGLETV_SRC" ]] || die "GoogleTV APK not found at $GOOGLETV_SRC"
    [[ -f "$FIRETV_SRC"   ]] || die "FireTV APK not found at $FIRETV_SRC"

    cp "$GOOGLETV_SRC" "$GOOGLETV_APK"
    cp "$FIRETV_SRC"   "$FIRETV_APK"

    green "▸ APKs built:"
    echo "    $GOOGLETV_APK ($(du -sh "$GOOGLETV_APK" | cut -f1))"
    echo "    $FIRETV_APK   ($(du -sh "$FIRETV_APK"   | cut -f1))"
    echo ""

    # Create and push git tag
    if git tag -l "$VERSION" | grep -q "$VERSION"; then
        echo "Tag $VERSION already exists locally — skipping tag creation."
    else
        echo "▸ Creating git tag $VERSION..."
        git tag -a "$VERSION" -m "Release $VERSION"
        git push origin "$VERSION"
    fi

    # Generate changelog from git log since last tag
    PREV_TAG=$(git tag --sort=-version:refname | grep -v "^${VERSION}$" | head -1)
    if [[ -n "$PREV_TAG" ]]; then
        COMMITS=$(git log --pretty=format:"- %s" "${PREV_TAG}..${VERSION}" 2>/dev/null | head -30)
        SINCE_TEXT="since $PREV_TAG"
    else
        COMMITS=$(git log --pretty=format:"- %s" "$VERSION" 2>/dev/null | head -30)
        SINCE_TEXT="initial release"
    fi

    RELEASE_NOTES="## What's changed (${SINCE_TEXT})

${COMMITS}

---
### Installation
Side-load the APK that matches your device:
- **${GOOGLETV_APK}** — Google TV / Android TV (Android 10+)
- **${FIRETV_APK}** — Amazon Fire TV (Android 7.1+)

Use [adb](https://developer.android.com/tools/adb) or a sideloading app like *Downloader* to install."

    # Determine if pre-release (contains a dash, e.g. v1.0.0-beta.1)
    # Pre-release only for -rc/-beta/-alpha tags. This fork versions every regular release as
    # 1.1.0-mh.N, so "any dash" marked them all pre-release and "Latest" silently stayed behind
    # (mh.18-mh.20, October 2026).
    PRERELEASE_FLAG="--latest"
    [[ "$VERSION" =~ -(rc|beta|alpha) ]] && PRERELEASE_FLAG="--prerelease"

    echo "▸ Publishing GitHub Release $VERSION..."
    gh release create "$VERSION" \
        --title "PhairPlay $VERSION" \
        --notes "$RELEASE_NOTES" \
        $PRERELEASE_FLAG \
        "$GOOGLETV_APK" \
        "$FIRETV_APK"

    # Clean up local APK copies
    rm -f "$GOOGLETV_APK" "$FIRETV_APK"

    echo ""
    green "=== Release $VERSION published! ==="
    gh release view "$VERSION" --web 2>/dev/null || true
}

# ── entry point ───────────────────────────────────────────────────────────────

case "${1:-}" in
    --setup|-s)
        cmd_setup
        ;;
    v*)
        cmd_release "$1"
        ;;
    *)
        echo "Usage:"
        echo "  ./scripts/release.sh --setup       # first-time keystore + credentials setup"
        echo "  ./scripts/release.sh v1.2.0        # build APKs and publish GitHub Release"
        exit 1
        ;;
esac
