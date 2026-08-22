#!/usr/bin/env bash
# Generates Magisk/KSU updateJson files pointing to the current VERSION.
# Run AFTER the GitHub release is published so zipUrl is already valid.
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION="$(tr -d '[:space:]' < VERSION)"

if ! [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "error: VERSION must be MAJOR.MINOR.PATCH, got '$VERSION'" >&2
    exit 1
fi

IFS='.' read -r MAJOR MINOR PATCH <<< "$VERSION"
# Force base-10: the regex above accepts a zero-padded component (e.g. 1.08.0),
# which bash would otherwise read as octal in $(( )) and abort on a digit 8/9.
VERSION_CODE=$(( 10#$MAJOR * 10000 + 10#$MINOR * 100 + 10#$PATCH ))

# These files tell a root manager where the next version of each module lives,
# so they must name the repository that actually publishes the release — a fork
# that emitted upstream's URLs would hand its users someone else's zips. In CI
# GITHUB_REPOSITORY is already the right answer; locally the origin remote is.
# VPNHIDE_REPO_SLUG overrides both, for publishing from a checkout whose remote
# isn't the release host.
slug="${VPNHIDE_REPO_SLUG:-${GITHUB_REPOSITORY:-}}"
if [ -z "$slug" ]; then
    origin="$(git remote get-url origin 2>/dev/null || true)"
    # Accept both remote spellings: git@github.com:owner/repo.git and
    # https://github.com/owner/repo(.git)
    slug="$(printf '%s' "$origin" | sed -E 's#^.*github\.com[:/]##; s#\.git$##')"
fi
if ! printf '%s' "$slug" | grep -qE '^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$'; then
    echo "error: could not determine the release repository (got '${slug}')." >&2
    echo "       Set VPNHIDE_REPO_SLUG=owner/repo and re-run." >&2
    exit 1
fi

REPO="https://github.com/${slug}"
RAW="https://raw.githubusercontent.com/${slug}/main"

echo "Release repository: ${slug}"

echo "Generating update-json for v${VERSION} (versionCode: $VERSION_CODE)"

mkdir -p update-json
KMOD_KMIS=("android12-5.10" "android13-5.10" "android13-5.15" "android14-5.15" "android14-6.1" "android15-6.6" "android16-6.12")
for kmi in "${KMOD_KMIS[@]}"; do
    cat > "update-json/update-kmod-${kmi}.json" <<EOJSON
{
  "version": "v${VERSION}",
  "versionCode": ${VERSION_CODE},
  "zipUrl": "${REPO}/releases/download/v${VERSION}/vpnhide-kmod-${kmi}.zip",
  "changelog": "${RAW}/update-json/changelog.md"
}
EOJSON
    echo "  update-json/update-kmod-${kmi}.json"
done

cat > "update-json/update-zygisk.json" <<EOJSON
{
  "version": "v${VERSION}",
  "versionCode": ${VERSION_CODE},
  "zipUrl": "${REPO}/releases/download/v${VERSION}/vpnhide-zygisk.zip",
  "changelog": "${RAW}/update-json/changelog.md"
}
EOJSON
echo "  update-json/update-zygisk.json"

cat > "update-json/update-kpm.json" <<EOJSON
{
  "version": "v${VERSION}",
  "versionCode": ${VERSION_CODE},
  "zipUrl": "${REPO}/releases/download/v${VERSION}/vpnhide-kpm.zip",
  "changelog": "${RAW}/update-json/changelog.md"
}
EOJSON
echo "  update-json/update-kpm.json"

cat > "update-json/update-ports.json" <<EOJSON
{
  "version": "v${VERSION}",
  "versionCode": ${VERSION_CODE},
  "zipUrl": "${REPO}/releases/download/v${VERSION}/vpnhide-ports.zip",
  "changelog": "${RAW}/update-json/changelog.md"
}
EOJSON
echo "  update-json/update-ports.json"
