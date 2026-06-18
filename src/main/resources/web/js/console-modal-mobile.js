(function () {
    const byId = (id) => document.getElementById(id);

    let pending = [];
    let scheduled = false;
    let snapshotInFlight = false;
    let logBuffer = [];
    let currentFilter = '';
    let currentFilterNodeId = 'local';
    let snapshotText = '';
    let modelSnapshots = {};
    const MAX_BUFFER = 5000;

    function matchFilter(modelId) {
        if (!currentFilter) return true;
        return (modelId || 'system') === currentFilter;
    }

    function getCacheKey() {
        if (!currentFilter) return '';
        if (currentFilter === 'system') return 'system';
        if (currentFilterNodeId && currentFilterNodeId !== 'local') return currentFilter + '|||' + currentFilterNodeId;
        return currentFilter;
    }

    function setLogFilter(filter, nodeId) {
        const plainModelId = filter && filter.includes('|||') ? filter.split('|||')[0] : (filter || '');
        currentFilter = plainModelId;
        currentFilterNodeId = nodeId || 'local';
        const sel = document.getElementById('logFilterSelect');
        if (sel) sel.value = filter || '';
        const cacheKey = getCacheKey();
        if (currentFilter && currentFilter !== 'system' && !modelSnapshots[cacheKey]) {
            const url = currentFilterNodeId !== 'local'
                ? '/api/sys/model-log?modelId=' + encodeURIComponent(currentFilter) + '&nodeId=' + encodeURIComponent(currentFilterNodeId)
                : '/api/sys/model-log?modelId=' + encodeURIComponent(currentFilter);
            fetch(url)
                .then(function (r) { return r.text(); })
                .then(function (text) {
                    modelSnapshots[cacheKey] = text || '';
                    renderFiltered();
                })
                .catch(function () {
                    modelSnapshots[cacheKey] = '';
                    renderFiltered();
                });
        }
        renderFiltered();
    }

    function renderFiltered() {
        const els = getEls();
        if (!els.content) return;
        const stay = nearBottom(els.container);
        let chunk = '';
        let matched = 0;
        const cacheKey = getCacheKey();
        if (!currentFilter || currentFilter === 'system') {
            chunk = snapshotText || '';
        } else if (cacheKey && modelSnapshots[cacheKey]) {
            chunk = modelSnapshots[cacheKey];
        }
        for (let i = 0; i < logBuffer.length; i++) {
            if (matchFilter(logBuffer[i].modelId)) {
                chunk += logBuffer[i].text;
                matched++;
            }
        }
        els.content.textContent = chunk;
        if (stay) scrollBottom(els.container);
    }

    function getEls() {
        return {
            modal: byId('consoleModal'),
            content: byId('consoleContent'),
            container: byId('logContainer'),
            status: byId('consoleStatusText'),
            refreshBtn: byId('refreshConsoleBtn')
        };
    }

    function nearBottom(container) {
        if (!container) return true;
        return Math.abs(container.scrollHeight - container.scrollTop - container.clientHeight) < 80;
    }

    function scrollBottom(container) {
        if (!container) return;
        container.scrollTop = container.scrollHeight;
    }

    async function fetchConsole() {
        const els = getEls();
        if (els.status) els.status.textContent = t('page.console.status.loading', '加载中...');
        snapshotInFlight = true;
        try {
            const res = await fetch('/api/sys/console');
            const text = await res.text();
            snapshotText = text;
            snapshotInFlight = false;
            renderFiltered();
            if (els.status) {
                els.status.textContent = t('page.console.status.updated', '已更新 · ') + new Date().toLocaleTimeString() + ' · ' + text.length;
            }
        } catch (e) {
            snapshotInFlight = false;
            if (els.status) els.status.textContent = t('page.console.status.load_failed', '加载失败');
        }
    }

    function openConsoleModal() {
        const els = getEls();
        if (!els.modal) return;
        els.modal.classList.add('show');
        if (typeof populateLogFilter === 'function') populateLogFilter('local');
        fetchConsole().finally(() => {
            setTimeout(() => scrollBottom(els.container), 120);
        });
    }

    function flushAppend() {
        scheduled = false;
        pending = [];
        renderFiltered();
    }

    function appendLogLine(line, timestamp, modelId) {
        const els = getEls();
        if (!els.modal || !els.modal.classList.contains('show')) return;
        const clean = (line == null ? '' : String(line)).replace(/\r/g, '');
        const withNl = clean.endsWith('\n') ? clean : clean + '\n';
        const entry = { text: withNl, ts: typeof timestamp === 'number' ? timestamp : 0, modelId: modelId || 'system' };
        logBuffer.push(entry);
        if (logBuffer.length > MAX_BUFFER) {
            logBuffer.splice(0, logBuffer.length - MAX_BUFFER);
        }
        pending.push(entry);
        if (snapshotInFlight) return;
        if (!scheduled) {
            scheduled = true;
            requestAnimationFrame(flushAppend);
        }
    }

    function stopConsoleAuto() {}

    function bind() {
        const els = getEls();
        if (els.refreshBtn) els.refreshBtn.addEventListener('click', fetchConsole);
    }

    document.addEventListener('DOMContentLoaded', function () {
        bind();
    });

    window.openConsoleModal = openConsoleModal;
    window.appendLogLine = appendLogLine;
    window.setLogFilter = setLogFilter;
    window.stopConsoleAuto = stopConsoleAuto;
})();