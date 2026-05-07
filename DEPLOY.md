# Deploying to Render Free Tier

Single-JAR Java search engine. The 50 MB search index is hosted as a GitHub Release asset (avoids bloating the repo) and downloaded by `IndexBootstrap` on first start.

## Prerequisites
- Code pushed to a GitHub repo
- A Render account — sign in with GitHub at https://render.com
- The four index files locally: `blog_index.bin`, `blog_doc_meta.txt`, `blog_token_meta.txt`, `blog_embeddings.bin`
- Optional: GitHub CLI (`gh`) — alternative is the GitHub web UI

## Step 1 — Upload the index as a GitHub Release

```bash
gh release create v0.1 \
  blog_index.bin blog_doc_meta.txt blog_token_meta.txt blog_embeddings.bin \
  --title "Search index v0.1" \
  --notes "Inverted index, doc metadata, token metadata, dense embeddings for the engineering blog corpus."
```

Asset URLs follow the pattern:
```
https://github.com/<USER>/<REPO>/releases/download/v0.1/blog_index.bin
https://github.com/<USER>/<REPO>/releases/download/v0.1/blog_doc_meta.txt
https://github.com/<USER>/<REPO>/releases/download/v0.1/blog_token_meta.txt
https://github.com/<USER>/<REPO>/releases/download/v0.1/blog_embeddings.bin
```

(Without `gh`: GitHub web UI → Releases → Draft a new release → attach the three files → Publish.)

## Step 2 — Create the Render Web Service

Render dropped the native Java runtime; we use the **Docker** runtime via the `Dockerfile` in this repo (multi-stage: Maven builds the fat JAR in stage 1, Eclipse Temurin JRE 17 runs it in stage 2).

1. Render Dashboard → **New +** → **Web Service**
2. Connect your GitHub repo, pick the branch (`engineering-blogs` or `master`)
3. Configure:
   - **Name**: `engineering-blog-search` (becomes the public hostname)
   - **Region**: pick the one nearest you
   - **Branch**: the one you want to deploy from
   - **Runtime**: **Docker** (Render auto-detects `Dockerfile` at repo root — Build/Start commands disappear from the form because the Dockerfile owns them)
   - **Plan**: Free
   - **Health Check Path**: `/health`

4. Click **Advanced** → **Add Environment Variable**:
   - Key: `INDEX_RELEASE_URL`
   - Value: `https://github.com/<USER>/<REPO>/releases/download/v0.1`
   - (Optional, dense rerank) Key: `JAVA_TOOL_OPTIONS`, Value: `-Dsearch.reranker=dense`. The JVM picks this up automatically; flips on cosine rerank of the BM25 top-100 using `blog_embeddings.bin`. Skip this var to stay on plain BM25+PageRank.

5. Click **Create Web Service**.

## Step 3 — Watch the build, verify

The first build takes 5–10 min (Maven downloads dependencies, compiles). Logs in real time on the Render dashboard. You should see:

```
> mvn -q package -DskipTests
...
> java -Xmx400m -jar target/SearchEngine-1.0-SNAPSHOT.jar
Bootstrapping blog_index.bin from https://github.com/.../blog_index.bin
  -> 50000000 bytes
Bootstrapping blog_doc_meta.txt from https://github.com/...
  -> 1500000 bytes
Bootstrapping blog_token_meta.txt from https://github.com/...
  -> 3000000 bytes
Bootstrapping blog_embeddings.bin from https://github.com/...
  -> 21000000 bytes
Loaded 181799 tokens, 11196 docs, avg length 1696
Listening on http://localhost:10000
```

If `JAVA_TOOL_OPTIONS=-Dsearch.reranker=dense` is set, the first `/search` request triggers a one-time ~330 MB download of PyTorch native libs into `~/.djl.ai/` inside the container. Expect a 10–30 s cold-start latency on that first query; subsequent queries embed in ~50 ms.

Render reports `Live`. Visit `https://engineering-blog-search.onrender.com` — the all-Java UI loads, search works.

## Step 4 — Keep the free dyno warm (optional)

Render's free plan sleeps a service after 15 min of inactivity. First request after sleep takes ~30 s to wake. For demos / job-search hours, ping `/health` every 5 min:

1. Sign up at https://uptimerobot.com (free)
2. Add Monitor:
   - Type: HTTPS
   - URL: `https://<your-app>.onrender.com/health`
   - Interval: 5 min
3. Done. Service stays warm whenever UptimeRobot is hitting it.

## Updating the index later

Whenever you re-crawl and re-index:

1. Cut a new GitHub Release (e.g. `v0.2`) with the fresh `blog_*.{bin,txt}` files.
2. In Render → service → Environment → bump `INDEX_RELEASE_URL` to the new release URL.
3. Manually deploy. The bootstrap detects the existing files are stale (you'd need to delete them first or change filenames) — alternative: Render gives each deploy a fresh filesystem on the free tier, so a redeploy is enough.

## Memory caveat for dense rerank

The free Render tier is 512 MB RAM. Plain BM25+PageRank fits comfortably under the `-Xmx384m` heap in the Dockerfile. Dense rerank loads ~250 MB of PyTorch native libs (off-heap, but charged against the container's RAM cap) plus the ~80 MB embedding model into memory — combined with the JVM heap, you are within tens of MB of the limit. Symptoms of running over: container is killed mid-query with no Java exception, Render dashboard shows `Out of memory`, service flips to `Deploy failed` after a few restarts.

Mitigations if it OOMs:
- Lower `-Xmx384m` to `-Xmx256m` in the Dockerfile (the index is mostly mmap'd, smaller heap is fine).
- Drop dense rerank in production (unset `JAVA_TOOL_OPTIONS`). The eval delta vs. BM25+PR is small at 13 K docs.
- Upgrade Render to the Starter plan for more headroom.

## Why no Vercel?

Vercel hosts static frontends + Node/Edge serverless. This backend is a long-running JVM that loads ~12 MB of in-memory index at startup and keeps it warm. Vercel's serverless functions have cold-start, 50 MB code limit, ephemeral filesystem, and 10–30 s timeouts — none of that fits. Render, Fly.io, Railway, Cloud Run, or a small VPS are the realistic options. Render free tier is the simplest.
