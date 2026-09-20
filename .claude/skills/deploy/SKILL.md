---
description: Deploy the web app to production — build and push the Docker image to Docker Hub, then have the CasaOS NAS pull it and restart. Use whenever asked to deploy, ship, release, or push to the server / NAS / production.
---

# Deploy stock-tracker to production

Production is a Docker container named `stock-tracker` on a CasaOS NAS at `192.168.0.103`, reachable
over SSH as the `casa` host alias. The deploy is **one routine, two steps** — build and push the image
here, then have the NAS pull it. Do not split these into separate decisions and do not ask about the
registry push; it is part of the routine.

Do not deploy uncommitted work without saying so. If the working tree is dirty, mention what is
uncommitted and proceed only if that is what was asked for.

## Step 0 — regenerate the lockfile if dependencies changed

Skip this only if `package.json` and `package-lock.json` are both untouched since the last successful
deploy. This machine runs a newer npm than the image's `node:22-alpine` (npm 10.9.8); the newer npm
omits optional platform packages the older one still expects, so the image build dies with
`npm error Missing: @esbuild/win32-x64@... from lock file` while everything passes locally.

```bash
docker run --rm -v "$PWD":/app -w /app node:22-alpine \
  sh -c "npm install --package-lock-only --ignore-scripts"

# verify it really installs under that npm before building the image
docker run --rm -v "$PWD":/src:ro -w /work node:22-alpine \
  sh -c "cp /src/package.json /src/package-lock.json . && npm ci --omit=dev"
```

Commit the regenerated lockfile. The local npm reads it fine, so this costs nothing locally.

## Step 1 — build and push from this machine

`VITE_PERSIST_API_KEY` is inlined into the bundle at build time, so it must be a build arg, sourced
from `.env` in the same command. Never type the key literal into a command — Claude Code's auto-mode
classifier blocks commands containing what look like secrets.

```bash
set -a && . ./.env && set +a
docker build -t 59man/stock-tracker:latest --build-arg VITE_PERSIST_API_KEY="$VITE_PERSIST_API_KEY" .
docker push 59man/stock-tracker:latest
```

The user is already logged in to Docker Hub here as `59man`. Confirm with `docker info | grep Username`
if a push fails, but never run `docker login` — that is the user's action.

## Step 2 — pull and restart on the NAS

`sudo -n docker` is required on that host; plain `docker` fails with a socket permission error. A pull
needs no Docker Hub login (the repo is public), which is why this path works even though no Docker Hub
credentials exist on the NAS.

Read the running container's flags before replacing it rather than trusting the documented example:

```bash
ssh -q casa "sudo -n docker inspect stock-tracker --format '{{range .Mounts}}{{.Source}} -> {{.Destination}}{{println}}{{end}}Ports: {{.HostConfig.PortBindings}}{{println}}RestartPolicy: {{.HostConfig.RestartPolicy}}{{println}}LogOpts: {{.HostConfig.LogConfig}}'"
ssh -q casa "sudo -n docker pull 59man/stock-tracker:latest"
```

Recover the runtime `PERSIST_API_KEY` from the old container's own env so the value never appears in a
command you typed, then stop, remove, and re-run with the flags you just read:

```bash
ssh -q casa 'KEY=$(sudo -n docker inspect stock-tracker --format "{{range .Config.Env}}{{println .}}{{end}}" | grep "^PERSIST_API_KEY=" | cut -d= -f2-) && \
  sudo -n docker stop stock-tracker && sudo -n docker rm stock-tracker && \
  sudo -n docker run -d --name stock-tracker -p 4000:8080 \
    -v /DATA/stock-tracker/data.json:/app/server/data.json \
    -v /DATA/stock-tracker/backups:/app/server/backups \
    -e PERSIST_API_KEY="$KEY" \
    --log-opt max-size=10m --log-opt max-file=3 \
    --restart unless-stopped \
    59man/stock-tracker:latest'
```

Always use absolute paths for the volume mounts. The mounts above are the current known-good values,
but verify them against the `inspect` output each time.

Always add `-q` to `ssh` — OpenSSH prints a harmless post-quantum-key-exchange banner on every
connection that otherwise lands in stdout and breaks anything parsing the output.

## Step 3 — verify before calling it done

All three, not just the first:

```bash
ssh -q casa "sudo -n docker ps --filter name=stock-tracker"          # expect: healthy
ssh -q casa "curl -s http://localhost:4000/api/health"               # expect: 200
ssh -q casa "curl -s -H 'X-API-Key: \$KEY' http://localhost:4000/api/persist/stock_tracker_portfolios"
```

The last check is the one that matters: confirm the four real portfolio names — Main Portfolio,
rodice, jonas, Marek — are still present. A healthy container serving an empty store is a failed
deploy, usually a wrong or missing volume mount.

If `VITE_PERSIST_API_KEY` changed, the image must be rebuilt — it is baked in at build time. Only
`PERSIST_API_KEY` (the server-side runtime check) can change with a plain restart.

## Fallback: build on the NAS

Use this only if Docker on this machine is broken. Clone or update the repo on the NAS
(`~/stock-tracker-build`, `git fetch origin && git reset --hard origin/main`), `scp` `.env` to a temp
path, source it, `sudo docker build`, delete the temp file immediately, then stop/rm/run as above. This
path does not update Docker Hub, so the `:latest` tag goes stale — note that if you use it.
