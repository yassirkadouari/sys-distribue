/**
 * Byzantine Consensus Dashboard — Real-time PBFT Monitor
 * Polls metrics from each node's HTTP API and renders topology, charts, and logs.
 */

// ============================================================
// Configuration
// ============================================================
const CONFIG = {
    // Node metrics endpoints — Docker Compose maps these ports
    nodes: [
        { id: 0, name: 'Node 0', url: 'http://localhost:8080' },
        { id: 1, name: 'Node 1', url: 'http://localhost:8081' },
        { id: 2, name: 'Node 2', url: 'http://localhost:8082' },
        { id: 3, name: 'Node 3', url: 'http://localhost:8083' },
    ],
    pollInterval: 2000, // 2 seconds
    maxChartPoints: 50,
};

// ============================================================
// State
// ============================================================
const state = {
    nodesData: {},
    latencyHistory: [],
    throughputHistory: [],
    events: [],
    connected: new Set(),
};

// ============================================================
// Clock
// ============================================================
function updateClock() {
    const now = new Date();
    const el = document.getElementById('clock');
    if (el) el.textContent = now.toLocaleTimeString('fr-FR');
}
setInterval(updateClock, 1000);
updateClock();

// ============================================================
// Data Fetching
// ============================================================
async function fetchNodeData(node) {
    try {
        const [metricsRes, statusRes, consensusRes] = await Promise.all([
            fetch(`${node.url}/metrics`, { signal: AbortSignal.timeout(3000) }),
            fetch(`${node.url}/status`, { signal: AbortSignal.timeout(3000) }),
            fetch(`${node.url}/consensus`, { signal: AbortSignal.timeout(3000) }),
        ]);

        const metrics = await metricsRes.json();
        const status = await statusRes.json();
        const consensus = await consensusRes.json();

        state.nodesData[node.id] = { metrics, status, consensus, online: true, lastSeen: Date.now() };
        state.connected.add(node.id);

        return true;
    } catch (e) {
        // Node is offline
        if (state.nodesData[node.id]) {
            state.nodesData[node.id].online = false;
        } else {
            state.nodesData[node.id] = { online: false };
        }
        state.connected.delete(node.id);
        return false;
    }
}

async function pollAll() {
    const results = await Promise.all(CONFIG.nodes.map(n => fetchNodeData(n)));
    const onlineCount = results.filter(Boolean).length;

    // Update cluster status
    const statusBadge = document.getElementById('cluster-status');
    const statusText = document.getElementById('status-text');
    if (onlineCount === CONFIG.nodes.length) {
        statusBadge.className = 'status-badge connected';
        statusText.textContent = `${onlineCount}/${CONFIG.nodes.length} Online`;
    } else if (onlineCount > 0) {
        statusBadge.className = 'status-badge';
        statusText.textContent = `${onlineCount}/${CONFIG.nodes.length} Online`;
    } else {
        statusBadge.className = 'status-badge error';
        statusText.textContent = 'Disconnected';
    }

    updateDashboard();
}

// ============================================================
// Dashboard Update
// ============================================================
function updateDashboard() {
    updateStats();
    updateTopology();
    updateConsensusTable();
    updateNodeCards();
    updateCharts();
    updateByzantineEvents();
}

// ---- Stats ----
function updateStats() {
    let totalTx = 0, totalThroughput = 0, totalLatency = 0, latencyCount = 0, byzantineEvents = 0;

    for (const [id, data] of Object.entries(state.nodesData)) {
        if (!data.online || !data.metrics) continue;
        const perf = data.metrics.performance || {};
        totalTx = Math.max(totalTx, perf.totalTransactions || 0);
        totalThroughput = Math.max(totalThroughput, perf.throughputTxPerSec || 0);
        if (perf.averageLatencyMs > 0) {
            totalLatency += perf.averageLatencyMs;
            latencyCount++;
        }
        byzantineEvents += (data.metrics.byzantineEvents || []).length;
    }

    animateValue('total-tx', totalTx);
    animateValue('throughput', totalThroughput.toFixed(1));
    animateValue('avg-latency', latencyCount > 0 ? Math.round(totalLatency / latencyCount) : 0);
    animateValue('byzantine-count', byzantineEvents);

    // Record for charts
    const now = Date.now();
    state.latencyHistory.push({ time: now, value: latencyCount > 0 ? totalLatency / latencyCount : 0 });
    state.throughputHistory.push({ time: now, value: totalThroughput });

    if (state.latencyHistory.length > CONFIG.maxChartPoints) state.latencyHistory.shift();
    if (state.throughputHistory.length > CONFIG.maxChartPoints) state.throughputHistory.shift();

    // Update view
    for (const [id, data] of Object.entries(state.nodesData)) {
        if (data.online && data.status) {
            document.getElementById('view-number').textContent = data.status.currentView || 0;
            break;
        }
    }
}

function animateValue(elementId, newValue) {
    const el = document.getElementById(elementId);
    if (el) el.textContent = newValue;
}

// ---- Topology Canvas ----
function updateTopology() {
    const canvas = document.getElementById('topology-canvas');
    const ctx = canvas.getContext('2d');
    const dpr = window.devicePixelRatio || 1;

    canvas.width = canvas.offsetWidth * dpr;
    canvas.height = canvas.offsetHeight * dpr;
    ctx.scale(dpr, dpr);

    const w = canvas.offsetWidth;
    const h = canvas.offsetHeight;
    const cx = w / 2;
    const cy = h / 2;
    const radius = Math.min(w, h) * 0.32;

    ctx.clearRect(0, 0, w, h);

    // Calculate node positions in a circle
    const nodePositions = CONFIG.nodes.map((node, i) => {
        const angle = (i / CONFIG.nodes.length) * Math.PI * 2 - Math.PI / 2;
        return {
            x: cx + Math.cos(angle) * radius,
            y: cy + Math.sin(angle) * radius,
            id: node.id,
            name: node.name,
        };
    });

    // Draw connection lines
    for (let i = 0; i < nodePositions.length; i++) {
        for (let j = i + 1; j < nodePositions.length; j++) {
            const a = nodePositions[i];
            const b = nodePositions[j];
            const aOnline = state.nodesData[a.id]?.online;
            const bOnline = state.nodesData[b.id]?.online;

            ctx.beginPath();
            ctx.moveTo(a.x, a.y);
            ctx.lineTo(b.x, b.y);

            if (aOnline && bOnline) {
                ctx.strokeStyle = 'rgba(0, 240, 255, 0.15)';
                ctx.lineWidth = 1.5;
            } else {
                ctx.strokeStyle = 'rgba(100, 116, 139, 0.08)';
                ctx.lineWidth = 1;
                ctx.setLineDash([4, 4]);
            }
            ctx.stroke();
            ctx.setLineDash([]);
        }
    }

    // Animate message flow (glowing dots on connections)
    const time = Date.now() / 1000;
    for (let i = 0; i < nodePositions.length; i++) {
        for (let j = i + 1; j < nodePositions.length; j++) {
            const a = nodePositions[i];
            const b = nodePositions[j];
            if (!state.nodesData[a.id]?.online || !state.nodesData[b.id]?.online) continue;

            const t = ((time * 0.5 + i * 0.3 + j * 0.2) % 1);
            const px = a.x + (b.x - a.x) * t;
            const py = a.y + (b.y - a.y) * t;

            ctx.beginPath();
            ctx.arc(px, py, 2, 0, Math.PI * 2);
            ctx.fillStyle = 'rgba(0, 240, 255, 0.5)';
            ctx.fill();
        }
    }

    // Draw nodes
    for (const pos of nodePositions) {
        const data = state.nodesData[pos.id];
        const online = data?.online;
        const isLeader = data?.status?.currentLeader === pos.id;
        const isByzantine = data?.status?.byzantine;

        // Glow effect
        if (online) {
            const gradient = ctx.createRadialGradient(pos.x, pos.y, 0, pos.x, pos.y, 35);
            if (isByzantine) {
                gradient.addColorStop(0, 'rgba(247, 37, 133, 0.2)');
            } else if (isLeader) {
                gradient.addColorStop(0, 'rgba(0, 240, 255, 0.2)');
            } else {
                gradient.addColorStop(0, 'rgba(124, 58, 237, 0.15)');
            }
            gradient.addColorStop(1, 'rgba(0, 0, 0, 0)');
            ctx.beginPath();
            ctx.arc(pos.x, pos.y, 35, 0, Math.PI * 2);
            ctx.fillStyle = gradient;
            ctx.fill();
        }

        // Node circle
        ctx.beginPath();
        ctx.arc(pos.x, pos.y, 22, 0, Math.PI * 2);

        if (!online) {
            ctx.fillStyle = '#1a2236';
            ctx.strokeStyle = 'rgba(100, 116, 139, 0.3)';
        } else if (isByzantine) {
            ctx.fillStyle = 'rgba(247, 37, 133, 0.2)';
            ctx.strokeStyle = '#f72585';
        } else if (isLeader) {
            ctx.fillStyle = 'rgba(0, 240, 255, 0.15)';
            ctx.strokeStyle = '#00f0ff';
        } else {
            ctx.fillStyle = 'rgba(124, 58, 237, 0.12)';
            ctx.strokeStyle = '#7c3aed';
        }

        ctx.lineWidth = 2;
        ctx.fill();
        ctx.stroke();

        // Node ID text
        ctx.fillStyle = online ? '#e2e8f0' : '#64748b';
        ctx.font = 'bold 13px Inter';
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillText(`N${pos.id}`, pos.x, pos.y);

        // Role label below
        ctx.font = '10px Inter';
        ctx.fillStyle = isByzantine ? '#f72585' : (isLeader ? '#00f0ff' : '#94a3b8');
        const roleText = !online ? 'OFFLINE' : (isByzantine ? 'BYZANTINE' : (isLeader ? 'LEADER' : 'REPLICA'));
        ctx.fillText(roleText, pos.x, pos.y + 34);

        // TX count above
        if (online && data?.status) {
            ctx.font = '9px JetBrains Mono';
            ctx.fillStyle = '#64748b';
            ctx.fillText(`${data.status.totalExecuted || 0} tx`, pos.x, pos.y - 32);
        }
    }
}

// ---- Consensus Table ----
function updateConsensusTable() {
    const tbody = document.getElementById('consensus-tbody');
    let allRounds = [];

    for (const [id, data] of Object.entries(state.nodesData)) {
        if (!data.online || !data.consensus || !data.consensus.rounds) continue;
        // Use the first online node's consensus log
        allRounds = data.consensus.rounds;
        document.getElementById('rounds-count').textContent = `${data.consensus.totalRounds || 0} rounds`;
        break;
    }

    if (allRounds.length === 0) {
        tbody.innerHTML = '<tr class="empty-row"><td colspan="7">Waiting for consensus rounds...</td></tr>';
        return;
    }

    tbody.innerHTML = allRounds.slice(0, 20).map(round => {
        const phaseClass = round.phase.toLowerCase().replace('_', '-');
        const statusIcon = round.phase === 'EXECUTED' ? '✅' :
                          round.phase === 'COMMITTED' ? '🔒' :
                          round.phase === 'PREPARED' ? '📝' : '⏳';

        return `<tr>
            <td style="color: var(--accent-cyan);">#${round.sequenceNumber}</td>
            <td>${round.viewNumber}</td>
            <td><span class="phase-badge ${phaseClass}">${round.phase}</span></td>
            <td>${round.prepareCount}/${CONFIG.nodes.length - 1}</td>
            <td>${round.commitCount}/${CONFIG.nodes.length}</td>
            <td>${round.latencyMs}ms</td>
            <td><span class="status-icon">${statusIcon}</span></td>
        </tr>`;
    }).join('');
}

// ---- Node Cards ----
function updateNodeCards() {
    const container = document.getElementById('node-cards');

    container.innerHTML = CONFIG.nodes.map(node => {
        const data = state.nodesData[node.id];
        const online = data?.online;
        const isLeader = data?.status?.currentLeader === node.id;
        const isByzantine = data?.status?.byzantine;
        const byzantineType = data?.status?.byzantineType || 'none';

        const cardClass = !online ? 'offline' : (isByzantine ? 'byzantine' : (isLeader ? 'leader' : ''));
        const roleClass = isByzantine ? 'byzantine' : (isLeader ? 'leader' : 'replica');
        const roleText = !online ? 'OFFLINE' : (isByzantine ? byzantineType.toUpperCase() : (isLeader ? 'LEADER' : 'REPLICA'));

        const perf = data?.metrics?.performance || {};

        return `<div class="node-card ${cardClass}">
            <div class="node-card-header">
                <span class="node-name">${node.name}</span>
                <span class="node-role ${roleClass}">${roleText}</span>
            </div>
            <div class="node-stats">
                <div class="node-stat-row">
                    <span class="label">Transactions</span>
                    <span class="value">${data?.status?.totalExecuted || 0}</span>
                </div>
                <div class="node-stat-row">
                    <span class="label">Throughput</span>
                    <span class="value">${(perf.throughputTxPerSec || 0).toFixed(1)} tx/s</span>
                </div>
                <div class="node-stat-row">
                    <span class="label">Latency</span>
                    <span class="value">${perf.averageLatencyMs || 0} ms</span>
                </div>
                <div class="node-stat-row">
                    <span class="label">View</span>
                    <span class="value">${data?.status?.currentView ?? '-'}</span>
                </div>
                <div class="node-stat-row">
                    <span class="label">Port</span>
                    <span class="value">${data?.status?.port || '-'}</span>
                </div>
            </div>
        </div>`;
    }).join('');
}

// ---- Charts (Canvas-based) ----
function drawLineChart(canvasId, data, color, label, unit) {
    const canvas = document.getElementById(canvasId);
    const ctx = canvas.getContext('2d');
    const dpr = window.devicePixelRatio || 1;

    canvas.width = canvas.offsetWidth * dpr;
    canvas.height = canvas.offsetHeight * dpr;
    ctx.scale(dpr, dpr);

    const w = canvas.offsetWidth;
    const h = canvas.offsetHeight;
    const padding = { top: 20, right: 20, bottom: 30, left: 50 };
    const plotW = w - padding.left - padding.right;
    const plotH = h - padding.top - padding.bottom;

    ctx.clearRect(0, 0, w, h);

    if (data.length < 2) {
        ctx.fillStyle = '#64748b';
        ctx.font = '12px Inter';
        ctx.textAlign = 'center';
        ctx.fillText('Collecting data...', w / 2, h / 2);
        return;
    }

    const values = data.map(d => d.value);
    const maxVal = Math.max(...values, 1) * 1.2;
    const minVal = 0;

    // Grid lines
    ctx.strokeStyle = 'rgba(100, 116, 139, 0.08)';
    ctx.lineWidth = 1;
    for (let i = 0; i <= 4; i++) {
        const y = padding.top + (plotH * i / 4);
        ctx.beginPath();
        ctx.moveTo(padding.left, y);
        ctx.lineTo(w - padding.right, y);
        ctx.stroke();

        // Y-axis labels
        const val = maxVal - (maxVal - minVal) * (i / 4);
        ctx.fillStyle = '#64748b';
        ctx.font = '10px JetBrains Mono';
        ctx.textAlign = 'right';
        ctx.fillText(val.toFixed(0) + unit, padding.left - 6, y + 3);
    }

    // Draw line with gradient fill
    ctx.beginPath();
    for (let i = 0; i < data.length; i++) {
        const x = padding.left + (i / (data.length - 1)) * plotW;
        const y = padding.top + plotH - ((data[i].value - minVal) / (maxVal - minVal)) * plotH;
        if (i === 0) ctx.moveTo(x, y);
        else ctx.lineTo(x, y);
    }

    ctx.strokeStyle = color;
    ctx.lineWidth = 2;
    ctx.stroke();

    // Fill area under curve
    const lastX = padding.left + plotW;
    const baseline = padding.top + plotH;
    ctx.lineTo(lastX, baseline);
    ctx.lineTo(padding.left, baseline);
    ctx.closePath();

    const gradient = ctx.createLinearGradient(0, padding.top, 0, baseline);
    gradient.addColorStop(0, color.replace(')', ', 0.2)').replace('rgb', 'rgba'));
    gradient.addColorStop(1, color.replace(')', ', 0.01)').replace('rgb', 'rgba'));
    ctx.fillStyle = gradient;
    ctx.fill();

    // Draw dots on data points (last 5)
    for (let i = Math.max(0, data.length - 5); i < data.length; i++) {
        const x = padding.left + (i / (data.length - 1)) * plotW;
        const y = padding.top + plotH - ((data[i].value - minVal) / (maxVal - minVal)) * plotH;

        ctx.beginPath();
        ctx.arc(x, y, 3, 0, Math.PI * 2);
        ctx.fillStyle = color;
        ctx.fill();

        ctx.beginPath();
        ctx.arc(x, y, 5, 0, Math.PI * 2);
        ctx.strokeStyle = color;
        ctx.lineWidth = 1;
        ctx.stroke();
    }

    // Label
    ctx.fillStyle = '#94a3b8';
    ctx.font = '10px Inter';
    ctx.textAlign = 'left';
    ctx.fillText(label, padding.left, h - 6);
}

function updateCharts() {
    drawLineChart('latency-chart', state.latencyHistory, 'rgb(6, 214, 160)', 'Latency (ms)', 'ms');
    drawLineChart('throughput-chart', state.throughputHistory, 'rgb(124, 58, 237)', 'Throughput (tx/s)', '');
}

// ---- Byzantine Events ----
function updateByzantineEvents() {
    const eventsLog = document.getElementById('events-log');

    for (const [id, data] of Object.entries(state.nodesData)) {
        if (!data.online || !data.metrics) continue;
        const events = data.metrics.byzantineEvents || [];
        for (const event of events) {
            const key = `${event.suspectedNode}-${event.type}-${event.timestamp}`;
            if (!state.events.includes(key)) {
                state.events.push(key);
                addEvent('danger', `Node ${event.suspectedNode} — ${event.type}`, new Date(event.timestamp));
            }
        }
    }
}

function addEvent(type, message, time) {
    const eventsLog = document.getElementById('events-log');
    const timeStr = (time || new Date()).toLocaleTimeString('fr-FR');
    const div = document.createElement('div');
    div.className = `event-item ${type}`;
    div.innerHTML = `<span class="event-time">${timeStr}</span><span class="event-msg">${message}</span>`;
    eventsLog.insertBefore(div, eventsLog.firstChild);

    // Keep only last 50 events
    while (eventsLog.children.length > 50) {
        eventsLog.removeChild(eventsLog.lastChild);
    }
}

// Clear events
document.getElementById('clear-events')?.addEventListener('click', () => {
    const eventsLog = document.getElementById('events-log');
    eventsLog.innerHTML = '';
    state.events = [];
    addEvent('info', 'Events log cleared.');
});

// ============================================================
// Animation Loop for Topology
// ============================================================
function animateTopology() {
    if (Object.keys(state.nodesData).length > 0) {
        updateTopology();
    }
    requestAnimationFrame(animateTopology);
}
requestAnimationFrame(animateTopology);

// ============================================================
// Start Polling
// ============================================================
addEvent('info', 'Dashboard starting — connecting to cluster nodes...');

// Initial poll
pollAll();

// Periodic poll
setInterval(pollAll, CONFIG.pollInterval);

// Handle window resize for canvas
window.addEventListener('resize', () => {
    updateTopology();
    updateCharts();
});

console.log('🔗 Byzantine Consensus Dashboard initialized');
