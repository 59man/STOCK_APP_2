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

The image is public, so it carries no API key: never add a key build arg. The server generates its own
key on first start (see Auth in `CLAUDE.md`). If `docker build` fails with `operation not supported` on
a veth pair, add `--network host`.

```bash
docker build -t 59man/stock-tracker:latest .
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

Stop, remove, and re-run with the flags you just read. The key lives in the backups mount, so it
survives this. Do not pass `-e PERSIST_API_KEY`: it would override the generated key and block rotation.

```bash
ssh -q casa 'sudo -n docker stop stock-tracker && sudo -n docker rm stock-tracker && \
  sudo -n docker run -d --name stock-tracker -p 4000:8080 \
    -v /DATA/stock-tracker/data.json:/app/server/data.json \
    -v /DATA/stock-tracker/backups:/app/server/backups \
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
ssh -q casa 'KEY=$(sudo -n docker exec stock-tracker node server/index.js show-key) && curl -s -H "X-API-Key: $KEY" http://localhost:4000/api/persist/stock_tracker_portfolios'
```

The last check is the one that matters: confirm the four real portfolio names — Main Portfolio,
rodice, jonas, Marek — are still present. A healthy container serving an empty store is a failed
deploy, usually a wrong or missing volume mount.

To change the key: `ssh -q casa "sudo -n docker exec stock-tracker node server/index.js rotate-key"`. It
is live at once, with no rebuild or restart; every browser and phone then needs the new key once.

## Fallback: build on the NAS

Use this only if Docker on this machine is broken. Clone or update the repo on the NAS
(`~/stock-tracker-build`, `git fetch origin && git reset --hard origin/main`), `sudo docker build`, then stop/rm/run as above. This
path does not update Docker Hub, so the `:latest` tag goes stale — note that if you use it.
