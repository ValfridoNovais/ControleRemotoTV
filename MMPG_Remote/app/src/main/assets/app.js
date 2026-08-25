const state = { devices: [], selected: null, log: [], premium: false };

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
  const portSuffix = d && d.port != null ? `:${d.port}` : "";
  document.getElementById("deviceInfo").textContent =
    d ? `${d.name} • ${d.host}${portSuffix} • ${d.platformLabel || "TV"}` : "Celular e TV devem estar na mesma Wi‑Fi.";
  renderDeviceCards();
}
function renderDevices(devices) {
  state.devices = devices;
  const select = document.getElementById("deviceSelect");
  const previouslySelected = select.value;
  select.innerHTML = '<option value="">Selecione uma TV</option>';
  devices.forEach(d => {
    const opt = document.createElement("option");
    opt.value = d.host;
    opt.textContent = d.platformLabel ? `${d.name} — ${d.platformLabel}` : `${d.name} — ${d.host}`;
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

function platformIcon(platformLabel) {
  const label = String(platformLabel || "").toLowerCase();
  if (label.indexOf("android") >= 0 || label.indexOf("google") >= 0) return "G";
  if (label.indexOf("webos") >= 0 || label.indexOf("lg") >= 0) return "L";
  if (label.indexOf("tizen") >= 0 || label.indexOf("samsung") >= 0) return "S";
  return "TV";
}
function renderDeviceCards() {
  const list = document.getElementById("deviceCards");
  if (!list) return;
  if (!state.devices.length) {
    list.innerHTML = '<div class="device-empty">Nenhuma TV encontrada ainda.</div>';
    return;
  }
  list.innerHTML = "";
  state.devices.forEach(d => {
    const btn = document.createElement("button");
    const portSuffix = d.port != null ? `:${d.port}` : "";
    const icon = document.createElement("span");
    const main = document.createElement("span");
    const name = document.createElement("strong");
    const meta = document.createElement("small");
    const check = document.createElement("span");
    btn.type = "button";
    btn.className = "device-card" + (state.selected && state.selected.host === d.host ? " selected" : "");
    icon.className = "device-icon";
    icon.textContent = platformIcon(d.platformLabel);
    main.className = "device-main";
    name.textContent = d.name || "TV encontrada";
    meta.textContent = `${d.platformLabel || "Televisão"} • ${d.host}${portSuffix}`;
    check.className = "device-check";
    check.textContent = "✓";
    main.appendChild(name);
    main.appendChild(meta);
    btn.appendChild(icon);
    btn.appendChild(main);
    btn.appendChild(check);
    btn.addEventListener("click", () => {
      document.getElementById("deviceSelect").value = d.host;
      updateDeviceInfo();
    });
    list.appendChild(btn);
  });
}

// Pareamento no Android TV Remote Protocol v2 sempre acontece na porta fixa
// 6467, mesmo quando o mDNS anuncia outra porta para o serviço encontrado
// (a porta resolvida por NSD é a do canal remoto, 6466 — usada só em
// connectTv(); nunca é a porta de pareamento). Outros providers (ex.: LG
// webOS) ignoram este parâmetro — cada um resolve sua própria porta.
const PAIRING_PORT = 6467;

// beginPairing()/submitPairingPin() cobrem os dois formatos de pareamento
// que os providers hoje usam: Android TV mostra um PIN na TV que o usuário
// digita aqui (submitPairingPin é chamado); LG webOS confirma direto no
// controle da TV, sem PIN — beginPairing() sozinho já conclui tudo (ver o
// branch "AWAITING_PIN" vs. sucesso direto no handler de beginPairingResult
// abaixo). De qualquer forma, beginPairing() precisa vir antes: nenhum dos
// dois fluxos pode pedir uma credencial ao usuário antes da TV sequer saber
// que alguém está tentando parear.
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
  // O host identifica qual TvProvider deve receber o comando — necessário
  // desde que o app passou a suportar mais de um provider de TV (ver
  // TvManager.kt); antes só existia um provider e o host era implícito.
  const d = state.selected;
  if (!d) { toast("Selecione uma TV."); return; }
  window.MMPGNative.sendKey(d.host, name);
}

// ── Navegação por abas ───────────────────────────────────────────────────
const VIEWS = ["remote", "tvs", "about"];
function showView(name) {
  if (VIEWS.indexOf(name) === -1) return;
  VIEWS.forEach(v => {
    document.getElementById(`view-${v}`).hidden = v !== name;
    document.getElementById(`tabBtn-${v}`).classList.toggle("active", v === name);
  });
  if (name === "tvs") loadPairedDevices();
  if (name === "about" && !state.premium) {
    if (nativeAvailable()) window.MMPGNative.getProductPrices();
  }
}

// ── Minhas TVs (dispositivos pareados, mesmo offline) ────────────────────
function loadPairedDevices() {
  if (!nativeAvailable()) return;
  window.MMPGNative.getPairedDevices();
}
function renderPairedDevices(devices) {
  const list = document.getElementById("pairedList");
  const empty = document.getElementById("pairedEmpty");
  list.querySelectorAll(".paired-item").forEach(el => el.remove());
  empty.hidden = devices.length > 0;
  devices.forEach(d => {
    const item = document.createElement("div");
    item.className = "paired-item";
    const info = document.createElement("div");
    info.className = "paired-info";
    info.innerHTML = `<strong>${d.name}</strong><small class="muted">${d.platformLabel || "TV"} • ${d.host}</small>`;
    const actions = document.createElement("div");
    actions.className = "row compact";
    actions.style.marginTop = "0";
    const connectBtn = document.createElement("button");
    connectBtn.className = "ghost";
    connectBtn.textContent = "Conectar";
    connectBtn.onclick = () => connectPairedDevice(d);
    const forgetBtn = document.createElement("button");
    forgetBtn.className = "danger ghost";
    forgetBtn.textContent = "Esquecer";
    forgetBtn.onclick = () => forgetPairedDevice(d);
    actions.appendChild(connectBtn);
    actions.appendChild(forgetBtn);
    item.appendChild(info);
    item.appendChild(actions);
    list.appendChild(item);
  });
}
function connectPairedDevice(d) {
  // A TV pode não estar na lista de descoberta desta sessão (ex.: pareada
  // antes, agora fora de alcance ou nunca redescoberta) — adiciona um
  // registro mínimo pra o seletor/controle remoto funcionarem mesmo assim.
  if (!state.devices.some(x => x.host === d.host)) {
    state.devices = state.devices.concat([d]);
    const select = document.getElementById("deviceSelect");
    const opt = document.createElement("option");
    opt.value = d.host;
    opt.textContent = d.platformLabel ? `${d.name} — ${d.platformLabel}` : `${d.name} — ${d.host}`;
    select.appendChild(opt);
  }
  document.getElementById("deviceSelect").value = d.host;
  updateDeviceInfo();
  showView("remote");
  connectTv();
}
function forgetPairedDevice(d) {
  if (!nativeAvailable()) return;
  if (!confirm(`Esquecer "${d.name}"? Você vai precisar parear de novo pra controlar essa TV.`)) return;
  window.MMPGNative.forget(d.host);
}

// ── Sobre (info do app, plano, links) ─────────────────────────────────────
function updateAboutPlanUI() {
  const statusEl = document.getElementById("aboutPlanStatus");
  const actionsEl = document.getElementById("aboutPlanActions");
  if (!statusEl) return;
  if (state.premium) {
    statusEl.textContent = "Premium ativo — obrigado por apoiar o projeto! 🙏";
    if (actionsEl) actionsEl.hidden = true;
  } else {
    statusEl.textContent = "Versão gratuita.";
    if (actionsEl) actionsEl.hidden = false;
  }
}
function buyLifetimeFromAbout() {
  if (!nativeAvailable()) return toast("Abra dentro do APK MMPG Remote.");
  window.MMPGNative.buyLifetime();
}
function buySubscriptionFromAbout() {
  if (!nativeAvailable()) return toast("Abra dentro do APK MMPG Remote.");
  window.MMPGNative.buySubscription();
}
function openPrivacySummary() {
  const dialog = document.getElementById("privacyDialog");
  if (!dialog) return;
  if (typeof dialog.showModal === "function") dialog.showModal();
  else dialog.setAttribute("open", "");
}
function closePrivacySummary() {
  const dialog = document.getElementById("privacyDialog");
  if (!dialog) return;
  if (typeof dialog.close === "function") dialog.close();
  else dialog.removeAttribute("open");
}

window.MMPG = {
  onNative(event, raw) {
    let data = raw;
    try { data = JSON.parse(raw); } catch (_) {}

    if (event === "devices") {
      renderDevices(Array.isArray(data) ? data : []);
      return;
    }
    if (event === "pairedDevices") {
      renderPairedDevices(Array.isArray(data) ? data : []);
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
        if (r.ok && r.code === "AWAITING_PIN") {
          // Fluxo com PIN (Android TV): a TV mostra um código, o usuário digita aqui.
          setStatus("Aguardando PIN");
          document.getElementById("pairDialog").showModal();
          document.getElementById("pin").focus();
        } else if (r.ok) {
          // Fluxo sem PIN (ex.: LG webOS, confirmação direto no controle da
          // TV): beginPairing() sozinho já conclui o pareamento — não há
          // nada para o usuário digitar aqui.
          toast(r.message || "TV pareada com sucesso.");
          setStatus("Pareada");
          state.pairingHost = null;
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
        if (event === "forgetResult" && r.ok) { setStatus("Esquecida"); loadPairedDevices(); }
        if (event === "resetIdentityResult" && r.ok) setStatus("Identidade redefinida");
      }
      return;
    }
    if (event === "permissionResult") {
      logLine(`Permissão de dispositivos próximos: granted=${data && data.granted}`);
      if (data && data.granted === false) {
        toast("Permissão de dispositivos próximos não concedida.");
      }
      return;
    }
    if (event === "entitlement") {
      state.premium = !!(data && data.premium);
      logLine(`entitlement: premium=${state.premium} source=${data && data.source}`);
      updateAboutPlanUI();
      window.MMPGPaywall?.handleNative(event, data);
      // Sempre chama onAppOpened — ele mesmo decide se conta como sessão
      // nova (debounce interno contra eventos duplicados muito próximos,
      // ver paywall.js) e se é hora de mostrar o paywall. Isso é o que
      // permite um retorno do segundo plano (evento "resumedFromBackground"
      // abaixo) também contar como sessão, não só a abertura inicial.
      window.MMPGPaywall?.onAppOpened(state.premium);
      return;
    }
    if (event === "resumedFromBackground") {
      logLine("App voltou do segundo plano após período parado — reavaliando paywall");
      if (nativeAvailable()) window.MMPGNative.getEntitlement();
      return;
    }
    if (event === "productPrices" || event === "purchaseResult") {
      window.MMPGPaywall?.handleNative(event, data);
      if (event === "productPrices" && data && typeof data === "object") {
        const lifetimeEl = document.getElementById("aboutPriceLifetime");
        const monthlyEl = document.getElementById("aboutPriceMonthly");
        if (lifetimeEl) lifetimeEl.textContent = data.lifetime && data.lifetime.price ? `— ${data.lifetime.price}` : "";
        if (monthlyEl) monthlyEl.textContent = data.monthly && data.monthly.price ? `— ${data.monthly.price}` : "";
      }
      if (event === "purchaseResult" && data && typeof data === "object") {
        logLine(`purchaseResult: ok=${data.ok} code=${data.code} premium=${data.premium}`);
        if (data.ok) toast("Obrigado! Plano premium ativado.");
        else if (data.code !== "USER_CANCELED") toast("Não foi possível concluir a compra.");
      }
      return;
    }
    if (event === "buyResult") {
      if (data && typeof data === "object") {
        logLine(`buyResult: ok=${data.ok} code=${data.code}`);
        if (!data.ok) toast("Não foi possível abrir a compra agora.");
      }
      return;
    }
  }
};

// Consulta o entitlement assim que o app abre — a decisão de mostrar (ou
// não) o paywall só acontece quando a resposta chega (ver "entitlement" acima).
if (nativeAvailable()) {
  logLine("Abertura do app: consultando entitlement…");
  window.MMPGNative.getEntitlement();
}

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
// NSD/TLS/protobuf path in TvBridge/TvManager/AndroidTvProvider.
function initMockIndicator() {
  if (!nativeAvailable()) return;
  try {
    const info = JSON.parse(window.MMPGNative.getBuildInfo());
    const versionLabel = document.getElementById("versionLabel");
    if (versionLabel && info && info.version) versionLabel.textContent = info.version;
    const aboutVersion = document.getElementById("aboutVersion");
    if (aboutVersion && info && info.version) aboutVersion.textContent = `v${info.version}`;
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
