# ── Stage 1: build the React frontend ────────────────────────────────────────
FROM node:22-alpine AS builder
WORKDIR /app

COPY package*.json ./
RUN npm ci

COPY . .
# VITE_* vars are inlined into the built JS at this step — a runtime `environment:`
# entry in docker-compose.yml can't reach back into bytes already written to dist/.
ARG VITE_PERSIST_API_KEY
ENV VITE_PERSIST_API_KEY=$VITE_PERSIST_API_KEY
RUN npm run build

# ── Stage 2: production image ─────────────────────────────────────────────────
FROM node:22-alpine
WORKDIR /app

# Install only runtime dependencies (express + peer packages)
COPY package*.json ./
RUN npm ci --omit=dev

# Bring in the built frontend and the server
COPY --from=builder /app/dist ./dist
COPY server/ ./server/

ENV NODE_ENV=production
ENV PORT=8080
EXPOSE 8080

HEALTHCHECK --interval=60s --timeout=5s --start-period=10s --retries=3 \
  CMD node -e "fetch('http://localhost:'+(process.env.PORT||8080)+'/api/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"

CMD ["node", "server/index.js"]
