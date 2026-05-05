# Engineering Blog Search — Frontend

React + Vite UI for the Java BlogServer search backend.

## Dev

```
cd frontend && npm install && npm run dev
```

Opens on http://localhost:5173. The app expects the backend at `$VITE_API_URL` (defaults to `http://localhost:8080`). Copy `.env.example` to `.env` and edit if your backend lives elsewhere. The backend must send `Access-Control-Allow-Origin: *` (or a matching origin) for the browser to accept responses cross-origin.

## Production deploy (Vercel)

```
npm run build
```

Then deploy `dist/` to Vercel (or any static host). In the Vercel project settings, set `VITE_API_URL` to the backend's public URL — it must be baked in at build time, not runtime, because Vite inlines `import.meta.env.VITE_*` constants during `vite build`.

The Java backend cannot run on Vercel: it is a JVM process that mmaps `blog_index.bin` and reads `blog_doc_meta.txt` / `blog_token_meta.txt` from a persistent disk, neither of which fits Vercel's serverless model. Host it on Render, Fly.io, or Railway and point `VITE_API_URL` at that deployment.

## Architecture

The frontend is a single-page React app that issues `GET /search?q=&top=` against the Java BlogServer and renders the JSON response. It holds no state beyond the current query and last response — there is no database, no auth, and no server-side rendering. The backend itself is stateless across requests but stateful on disk: the BM25 postings, document metadata, and PageRank scores are precomputed index files, not a live DB. Cross-origin access works because BlogServer sets `Access-Control-Allow-Origin` on every response; tighten this to the deployed frontend's origin before going public.
