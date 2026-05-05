import { useEffect, useRef, useState } from 'react';

const API_BASE = import.meta.env.VITE_API_URL || 'http://localhost:8080';
const DEBOUNCE_MS = 300;
const TOP_N = 10;

function truncate(s, n) {
  if (!s) return '';
  return s.length > n ? s.slice(0, n - 1) + '…' : s;
}

export default function App() {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [tookMs, setTookMs] = useState(0);
  const [totalResults, setTotalResults] = useState(0);
  const [hasSearched, setHasSearched] = useState(false);

  const abortRef = useRef(null);

  async function runSearch(q) {
    const trimmed = q.trim();
    if (!trimmed) {
      setResults([]);
      setError(null);
      setLoading(false);
      setHasSearched(false);
      return;
    }

    if (abortRef.current) abortRef.current.abort();
    const controller = new AbortController();
    abortRef.current = controller;

    setLoading(true);
    setError(null);
    setHasSearched(true);

    try {
      const url = `${API_BASE}/search?q=${encodeURIComponent(trimmed)}&top=${TOP_N}`;
      const res = await fetch(url, { signal: controller.signal });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = await res.json();
      setResults(Array.isArray(data.results) ? data.results : []);
      setTookMs(typeof data.tookMs === 'number' ? data.tookMs : 0);
      setTotalResults(typeof data.totalResults === 'number' ? data.totalResults : 0);
    } catch (e) {
      if (e.name === 'AbortError') return;
      setError(e.message || 'Search failed');
      setResults([]);
      setTookMs(0);
      setTotalResults(0);
    } finally {
      if (abortRef.current === controller) {
        setLoading(false);
      }
    }
  }

  useEffect(() => {
    const handle = setTimeout(() => {
      runSearch(query);
    }, DEBOUNCE_MS);
    return () => clearTimeout(handle);
  }, [query]);

  function onSubmit(e) {
    e.preventDefault();
    runSearch(query);
  }

  return (
    <div className="app">
      <header className="header">
        <h1 className="title">Engineering Blog Search</h1>
        <form onSubmit={onSubmit} className="search-form" role="search">
          <input
            type="search"
            className="search-input"
            placeholder="Search engineering blogs"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            autoFocus
            autoComplete="off"
            spellCheck="false"
          />
        </form>
      </header>

      <main className="main">
        {!hasSearched && !loading && (
          <p className="hint">Try queries like &laquo;rust async&raquo;, &laquo;kafka&raquo;, or &laquo;distributed tracing&raquo;.</p>
        )}

        {loading && <p className="status">searching&hellip;</p>}

        {error && <p className="error">Error: {error}</p>}

        {!loading && !error && hasSearched && results.length === 0 && (
          <p className="status">No results for &laquo;{query.trim()}&raquo;</p>
        )}

        {!error && results.length > 0 && (
          <>
            <ol className="results">
              {results.map((r) => (
                <li key={r.docId} className="result">
                  <div className="result-main">
                    <a
                      className="result-title"
                      href={r.url}
                      target="_blank"
                      rel="noopener noreferrer"
                    >
                      {r.title || '(untitled)'}
                    </a>
                    {r.company && <div className="result-company">{r.company}</div>}
                    <div className="result-url">{truncate(r.url, 80)}</div>
                  </div>
                  <div className="result-score" title="Relevance score">
                    {typeof r.score === 'number' ? r.score.toFixed(2) : ''}
                  </div>
                </li>
              ))}
            </ol>
            <footer className="footer">
              {totalResults} results in {tookMs}ms
            </footer>
          </>
        )}
      </main>
    </div>
  );
}
