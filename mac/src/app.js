// GhostClip Mac Frontend — Main Application
// Communicates with Tauri Rust backend via @tauri-apps/api

import { icons } from './icons.js';

// ============================================
// Tauri IPC Bridge
// ============================================

// In production, we use window.__TAURI__.core.invoke
// The withGlobalTauri=true in tauri.conf.json exposes __TAURI__ globally
function invoke(cmd, args) {
  if (window.__TAURI__ && window.__TAURI__.core) {
    return window.__TAURI__.core.invoke(cmd, args);
  }
  // Fallback for development/preview (no Tauri runtime)
  console.warn(`[GhostClip] Tauri not available, skipping invoke: ${cmd}`);
  return Promise.resolve(null);
}

function listen(event, callback) {
  if (window.__TAURI__ && window.__TAURI__.event) {
    return window.__TAURI__.event.listen(event, callback);
  }
  console.warn(`[GhostClip] Tauri not available, skipping listen: ${event}`);
  return Promise.resolve(() => {});
}

function emit(event, payload) {
  if (window.__TAURI__ && window.__TAURI__.event) {
    return window.__TAURI__.event.emit(event, payload);
  }
  console.warn(`[GhostClip] Tauri not available, skipping emit: ${event}`);
  return Promise.resolve();
}

// ============================================
// State Management
// ============================================

const state = {
  // Connection state: 'connected' | 'cloud' | 'disconnected'
  connectionState: 'disconnected',
  // Device name
  deviceName: '',
  // Connection type label
  connectionLabel: '',
  // Pairing state: 'waiting_pair' | 'paired'
  pairingState: 'waiting_pair',
  // Paired device name (from Android)
  pairedDeviceName: '',
  // MAC hash identifier (e.g. gc-a3f8e2c71b04)
  macHash: '',
  // QR code SVG data
  qrSvg: '',
  // Whether QR popup is shown
  qrPopupVisible: false,
  // Recent sync records
  recentSyncs: [],
  // Copy feedback: index of recently copied item
  copiedIndex: -1,
  copiedTimer: null,
  // Settings
  settings: {
    hotkey: '\u2318 \u21E7 C',
    hotkeyRaw: 'CmdOrCtrl+Shift+C',
    cloudUrl: '',
    cloudToken: '',
    cloudEnabled: true,
    notificationsEnabled: true,
  },
  // Debug logs
  debugLogs: [],
  // UI state
  currentView: 'dropdown', // 'dropdown' | 'settings'
  isRecordingHotkey: false,
  // Error display
  errorMessage: '',
  errorLevel: '', // 'error' | 'warning'
  errorTimer: null,
  // Settings validation error
  settingsError: '',
  // Persistent connection error from backend
  connectionError: '',
};

// ============================================
// Error Toast
// ============================================

function renderErrorToast() {
  if (!state.errorMessage) return '';
  const level = state.errorLevel === 'error' ? 'error' : 'warning';
  return `
    <div class="error-toast ${level}" data-action="dismiss-error">
      <span class="error-toast-text">${escapeHtml(state.errorMessage)}</span>
    </div>
  `;
}

function showError(message, level = 'warning') {
  state.errorMessage = message;
  state.errorLevel = level;
  if (state.errorTimer) clearTimeout(state.errorTimer);
  state.errorTimer = setTimeout(() => {
    state.errorMessage = '';
    state.errorLevel = '';
    state.errorTimer = null;
    render();
  }, 6000);
  render();
}

/**
 * Classify error message into 'error' or 'warning' level
 */
function classifyError(message) {
  const msg = message.toLowerCase();
  if (msg.includes('401') || msg.includes('token') || msg.includes('认证') || msg.includes('配置')) {
    return 'error';
  }
  return 'warning';
}

// ============================================
// View: Dropdown Panel
// ============================================

function renderDropdown() {
  const dotClass = state.connectionState === 'connected' ? '' :
                   state.connectionState === 'cloud' ? 'cloud' : 'disconnected';
  const statusClass = dotClass;
  const statusText = state.connectionState === 'connected' ? '\u5DF2\u8FDE\u63A5' :
                     state.connectionState === 'cloud' ? '\u4E91\u7AEF\u540C\u6B65' : '\u672A\u8FDE\u63A5';

  const connIcon = state.connectionState === 'cloud' ? 'cloud' : 'wifi';
  const connIconClass = state.connectionState === 'cloud' ? 'cloud' : '';

  // Device info section (only shown when connected)
  const deviceInfoHtml = state.connectionState !== 'disconnected' ? `
    <div class="dev-info">
      <div class="dev-row">
        <span class="icon">${icons.smartphone}</span>
        <span class="dev-name">${escapeHtml(state.deviceName || 'Android')}</span>
      </div>
      <div class="conn-row">
        <span class="icon ${connIconClass}">${icons[connIcon]}</span>
        <span class="conn-label">${escapeHtml(state.connectionLabel || '\u7B49\u5F85\u8FDE\u63A5...')}</span>
      </div>
    </div>
    <div class="divider"></div>
  ` : '';

  // Persistent connection error banner
  const connErrorHtml = state.connectionError ? `
    <div class="conn-error">${escapeHtml(state.connectionError)}</div>
  ` : '';

  // Recent sync records (3.7: click to copy + hover)
  let recentHtml = '';
  if (state.recentSyncs.length === 0) {
    recentHtml = `
      <div class="recent-empty">
        <span class="icon">${icons.clipboard}</span>
        <span>\u6682\u65E0\u540C\u6B65\u8BB0\u5F55</span>
      </div>
    `;
  } else {
    recentHtml = state.recentSyncs.map((sync, index) => {
      const isIncoming = sync.direction === 'incoming';
      const icon = isIncoming ? icons.arrowDownLeft : icons.arrowUpRight;
      const iconClass = isIncoming ? 'incoming' : 'outgoing';
      const isCopied = state.copiedIndex === index;
      return `
        <div class="recent-row copyable${isCopied ? ' copied' : ''}" title="${escapeHtml(sync.text)}" data-action="copy-recent" data-index="${index}">
          <span class="icon ${iconClass}">${icon}</span>
          <span class="recent-text">${isCopied ? '\u2705 \u5DF2\u590D\u5236' : escapeHtml(truncate(sync.text, 40))}</span>
          <span class="recent-time">${escapeHtml(formatTimeAgo(sync.timestamp))}</span>
        </div>
      `;
    }).join('');
  }

  return `
    <div id="dropdown-view">
      <div class="dropdown-panel">
        ${renderErrorToast()}
        <div class="dd-header">
          <div class="dd-left">
            <div class="dd-dot ${dotClass}"></div>
            <span class="dd-title">GhostClip</span>
          </div>
          <span class="dd-status ${statusClass}">${statusText}</span>
        </div>
        <div class="divider"></div>
        ${deviceInfoHtml}
        ${connErrorHtml}
        <div class="recent-section">
          <span class="section-label">\u6700\u8FD1\u540C\u6B65</span>
          ${recentHtml}
        </div>
        <div class="divider"></div>
        <div class="action-section">
          <div class="action-row" data-action="send">
            <span class="icon">${icons.send}</span>
            <span class="text">\u53D1\u9001\u526A\u8D34\u677F\u5230 Android</span>
            <span class="shortcut">${escapeHtml(state.settings.hotkey)}</span>
          </div>
          <div class="action-row" data-action="settings">
            <span class="icon">${icons.settings}</span>
            <span class="text">\u8BBE\u7F6E\u2026</span>
          </div>
          ${state.pairingState === 'paired' ? `
          <div class="action-row" data-action="unpair">
            <span class="icon">${icons.unlink}</span>
            <span class="text">\u89E3\u9664\u914D\u5BF9</span>
          </div>
          ` : `
          <div class="action-row" data-action="pair">
            <span class="icon">${icons.qrCode}</span>
            <span class="text">\u914D\u5BF9\u2026</span>
          </div>
          `}
          <div class="divider" style="margin: 0 -8px; width: calc(100% + 16px);"></div>
          <div class="action-row quit" data-action="quit">
            <span class="icon">${icons.power}</span>
            <span class="text">\u9000\u51FA GhostClip</span>
          </div>
        </div>
        <div class="divider"></div>
        <div class="debug-section">
          <div class="debug-header">
            <span class="section-label">\u8C03\u8BD5\u65E5\u5FD7</span>
            <span class="debug-clear" data-action="clear-logs">\u6E05\u9664</span>
          </div>
          <div class="debug-logs" id="debug-logs">${state.debugLogs.length === 0 ?
            '<span class="debug-empty">\u6682\u65E0\u65E5\u5FD7</span>' :
            state.debugLogs.map(l => `<div class="debug-line">${escapeHtml(l)}</div>`).join('')
          }</div>
        </div>
      </div>
    </div>
  `;
}

// ============================================
// View: Settings Window
// ============================================

function renderSettings() {
  const hotkeyText = state.isRecordingHotkey ? '\u6309\u4E0B\u65B0\u5FEB\u6377\u952E...' : state.settings.hotkey;
  const badgeClass = state.isRecordingHotkey ? 'recording' : '';
  const notifToggleClass = state.settings.notificationsEnabled ? 'active' : '';

  const pairStatusText = state.pairingState === 'paired'
    ? `\u5DF2\u914D\u5BF9 \u00B7 ${escapeHtml(state.pairedDeviceName || 'Android')}`
    : '\u672A\u914D\u5BF9';
  const macHashText = state.macHash ? `gc-${escapeHtml(state.macHash)}` : '\u52A0\u8F7D\u4E2D...';
  const pairBtnText = state.pairingState === 'paired' ? '\u89E3\u9664\u914D\u5BF9' : '\u914D\u5BF9';
  const pairBtnAction = state.pairingState === 'paired' ? 'unpair' : 'pair';
  const pairBtnClass = state.pairingState === 'paired' ? 'unpair-btn' : 'pair-btn';

  return `
    <div id="settings-view" class="active">
      ${renderErrorToast()}
      <div class="settings-body">
        <!-- Hotkey Section -->
        <div class="settings-section">
          <span class="settings-section-label">\u5FEB\u6377\u952E</span>
          <div class="key-row">
            <span class="key-label">\u53D1\u9001\u526A\u8D34\u677F</span>
            <div class="key-badge ${badgeClass}" data-action="record-hotkey">
              <span class="key-val">${escapeHtml(hotkeyText)}</span>
            </div>
          </div>
        </div>
        <div class="settings-divider"></div>

        <!-- Device Pairing Section (8.1: replaces Cloud config) -->
        <div class="settings-section">
          <span class="settings-section-label">\u8BBE\u5907\u914D\u5BF9</span>
          <div class="field-group">
            <span class="field-label">\u914D\u5BF9\u72B6\u6001</span>
            <div class="field-display">${pairStatusText}</div>
          </div>
          <div class="field-group">
            <span class="field-label">MAC \u6807\u8BC6</span>
            <div class="field-display">${macHashText}</div>
          </div>
          <div class="key-row">
            <span class="key-label">\u663E\u793A\u914D\u5BF9\u4E8C\u7EF4\u7801</span>
            <div class="${pairBtnClass}" data-action="${pairBtnAction}">
              <span>${pairBtnText}</span>
            </div>
          </div>
        </div>
        <div class="settings-divider"></div>

        <!-- Notifications Section -->
        <div class="settings-section">
          <span class="settings-section-label">\u901A\u77E5</span>
          <div class="toggle-row">
            <span class="toggle-label">\u63A5\u6536\u540C\u6B65\u65F6\u663E\u793A\u901A\u77E5</span>
            <div class="toggle-track ${notifToggleClass}" data-action="toggle-notif">
              <div class="toggle-thumb"></div>
            </div>
          </div>
          <p class="toggle-desc">\u5F00\u542F\u540E\uFF0C\u5F53\u63A5\u6536\u5230\u6765\u81EA Android \u7684\u526A\u8D34\u677F\u6570\u636E\u65F6\u4F1A\u5F39\u51FA\u7CFB\u7EDF\u901A\u77E5\u3002</p>
        </div>
      </div>
    </div>
  `;
}

// ============================================
// View: QR Code Popup
// ============================================

function renderQrPopup() {
  const deviceLabel = state.macHash ? `${escapeHtml(state.deviceName || 'Mac')} \u00B7 gc-${escapeHtml(state.macHash)}` : '';

  return `
    <div id="qr-popup" class="active">
      <div class="qr-title-bar">
        <div class="traffic-lights">
          <div class="traffic-light red" data-action="close-qr"></div>
          <div class="traffic-light yellow"></div>
          <div class="traffic-light green"></div>
        </div>
        <span class="win-title">\u626B\u7801\u914D\u5BF9</span>
        <div class="win-spacer"></div>
      </div>
      <div class="qr-title-divider"></div>
      <div class="qr-body">
        <div class="qr-code-container">
          ${state.qrSvg || '<div class="qr-placeholder">\u52A0\u8F7D\u4E2D...</div>'}
        </div>
        <div class="qr-info">
          <span class="qr-info-title">\u4F7F\u7528 Android \u7AEF\u626B\u63CF\u6B64\u4E8C\u7EF4\u7801</span>
          <span class="qr-info-subtitle">\u914D\u5BF9\u540E\u5373\u53EF\u540C\u6B65\u526A\u8D34\u677F</span>
        </div>
        ${deviceLabel ? `
        <div class="qr-device-label">
          <span class="icon">${icons.monitor}</span>
          <span>${deviceLabel}</span>
        </div>
        ` : ''}
      </div>
    </div>
  `;
}

// ============================================
// Event Handlers
// ============================================

function attachEventListeners() {
  document.addEventListener('click', (e) => {
    const actionEl = e.target.closest('[data-action]');
    if (!actionEl) return;

    const action = actionEl.dataset.action;

    switch (action) {
      case 'send':
        handleSendClipboard();
        break;
      case 'settings':
        handleOpenSettings();
        break;
      case 'quit':
        handleQuit();
        break;
      case 'close-settings':
        handleCloseSettings();
        break;
      case 'record-hotkey':
        handleStartRecordHotkey();
        break;
      case 'toggle-cloud':
        handleToggleCloud();
        break;
      case 'toggle-notif':
        handleToggleNotification();
        break;
      case 'pair':
        handlePair();
        break;
      case 'unpair':
        handleUnpair();
        break;
      case 'close-qr':
        handleCloseQr();
        break;
      case 'copy-recent':
        handleCopyRecent(actionEl);
        break;
      case 'clear-logs':
        state.debugLogs = [];
        render();
        break;
      case 'dismiss-error':
        state.errorMessage = '';
        state.errorLevel = '';
        if (state.errorTimer) { clearTimeout(state.errorTimer); state.errorTimer = null; }
        render();
        break;
    }
  });

  // Settings input change listeners
  document.addEventListener('change', (e) => {
    if (e.target.id === 'cloud-url') {
      state.settings.cloudUrl = e.target.value.trim();
      saveSettings();
    }
    if (e.target.id === 'cloud-token') {
      state.settings.cloudToken = e.target.value;
      saveSettings();
    }
  });

  // Also save on blur for inputs
  document.addEventListener('blur', (e) => {
    if (e.target.id === 'cloud-url' || e.target.id === 'cloud-token') {
      saveSettings();
    }
  }, true);
}

async function handleSendClipboard() {
  await invoke('cmd_trigger_send');
}

async function handleOpenSettings() {
  await invoke('cmd_open_settings');
}

async function handleCloseSettings() {
  state.currentView = 'dropdown';
  await emit('close-settings');
  render();
}

async function handleQuit() {
  await invoke('cmd_quit');
}

function handleStartRecordHotkey() {
  if (state.isRecordingHotkey) return;
  state.isRecordingHotkey = true;
  render();

  const onKeyDown = async (e) => {
    e.preventDefault();
    e.stopPropagation();

    // Need at least one modifier
    if (!e.metaKey && !e.ctrlKey && !e.altKey && !e.shiftKey) return;
    // Don't capture modifier-only presses
    if (['Meta', 'Control', 'Alt', 'Shift'].includes(e.key)) return;

    const parts = [];
    const rawParts = [];

    if (e.metaKey || e.ctrlKey) { parts.push('\u2318'); rawParts.push('CmdOrCtrl'); }
    if (e.altKey) { parts.push('\u2325'); rawParts.push('Alt'); }
    if (e.shiftKey) { parts.push('\u21E7'); rawParts.push('Shift'); }

    // Map key to display
    const keyDisplay = e.key.length === 1 ? e.key.toUpperCase() : e.key;
    const keyRaw = e.key.length === 1 ? e.key.toUpperCase() : e.code.replace('Key', '');

    parts.push(keyDisplay);
    rawParts.push(keyRaw);

    state.settings.hotkey = parts.join(' ');
    state.settings.hotkeyRaw = rawParts.join('+');
    state.isRecordingHotkey = false;

    document.removeEventListener('keydown', onKeyDown, true);
    document.removeEventListener('keyup', onKeyUp, true);

    // Persist to backend
    await invoke('cmd_update_hotkey', { hotkey: state.settings.hotkeyRaw });
    await saveSettings();
    render();
  };

  const onKeyUp = (e) => {
    // If escape is pressed, cancel recording
    if (e.key === 'Escape') {
      state.isRecordingHotkey = false;
      document.removeEventListener('keydown', onKeyDown, true);
      document.removeEventListener('keyup', onKeyUp, true);
      render();
    }
  };

  document.addEventListener('keydown', onKeyDown, true);
  document.addEventListener('keyup', onKeyUp, true);
}

async function handlePair() {
  // Generate QR code SVG and show popup
  try {
    const svgResult = await invoke('cmd_generate_qr_code');
    if (svgResult) {
      state.qrSvg = typeof svgResult === 'string' ? svgResult : '';
    }
    // Refresh pairing state to get latest macHash
    await refreshPairingState();
  } catch (e) {
    console.warn('[GhostClip] Failed to generate QR code:', e);
  }
  state.qrPopupVisible = true;
  render();
}

async function handleUnpair() {
  try {
    await invoke('cmd_unpair');
  } catch (e) {
    console.warn('[GhostClip] Failed to unpair:', e);
  }
}

function handleCloseQr() {
  state.qrPopupVisible = false;
  render();
}

async function handleCopyRecent(el) {
  const index = parseInt(el.dataset.index, 10);
  const sync = state.recentSyncs[index];
  if (!sync) return;

  // Copy text to clipboard via Tauri or fallback
  try {
    if (window.__TAURI__ && window.__TAURI__.clipboard) {
      await window.__TAURI__.clipboard.writeText(sync.text);
    } else {
      await navigator.clipboard.writeText(sync.text);
    }
  } catch (e) {
    console.warn('[GhostClip] Failed to copy:', e);
    return;
  }

  // Show copied feedback
  if (state.copiedTimer) clearTimeout(state.copiedTimer);
  state.copiedIndex = index;
  render();
  state.copiedTimer = setTimeout(() => {
    state.copiedIndex = -1;
    state.copiedTimer = null;
    render();
  }, 1500);
}

/**
 * Refresh pairing state from backend (cmd_get_pairing_state)
 */
async function refreshPairingState() {
  try {
    const result = await invoke('cmd_get_pairing_state');
    if (result) {
      const data = typeof result === 'string' ? JSON.parse(result) : result;
      state.pairingState = data.status || state.pairingState;
      state.pairedDeviceName = data.deviceName || state.pairedDeviceName;
      state.macHash = data.macHash || state.macHash;
    }
  } catch (_) { /* ignore */ }
}

function handleToggleCloud() {
  state.settings.cloudEnabled = !state.settings.cloudEnabled;
  saveSettings();
  render();
}

function handleToggleNotification() {
  state.settings.notificationsEnabled = !state.settings.notificationsEnabled;
  saveSettings();
  render();
}

// ============================================
// Settings Persistence
// ============================================

async function loadSettings() {
  try {
    const saved = await invoke('cmd_get_settings');
    if (saved) {
      const parsed = typeof saved === 'string' ? JSON.parse(saved) : saved;
      Object.assign(state.settings, parsed);
    }
  } catch (e) {
    console.warn('[GhostClip] Failed to load settings:', e);
    // Try localStorage fallback
    try {
      const local = localStorage.getItem('ghostclip-settings');
      if (local) Object.assign(state.settings, JSON.parse(local));
    } catch (_) { /* ignore */ }
  }
}

let _saveSettingsTimer = null;

async function saveSettings() {
  // 防抖：300ms 内多次调用只执行最后一次，防止连续触发网络重启
  if (_saveSettingsTimer) clearTimeout(_saveSettingsTimer);
  _saveSettingsTimer = setTimeout(() => _doSaveSettings(), 300);
}

async function _doSaveSettings() {
  _saveSettingsTimer = null;
  const data = { ...state.settings };
  state.settingsError = '';
  try {
    await invoke('cmd_save_settings', { settings: JSON.stringify(data) });
  } catch (e) {
    const errMsg = typeof e === 'string' ? e : (e?.message || '保存设置失败');
    console.warn('[GhostClip] Failed to save settings:', errMsg);
    state.settingsError = errMsg;
    render();
    return;
  }
  // Always save to localStorage as fallback
  try {
    localStorage.setItem('ghostclip-settings', JSON.stringify(data));
  } catch (_) { /* ignore */ }
}

// ============================================
// Backend Event Listeners
// ============================================

async function setupBackendListeners() {
  // Listen for connection state changes from Rust backend
  await listen('connection-state-changed', (event) => {
    const { state: connState, deviceName, connectionLabel, error } = event.payload;
    state.connectionState = connState;
    state.deviceName = deviceName || state.deviceName;
    state.connectionLabel = connectionLabel || state.connectionLabel;
    state.connectionError = error || '';
    render();
  });

  // Listen for new sync records (3.8: show notification on incoming)
  await listen('clip-synced', (event) => {
    const { text, direction, timestamp, deviceName } = event.payload;
    state.recentSyncs.unshift({
      text,
      direction, // 'incoming' or 'outgoing'
      timestamp: timestamp || Date.now(),
    });
    // Keep max 20 records
    if (state.recentSyncs.length > 20) {
      state.recentSyncs = state.recentSyncs.slice(0, 20);
    }
    // 3.8: Show macOS system notification for incoming clipboard
    if (direction === 'incoming' && state.settings.notificationsEnabled) {
      sendNotification(
        `\u6765\u81EA ${deviceName || state.pairedDeviceName || 'Android'}`,
        truncate(text, 100)
      );
    }
    render();
  });

  // Listen for pairing state changes
  await listen('pairing-state-changed', (event) => {
    const { status, deviceName } = event.payload;
    state.pairingState = status || state.pairingState;
    if (deviceName !== undefined) state.pairedDeviceName = deviceName || '';
    // 3.6: Auto-close QR popup on pairing success
    if (status === 'paired' && state.qrPopupVisible) {
      state.qrPopupVisible = false;
    }
    render();
  });

  // Listen for errors from backend
  await listen('error-occurred', (event) => {
    const { message } = event.payload;
    const level = classifyError(message);
    showError(message, level);
  });

  // Listen for debug log events from Rust backend
  await listen('debug-log', (event) => {
    const { message } = event.payload;
    const time = new Date().toLocaleTimeString('en-US', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' });
    state.debugLogs.push(`${time} ${message}`);
    if (state.debugLogs.length > 100) {
      state.debugLogs = state.debugLogs.slice(-100);
    }
    // Auto-scroll debug logs without full re-render
    const logsEl = document.getElementById('debug-logs');
    if (logsEl) {
      const line = document.createElement('div');
      line.className = 'debug-line';
      line.textContent = `${time} ${message}`;
      // Remove empty placeholder if present
      const empty = logsEl.querySelector('.debug-empty');
      if (empty) empty.remove();
      logsEl.appendChild(line);
      logsEl.scrollTop = logsEl.scrollHeight;
    }
  });

  // Listen for view switch commands from tray menu
  await listen('show-dropdown', async () => {
    state.currentView = 'dropdown';
    state.qrPopupVisible = false;
    // 主动查询当前连接状态和配对状态（补偿 popup 隐藏期间错过的事件）
    try {
      const result = await invoke('cmd_get_connection_state');
      if (result) {
        const data = typeof result === 'string' ? JSON.parse(result) : result;
        state.connectionState = data.state || state.connectionState;
        state.deviceName = data.deviceName || state.deviceName;
        state.connectionLabel = data.connectionLabel || state.connectionLabel;
      }
    } catch (_) { /* ignore */ }
    await refreshPairingState();
    render();
  });

  await listen('show-settings', () => {
    state.currentView = 'settings';
    render();
  });

  // 弹出面板失焦自动隐藏（通过 Rust 命令，以便记录时间戳支持 toggle）
  try {
    const currentWindow = window.__TAURI__?.window?.getCurrentWindow();
    if (currentWindow && currentWindow.label === 'main') {
      await currentWindow.onFocusChanged(({ payload: focused }) => {
        if (!focused) {
          state.currentView = 'dropdown'; // 重置视图状态
          state.qrPopupVisible = false;
          invoke('cmd_popup_hide');
        }
      });
    }
  } catch (_) { /* ignore */ }
}

// ============================================
// Render
// ============================================

function render() {
  const app = document.getElementById('app');
  if (state.qrPopupVisible) {
    app.innerHTML = renderQrPopup();
  } else if (state.currentView === 'settings') {
    app.innerHTML = renderSettings();
  } else {
    app.innerHTML = renderDropdown();
  }
}

// ============================================
// Utilities
// ============================================

function escapeHtml(text) {
  const div = document.createElement('div');
  div.textContent = text;
  return div.innerHTML;
}

function truncate(text, maxLen) {
  if (!text) return '';
  if (text.length <= maxLen) return text;
  return text.substring(0, maxLen - 1) + '\u2026';
}

/**
 * Send macOS system notification via Tauri notification API (3.8)
 */
async function sendNotification(title, body) {
  try {
    if (window.__TAURI__ && window.__TAURI__.notification) {
      const permission = await window.__TAURI__.notification.isPermissionGranted();
      if (!permission) {
        await window.__TAURI__.notification.requestPermission();
      }
      await window.__TAURI__.notification.sendNotification({ title, body });
    }
  } catch (e) {
    console.warn('[GhostClip] Failed to send notification:', e);
  }
}

function formatTimeAgo(timestamp) {
  const now = Date.now();
  const diff = Math.max(0, now - timestamp);
  const seconds = Math.floor(diff / 1000);
  const minutes = Math.floor(seconds / 60);
  const hours = Math.floor(minutes / 60);

  if (seconds < 10) return '\u521A\u521A';
  if (seconds < 60) return `${seconds}s`;
  if (minutes < 60) return `${minutes}m`;
  if (hours < 24) return `${hours}h`;
  return `${Math.floor(hours / 24)}d`;
}

// ============================================
// Initialization
// ============================================

async function init() {
  await loadSettings();

  // 根据 URL hash 自动选择视图（Rust 端打开设置窗口时传入 #settings）
  if (window.location.hash === '#settings') {
    state.currentView = 'settings';
  }

  // Query initial pairing state
  await refreshPairingState();

  render();
  attachEventListeners();
  await setupBackendListeners();

  // Periodically re-render to update "time ago" labels
  setInterval(() => {
    if (state.currentView === 'dropdown' && state.recentSyncs.length > 0) {
      render();
    }
  }, 10000);
}

// Start the app
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', init);
} else {
  init();
}
