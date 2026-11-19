#!/usr/bin/env bash
set -euo pipefail

sha=${1:-}
if [[ ! "$sha" =~ ^[0-9a-f]{40}$ ]]; then
  echo "Expected a full lowercase commit SHA" >&2
  exit 1
fi

export PATH="/snap/bin:$PATH"
registry=007448417066.dkr.ecr.ap-northeast-2.amazonaws.com
repository="$registry/boongboongus-backend"
cd /opt/boongboongus

exec 9>.deploy.lock
flock -n 9

compose=(docker compose --env-file app.env)
candidate=$(mktemp .compose-release.XXXXXX)
trap 'rm -f -- "$candidate"' EXIT

# Replace only the backend image tag in the existing Compose configuration.
if [[ $(grep -Ec "^[[:space:]]+image: $repository:[a-f0-9]{40}$" compose.yaml) != 1 ]]; then
  echo "Expected one backend image with a commit SHA in compose.yaml" >&2
  exit 1
fi
sed -E "s|^([[:space:]]*image: )$repository:[a-f0-9]{40}$|\1$repository:$sha|" \
  compose.yaml > "$candidate"

"${compose[@]}" -f "$candidate" config -q
"${compose[@]}" -f "$candidate" config --images | grep -Fx -- "$repository:$sha" >/dev/null
aws ecr get-login-password --region ap-northeast-2 |
  docker login --username AWS --password-stdin "$registry" >/dev/null
"${compose[@]}" -f "$candidate" pull app
if ! cmp -s compose.yaml "$candidate"; then
  chmod --reference=compose.yaml "$candidate"
  chown --reference=compose.yaml "$candidate"
  cp -p -- compose.yaml compose.previous.yaml
  mv -- "$candidate" compose.yaml
fi
"${compose[@]}" up -d --no-deps app
curl -fsS --retry 20 --retry-connrefused --retry-all-errors --retry-delay 2 \
  --max-time 5 http://127.0.0.1:8080/actuator/health
