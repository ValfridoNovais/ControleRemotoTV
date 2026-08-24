const state = { devices: [], selected: null, log: [] };

function nativeAvailable() {
  return typeof window.MMPGNative !== "undefined";
}
function toast(msg) {
  const el = document.getElementById("toast");
  el.textContent = msg;
  el.classList.add("show");
  clearTimeout(window.__toast);
  window.__toast = setTimeout(()=>el.classList.remove("show"), 2600);
}
function setStatus(text) {
  document.getElementById("status").textContent = text;
}
function selectedDevice() {
  const host = document.getElementById("deviceSelect").value;
  return state.devices.find(d => d.host === host);
}
function logLine(text) {
  const time = new Date().toLocaleTimeString("pt-BR", { hour12: false });
  state.log.push(`[${time}] ${text}`);
  if (state.log.length > 300) state.log.shift();
  const el = document.getElementById("debugLog");
  if (el) {
    el.textContent = state.log.join("\n");
    el.scrollTop = el.scrollHeight;
  }
}
function copyLog() {
  const text = state.log.join("\n") || "(vazio)";
  navigator.clipboard?.writeText(text).then(
    () => toast("Log copiado."),
    () => toast("Não foi possível copiar.")
  );
}
function clearLog() {
  state.log = [];
  const el = document.getElementById("debugLog");
  if (el) el.textContent = "";
}
function discover() {
  if (!nativeAvailable()) return toast("Abra dentro do APK MMPG Remote.");
  setStatus("Procurando…");
  logLine("Procurar TV: iniciando descoberta…");
  window.MMPGNative.startDiscovery();
}
function updateDeviceInfo() {
  const d = selectedDevice();
  state.selected = d || null;
  document.getElementById("deviceInfo").textContent =
    d ? `${d.name} • ${d.host}:${d.port}` : "Celular e TV devem estar na mesma Wi‑Fi.";
}
function renderDevices(devices) {
  state.devices = devices;
  const select = document.getElementById("deviceSelect");
  const previouslySelected = select.value;
  select.innerHTML = '<option value="">Selecione uma TV</option>';
  devices.forEach(d => {
    const opt = document.createElement("option");
    opt.value = d.host;
    opt.textContent = `${d.name} — ${d.host}`;
    select.appendChild(opt);
  });
  // Auto-selects when there's exactly one TV (the common case) or when the
  // previously selected host is still in the list, so "Parear"/"Conectar"
  // work right away without the user having to open the dropdown manually.
  if (devices.some(d => d.host === previouslySelected)) {
    select.value = previouslySelected;
  } else if (devices.length === 1) {
    select.value = devices[0].host;
  }
  updateDeviceInfo();
  setStatus(devices.length ? `${devices.length} encontrada(s)` : "Nenhuma encontrada");
  logLine(`Lista de TVs atualizada: ${devices.length} dispositivo(s) — ` +
    (devices.length ? devices.map(d => `${d.name} (${d.host}:${d.port})`).join(", ") : "nenhum"));
}
document.getElementById("deviceSelect").addEventListener("change", updateDeviceInfo);

// Pareamento (Android TV Remote Protocol v2) sempre acontece na porta fixa
// 6467, mesmo quando o mDNS anuncia outra porta para o serviço encontrado
// (a porta resolvida por NSD é a do canal remoto, 6466 — usada só em
// connectTv(); nunca é a porta de pareamento).
const PAIRING_PORT = 6467;

// A TV só exibe o PIN depois que a conexão de pareamento é aberta — por isso
// isso acontece em duas etapas: 1) conectar (beginPairing), esperar a TV
// mostrar o código; 2) só então abrir a caixa de diálogo pedindo o PIN
// digitado, e enviá-lo (submitPairingPin). Não dá pra pedir o PIN antes de a
// TV sequer saber que alguém está tentando parear.
function openPair() {
  const d = selectedDevice();
  if (!d) { toast("Selecione uma TV."); logLine("Parear: nenhuma TV selecionada"); return; }
  state.pairingHost = d.host;
  setStatus("Conectando para parear…");
  logLine(`Conectando para parear com ${d.host}:${PAIRING_PORT} (porta fixa, ignorando ${d.port} anunciado)`);
  window.MMPGNative.beginPairing(d.host, PAIRING_PORT);
}
function doPair(ev) {
  ev.preventDefault();
  const pin = document.getElementById("pin").value.trim();
  if (!state.pairingHost || !pin) return false;
  setStatus("Enviando PIN…");
  logLine(`Enviando PIN para ${state.pairingHost}`);
  window.MMPGNative.submitPairingPin(state.pairingHost, pin);
  document.getElementById("pairDialog").close();
  document.getElementById("pin").value = "";
  return false;
}
function cancelPair() {
  document.getElementById("pairDialog").close();
  document.getElementById("pin").value = "";
  if (state.pairingHost) {
    window.MMPGNative.cancelPairing();
    state.pairingHost = null;
  }
}
function connectTv() {
  const d = selectedDevice();
  if (!d) { toast("Selecione uma TV."); logLine("Conectar: nenhuma TV selecionada"); return; }
  setStatus("Conectando…");
  window.MMPGNative.connect(d.host);
}
function forgetTv() {
  const d = selectedDevice();
  if (!d) { toast("Selecione uma TV."); logLine("Esquecer: nenhuma TV selecionada"); return; }
  window.MMPGNative.forget(d.host);
}
function resetIdentity() {
  if (!nativeAvailable()) return toast("Abra dentro do APK MMPG Remote.");
  const sure = confirm(
    "Isso redefine a identidade deste app perante TODAS as TVs já pareadas. " +
    "Todas precisarão ser pareadas novamente. Continuar?"
  );
  if (!sure) return;
  window.MMPGNative.resetIdentity();
}
function key(name) {
  if (!nativeAvailable()) return;
  window.MMPGNative.sendKey(name);
}

window.MMPG = {
  onNative(event, raw) {
    let data = raw;
    try { data = JSON.parse(raw); } catch (_) {}

    if (event === "devices") {
      renderDevices(Array.isArray(data) ? data : []);
      return;
    }
    if (event === "diag") {
      logLine(typeof data === "string" ? data : JSON.stringify(data));
      return;
    }
    if (event === "beginPairingResult") {
      let r = data;
      if (typeof data === "string") { try { r = JSON.parse(data); } catch (_) {} }
      if (r && typeof r === "object") {
        logLine(`beginPairingResult: ok=${r.ok} code=${r.code} — ${r.message || ""}`);
        if (r.ok) {
          setStatus("Aguardando PIN");
          document.getElementById("pairDialog").showModal();
          document.getElementById("pin").focus();
        } else {
          toast(r.message || r.code || event);
          setStatus("Falha ao conectar para parear");
          state.pairingHost = null;
        }
      }
      return;
    }
    if (event === "pairResult" || event === "connectResult" || event === "keyResult" || event === "forgetResult" || event === "resetIdentityResult") {
      let r = data;
      if (typeof data === "string") {
        try { r = JSON.parse(data); } catch (_) {}
      }
      if (r && typeof r === "object") {
        toast(r.message || r.code || event);
        logLine(`${event}: ok=${r.ok} code=${r.code} — ${r.message || ""}`);
        if (event === "pairResult") { setStatus(r.ok ? "Pareada" : "Falha no pareamento"); state.pairingHost = null; }
        if (event === "connectResult") setStatus(r.ok ? "Conectada" : "Não conectada");
        if (event === "forgetResult" && r.ok) setStatus("Esquecida");
        if (event === "resetIdentityResult" && r.ok) setStatus("Identidade redefinida");
      }
      return;
    }
    if (event === "permissionResult") {
      logLine(`Permissão de dispositivos próximos: granted=${data && data.granted}`);
      if (data && data.granted === false) {
        toast("Permissão de dispositivos próximos não concedida.");
      }
    }
  }
};

if (!nativeAvailable()) {
  setStatus("Preview web");
  document.getElementById("deviceInfo").textContent =
    "Preview da interface. O controle real exige o APK e a camada nativa.";
}

// Debug-build-only indicator: BuildConfig.ALLOW_MOCK is true only in debug
// builds (release always reports false — see app/build.gradle.kts). This is
// a purely visual signal that the running APK is a debug build with mock
// infrastructure reserved for future use; it never fakes discovery, pairing
// or key delivery — every command above still goes through the real
// NSD/TLS/protobuf path in TvBridge/AndroidTvRemoteService.
function initMockIndicator() {
  if (!nativeAvailable()) return;
  try {
    const info = JSON.parse(window.MMPGNative.getBuildInfo());
    const versionLabel = document.getElementById("versionLabel");
    if (versionLabel && info && info.version) versionLabel.textContent = info.version;
    if (info && info.mockAllowed) {
      const badge = document.getElementById("mockBadge");
      if (badge) badge.hidden = false;
      const logSection = document.getElementById("debugLogSection");
      if (logSection) logSection.hidden = false;
    }
  } catch (_) {
    // getBuildInfo() is best-effort UI decoration; never block the app on it.
  }
}
initMockIndicator();
