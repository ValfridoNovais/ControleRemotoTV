const state = { devices: [], selected: null };

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
function discover() {
  if (!nativeAvailable()) return toast("Abra dentro do APK MMPG Remote.");
  setStatus("Procurando…");
  window.MMPGNative.startDiscovery();
}
function renderDevices(devices) {
  state.devices = devices;
  const select = document.getElementById("deviceSelect");
  select.innerHTML = '<option value="">Selecione uma TV</option>';
  devices.forEach(d => {
    const opt = document.createElement("option");
    opt.value = d.host;
    opt.textContent = `${d.name} — ${d.host}`;
    select.appendChild(opt);
  });
  setStatus(devices.length ? `${devices.length} encontrada(s)` : "Nenhuma encontrada");
}
document.getElementById("deviceSelect").addEventListener("change", e => {
  const d = selectedDevice();
  state.selected = d || null;
  document.getElementById("deviceInfo").textContent =
    d ? `${d.name} • ${d.host}:${d.port}` : "Celular e TV devem estar na mesma Wi‑Fi.";
});
function openPair() {
  if (!selectedDevice()) return toast("Selecione uma TV.");
  document.getElementById("pairDialog").showModal();
  document.getElementById("pin").focus();
}
function doPair(ev) {
  ev.preventDefault();
  const d = selectedDevice();
  const pin = document.getElementById("pin").value.trim();
  if (!d || !pin) return false;
  setStatus("Pareando…");
  window.MMPGNative.pair(d.host, d.port || 6467, pin);
  document.getElementById("pairDialog").close();
  document.getElementById("pin").value = "";
  return false;
}
function connectTv() {
  const d = selectedDevice();
  if (!d) return toast("Selecione uma TV.");
  setStatus("Conectando…");
  window.MMPGNative.connect(d.host);
}
function forgetTv() {
  const d = selectedDevice();
  if (!d) return toast("Selecione uma TV.");
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
    if (event === "pairResult" || event === "connectResult" || event === "keyResult" || event === "forgetResult" || event === "resetIdentityResult") {
      let r = data;
      if (typeof data === "string") {
        try { r = JSON.parse(data); } catch (_) {}
      }
      if (r && typeof r === "object") {
        toast(r.message || r.code || event);
        if (event === "pairResult") setStatus(r.ok ? "Pareada" : "Falha no pareamento");
        if (event === "connectResult") setStatus(r.ok ? "Conectada" : "Não conectada");
        if (event === "forgetResult" && r.ok) setStatus("Esquecida");
        if (event === "resetIdentityResult" && r.ok) setStatus("Identidade redefinida");
      }
      return;
    }
    if (event === "permissionResult" && data && data.granted === false) {
      toast("Permissão de dispositivos próximos não concedida.");
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
    const badge = document.getElementById("mockBadge");
    if (badge && info && info.mockAllowed) badge.hidden = false;
  } catch (_) {
    // getBuildInfo() is best-effort UI decoration; never block the app on it.
  }
}
initMockIndicator();
