(() => {
  const DEBOUNCE_MS = 300;
  const TOP_N = 10;

  const input = document.getElementById('search-input');
  const form = document.getElementById('search-form');
  const hint = document.getElementById('hint');
  const status = document.getElementById('status');
  const error = document.getElementById('error');
  const results = document.getElementById('results');
  const footer = document.getElementById('footer');

  let debounceHandle = null;
  let activeController = null;

  function show(el) { el.hidden = false; }
  function hide(el) { el.hidden = true; }
  function setText(el, text) { el.textContent = text; }

  function truncate(s, n) {
    if (!s) return '';
    return s.length > n ? s.slice(0, n - 1) + '…' : s;
  }

  function clearResults() {
    results.replaceChildren();
    hide(footer);
  }

  function renderResults(data) {
    clearResults();
    const items = Array.isArray(data.results) ? data.results : [];

    if (items.length === 0) {
      setText(status, `No results for «${input.value.trim()}»`);
      show(status);
      return;
    }

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

      const score = document.createElement('div');
      score.className = 'result-score';
      score.title = 'Relevance score';
      score.textContent = typeof r.score === 'number' ? r.score.toFixed(2) : '';

      li.appendChild(main);
      li.appendChild(score);
      frag.appendChild(li);
    }
    results.appendChild(frag);

    setText(footer, `${data.totalResults} results in ${data.tookMs}ms`);
    show(footer);
  }

  async function runSearch(q) {
    const trimmed = q.trim();
    if (!trimmed) {
      hide(status); hide(error);
      clearResults();
      show(hint);
      return;
    }

    if (activeController) activeController.abort();
    activeController = new AbortController();

    hide(hint); hide(error);
    setText(status, 'searching…');
    show(status);

    try {
      const url = `/search?q=${encodeURIComponent(trimmed)}&top=${TOP_N}`;
      const res = await fetch(url, { signal: activeController.signal });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = await res.json();
      hide(status);
      renderResults(data);
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
})();
