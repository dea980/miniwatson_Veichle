const API = 'http://localhost:8080';

async function askRAG() {
    const question = document.getElementById('question').value;
    if (!question) return;

    document.getElementById('rag-loading').classList.remove('hidden');
    document.getElementById('rag-answer').classList.add('hidden');
    document.getElementById('rag-sources').classList.add('hidden');

    try {
        const res = await fetch(`${API}/api/rag/ask`, {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({question})
        });
        const data = await res.json();

        document.getElementById('rag-loading').classList.add('hidden');
        document.getElementById('rag-answer').classList.remove('hidden');
        document.getElementById('rag-answer').textContent = data.answer || '(No answer)';

        const sourcesList = document.getElementById('sources-list');
        sourcesList.innerHTML = (data.sources || []).map(s => `
            <div class="source-item">
                <a href="${s.url}" target="_blank">${s.title}</a>
                <div class="summary">${s.summary.substring(0, 150)}...</div>
            </div>
        `).join('');
        document.getElementById('rag-sources').classList.remove('hidden');
    } catch (e) {
        document.getElementById('rag-loading').classList.add('hidden');
        alert('Error: ' + e.message);
    }
}

async function ingestArticle() {
    const title = document.getElementById('ingest-title').value;
    if (!title) return;

    document.getElementById('ingest-result').textContent = '⏳ Ingesting...';

    try {
        const res = await fetch(`${API}/api/data/ingest?title=${encodeURIComponent(title)}`, {
            method: 'POST'
        });
        const data = await res.json();
        document.getElementById('ingest-result').innerHTML =
            `✅ Ingested: <b>${data.title}</b> (id: ${data.id})`;
        document.getElementById('ingest-title').value = '';
        loadArticles();
    } catch (e) {
        document.getElementById('ingest-result').textContent = '❌ Error: ' + e.message;
    }
}

async function loadArticles() {
    const res = await fetch(`${API}/api/data/articles`);
    const articles = await res.json();

    const list = document.getElementById('articles-list');
    list.innerHTML = articles.map(a => `
        <div class="article-item">
            <a href="${a.url}" target="_blank">#${a.id} ${a.title}</a>
            <div class="summary">${a.summary.substring(0, 200)}...</div>
            <div class="meta">Ingested: ${a.ingestedAt}</div>
        </div>
    `).join('');
}

async function loadLogs() {
    const res = await fetch(`${API}/api/governance/logs`);
    const logs = await res.json();

    const table = document.getElementById('logs-table');
    table.innerHTML = logs.reverse().map(l => `
        <tr>
            <td>${l.id}</td>
            <td title="${l.question}">${l.question.substring(0, 60)}...</td>
            <td><span class="model-badge">${l.model}</span></td>
            <td class="text-right">${l.latencyMs}</td>
            <td class="text-sm text-muted">${l.createdAt}</td>
        </tr>
    `).join('');
}

function showTab(tab) {
    const articles = document.getElementById('articles-section');
    const logs = document.getElementById('logs-section');
    const tabArticles = document.getElementById('tab-articles');
    const tabLogs = document.getElementById('tab-logs');

    articles.classList.toggle('hidden', tab !== 'articles');
    logs.classList.toggle('hidden', tab !== 'logs');
    tabArticles.classList.toggle('active', tab === 'articles');
    tabLogs.classList.toggle('active', tab === 'logs');

    if (tab === 'logs') loadLogs();
    else loadArticles();
}

loadArticles();

document.getElementById('question').addEventListener('keypress', e => {
    if (e.key === 'Enter') askRAG();
});
document.getElementById('ingest-title').addEventListener('keypress', e => {
    if (e.key === 'Enter') ingestArticle();
});