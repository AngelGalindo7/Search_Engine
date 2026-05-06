(() => {
  const DEBOUNCE_MS = 300;
  const TOP_N = 10;

  const input = document.getElementById('search-input');
  const form = document.getElementById('search-form');
  const statsStrip = document.getElementById('stats-strip');
  const topLabel = document.getElementById('top-label');
  const status = document.getElementById('status');
  const error = document.getElementById('error');
  const results = document.getElementById('results');
  const footer = document.getElementById('footer');

  let debounceHandle = null;
  let activeController = null;
  let topResults = null;

  function show(el) { el.hidden = false; }
  function hide(el) { el.hidden = true; }
  function setText(el, text) { el.textContent = text; }

  function fmt(n) { return Number(n).toLocaleString(); }

  function truncate(s, n) {
    if (!s) return '';
    return s.length > n ? s.slice(0, n - 1) + '…' : s;
  }

  const MONTHS = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];

  function formatPostDate(iso) {
    if (!iso) return '';
    const m = iso.match(/^(\d{4})-(\d{2})-(\d{2})/);
    if (!m) return '';
    return `${MONTHS[parseInt(m[2], 10) - 1]} ${parseInt(m[3], 10)}, ${m[1]}`;
  }

  function clearResults() {
    results.replaceChildren();
    hide(footer);
    hide(topLabel);
  }

  function renderResults(items, showScore) {
    results.replaceChildren();
    const frag = document.createDocumentFragment();
    for (const r of items) {
      const li = document.createElement('li');
      li.className = 'result';

      const main = document.createElement('div');
      main.className = 'result-main';

      const title = document.createElement('a');
      title.className = 'result-title';
      title.href = r.url || '#';
      title.target = '_blank';
      title.rel = 'noopener noreferrer';
      title.textContent = r.title || '(untitled)';
      main.appendChild(title);

      if (r.company) {
        const company = document.createElement('div');
        company.className = 'result-company';
        company.textContent = r.company;
        main.appendChild(company);
      }

      const url = document.createElement('div');
      url.className = 'result-url';
      url.textContent = truncate(r.url, 80);
      main.appendChild(url);

      li.appendChild(main);

      if (showScore && typeof r.score === 'number') {
        const score = document.createElement('div');
        score.className = 'result-score';
        score.title = 'Relevance score';
        score.textContent = r.score.toFixed(2);
        li.appendChild(score);
      }

      frag.appendChild(li);
    }
    results.appendChild(frag);
  }

  function showTopAsDefault() {
    if (!topResults || topResults.length === 0) return;
    hide(status); hide(error); hide(footer);
    show(topLabel);
    renderResults(topResults, false);
  }

  async function loadStats() {
    try {
      const res = await fetch('/stats');
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const s = await res.json();
      const date = formatPostDate(s.latestPostDate);
      const parts = [
        `${fmt(s.totalDocs)} posts`,
        `${fmt(s.totalBlogs)} blogs`,
        `${fmt(s.totalTokens)} tokens`,
      ];
      if (date) parts.push(`latest ${date}`);
      setText(statsStrip, parts.join(' · '));
    } catch (e) {
      setText(statsStrip, 'corpus stats unavailable');
    }
  }

  async function loadTop() {
    try {
      const res = await fetch('/top?n=10');
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = await res.json();
      topResults = Array.isArray(data.results) ? data.results : [];
      if (!input.value.trim()) showTopAsDefault();
    } catch (e) {
      // silently leave the empty state alone
    }
  }

  async function runSearch(q) {
    const trimmed = q.trim();
    if (!trimmed) {
      hide(status); hide(error);
      showTopAsDefault();
      return;
    }

    if (activeController) activeController.abort();
    activeController = new AbortController();

    hide(error); hide(topLabel);
    setText(status, 'searching…');
    show(status);

    try {
      const url = `/search?q=${encodeURIComponent(trimmed)}&top=${TOP_N}`;
      const res = await fetch(url, { signal: activeController.signal });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = await res.json();
      hide(status);
      const items = Array.isArray(data.results) ? data.results : [];
      if (items.length === 0) {
        clearResults();
        setText(status, `No results for «${trimmed}»`);
        show(status);
        return;
      }
      renderResults(items, true);
      setText(footer, `${data.totalResults} results in ${data.tookMs}ms`);
      show(footer);
    } catch (e) {
      if (e.name === 'AbortError') return;
      hide(status);
      setText(error, `Error: ${e.message || 'Search failed'}`);
      show(error);
      clearResults();
    }
  }

  input.addEventListener('input', () => {
    clearTimeout(debounceHandle);
    debounceHandle = setTimeout(() => runSearch(input.value), DEBOUNCE_MS);
  });

  form.addEventListener('submit', (e) => {
    e.preventDefault();
    clearTimeout(debounceHandle);
    runSearch(input.value);
  });

  loadStats();
  loadTop();
})();
