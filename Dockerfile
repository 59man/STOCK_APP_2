# ── Stage 1: build the React frontend ────────────────────────────────────────
FROM node:22-alpine AS builder
WORKDIR /app

COPY package*.json ./
RUN npm ci

COPY . .
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
  CMD node -e "fetch('http://localhost:'+(process.env.PORT||8080)+'/api/persist/stock_tracker_portfolios').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"

CMD ["node", "server/index.js"]
