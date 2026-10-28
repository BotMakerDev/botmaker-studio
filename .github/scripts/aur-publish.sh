#!/usr/bin/env bash
# Publish one AUR package from a PKGBUILD template (2026-10-28).
#
#   aur-publish.sh <PKGBUILD template> <AUR package name> KEY=VALUE…
#
# Each KEY=VALUE replaces @KEY@ in a copy of the template. The .SRCINFO the AUR requires is written by
# makepkg itself, in an archlinux container (the runner is Ubuntu), so it cannot disagree with the PKGBUILD.
# Then both are committed to ssh://aur@aur.archlinux.org/<name>.git and pushed; the AUR creates the package on
# its first push.
#
# AUR_SSH_PRIVATE_KEY is the private key of the maintainer's AUR account, from the repository secret of the
# same name. Unset (a fork, before the account exists), the PKGBUILD and .SRCINFO are still produced and
# checked, and nothing is pushed.
#
# A copy of this script is in botmaker-cli (botmaker-bin) and botmaker-studio (botmaker-studio-bin); change both.
set -euo pipefail

template="$1"; name="$2"; shift 2

work="$(mktemp -d)"
cp "$template" "$work/PKGBUILD"
for pair in "$@"; do
  key="${pair%%=*}"; value="${pair#*=}"
  sed -i "s|@${key}@|${value}|g" "$work/PKGBUILD"
done
if grep -n '@[A-Z0-9_]*@' "$work/PKGBUILD"; then
  echo "::error::$template has a placeholder no argument filled in" >&2
  exit 1
fi

# makepkg refuses to run as root, and the container's only user is root. The folder is handed back to the
# runner's own uid afterwards: the container's builder is 1000, GitHub's runner user 1001, and mktemp's 700
# would otherwise lock the runner out of the files it pushes.
docker run --rm -v "$work:/pkg" -e OWNER="$(id -u):$(id -g)" archlinux:latest bash -c '
  set -e
  useradd -m builder
  chown -R builder /pkg
  su builder -c "cd /pkg && makepkg --printsrcinfo > .SRCINFO"
  chown -R "$OWNER" /pkg'
cat "$work/.SRCINFO"

if [ -z "${AUR_SSH_PRIVATE_KEY:-}" ]; then
  echo "::notice::AUR_SSH_PRIVATE_KEY is unset: $name was checked, not pushed."
  exit 0
fi

ssh_dir="$(mktemp -d)"
umask 077
printf '%s\n' "$AUR_SSH_PRIVATE_KEY" > "$ssh_dir/aur"
# The AUR's ed25519 host key, pinned rather than scanned on each run. Its fingerprint,
# SHA256:RFzBCUItH9LZS0cKB5UE6ceAYhBD5C8GeOBip8Z11+4, is the one aur.archlinux.org publishes on its front page.
echo 'aur.archlinux.org ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIEuBKrPzbawxA/k2g6NcyV5jmqwJ2s+zpgZGZ7tpLIcN' \
  > "$ssh_dir/known_hosts"
export GIT_SSH_COMMAND="ssh -i $ssh_dir/aur -o UserKnownHostsFile=$ssh_dir/known_hosts -o IdentitiesOnly=yes"

repo="$(mktemp -d)"
git clone "ssh://aur@aur.archlinux.org/${name}.git" "$repo"
cp "$work/PKGBUILD" "$work/.SRCINFO" "$repo/"
cd "$repo"
git add PKGBUILD .SRCINFO
if git diff --cached --quiet; then
  echo "$name is already up to date on the AUR."
else
  version="$(sed -n 's/^\tpkgver = //p' .SRCINFO)"
  git -c user.name='BotMaker release' -c user.email='bgroisne@proton.me' commit -q -m "Update to ${version}"
  git push origin HEAD:master
fi
rm -rf "$ssh_dir"
