/*!
 * MMPGPaywall
 * Paywall fullscreen da versão gratuita do MMPG Remote — alterna entre 10
 * variantes visuais/textuais, cada uma com seu próprio tempo de espera
 * (15 a 25s) antes de liberar "Continuar usando grátis". Aparece por
 * sessão de uso (não por tecla pressionada), com cooldown mínimo entre
 * exibições. Compra real (vitalício ou assinatura) é sempre feita pelo
 * Google Play Billing, nunca simulada aqui.
 */
(function (global) {
  "use strict";

  var STORAGE_SESSIONS   = "mmpg_pw_sessions";
  var STORAGE_LAST_SHOWN = "mmpg_pw_last_shown";
  var STORAGE_VARIANT_IX = "mmpg_pw_variant_ix";
  // Cadência "agressiva" (decisão do produto, não um valor arbitrário
  // nosso): mostra a partir da 2ª sessão e depois em toda sessão seguinte,
  // com só 10min de intervalo mínimo — curto o bastante pra não sumir por
  // horas enquanto a pessoa ainda está usando a TV (a própria razão da
  // troca de 4h pra 10min), mas ainda assim um intervalo real, não a cada
  // sessão sem parar feito muito app por aí faz. "Sessão" inclui tanto
  // abrir o app do zero quanto voltar do segundo plano depois de
  // BACKGROUND_THRESHOLD_MS parado (ver MainActivity.onResume/
  // TvBridge.notifyResumedFromBackground) - sem isso, minimizar sem fechar
  // de verdade nunca contaria como sessão nova.
  var COOLDOWN_MINUTES   = 10;
  var FIRST_SHOW_SESSION = 2;
  var REPEAT_EVERY       = 1;
  // Debounce contra o "entitlement" disparar mais de uma vez muito perto
  // (ex.: a checagem inicial do JS e o auto-push do BillingClient chegando
  // quase juntos) - sem isso, uma única abertura de app poderia contar como
  // duas sessões. Um retorno real do segundo plano acontece minutos/horas
  // depois, bem fora dessa janela, então nunca é afetado por este debounce.
  var MIN_RECHECK_INTERVAL_MS = 2000;
  var _lastAppOpenedCheckAt = 0;

  var VARIANTS = [
    {
      icon: "💙", a: "#2563EB", b: "#06B6D4", holdSeconds: 15,
      title: "O MMPG Remote é mantido por uma pessoa só",
      body: "Manter o app funcionando, testado em TVs reais e atualizado tem um custo real de tempo e dinheiro.\n\nEscolha uma das opções abaixo para apoiar o projeto — ou aguarde alguns segundos para continuar usando gratuitamente."
    },
    {
      icon: "🙌", a: "#10B981", b: "#059669", holdSeconds: 16,
      title: "Ajude o MMPG Remote a continuar existindo",
      body: "Você está usando um app gratuito de controle remoto. Se ele já te ajudou, considere apoiar com uma das opções abaixo.\n\nSe preferir não agora, é só aguardar — o app continua liberado."
    },
    {
      icon: "📺", a: "#F59E0B", b: "#EF4444", holdSeconds: 17,
      title: "Controle sua TV sem interrupções",
      body: "Com o plano premium, esta tela para de aparecer — de vez.\n\nEscolha pagamento único ou assinatura mensal abaixo, ou aguarde para continuar na versão gratuita."
    },
    {
      icon: "⚙️", a: "#8B5CF6", b: "#6366F1", holdSeconds: 18,
      title: "Prefere pagar uma vez só?",
      body: "O plano vitalício remove esta tela para sempre, com um único pagamento — sem mensalidade.\n\nSe preferir testar mais antes, aguarde alguns segundos para continuar de graça."
    },
    {
      icon: "☕", a: "#F59E0B", b: "#D97706", holdSeconds: 19,
      title: "Menos que um cafezinho por mês",
      body: "A assinatura mensal do MMPG Remote custa pouco e ajuda a manter o app no ar todo mês.\n\nSem compromisso: cancele quando quiser. Ou aguarde para continuar usando a versão gratuita."
    },
    {
      icon: "🇧🇷", a: "#059669", b: "#2563EB", holdSeconds: 20,
      title: "Feito no Brasil, para brasileiros",
      body: "O MMPG Remote é desenvolvido e mantido de forma independente.\n\nApoiar o projeto ajuda a manter novas versões chegando. Se não for o momento, é só esperar para continuar de graça."
    },
    {
      icon: "🔧", a: "#06B6D4", b: "#2563EB", holdSeconds: 21,
      title: "Cada atualização leva tempo real",
      body: "Correções, suporte a novos aparelhos e melhorias no pareamento não acontecem sozinhos.\n\nSe o app te é útil, considere apoiar. Senão, aguarde para continuar usando gratuitamente."
    },
    {
      icon: "🎯", a: "#EF4444", b: "#F59E0B", holdSeconds: 22,
      title: "Você já usa bastante o MMPG Remote",
      body: "Notamos que você volta a usar o app com frequência — isso é ótimo pra gente!\n\nSe quiser remover esta tela definitivamente, escolha uma das opções abaixo. Ou aguarde para continuar de graça."
    },
    {
      icon: "🤝", a: "#6366F1", b: "#8B5CF6", holdSeconds: 23,
      title: "Um combinado justo",
      body: "O app continua 100% funcional gratuitamente — esta tela é só um convite, não uma cobrança.\n\nSe quiser apoiar e remover esta tela, escolha uma opção abaixo. Senão, é só aguardar."
    },
    {
      icon: "✅", a: "#10B981", b: "#06B6D4", holdSeconds: 25,
      title: "Escolha como apoiar e continue usando",
      body: "Pagamento único ou assinatura mensal — qualquer uma remove esta tela definitivamente.\n\nSe preferir continuar na versão gratuita por enquanto, aguarde a liberação abaixo."
    }
  ];

  var _overlay = null;
  var _countdownTimer = null;
  var _priceCache = null; // último productPrices recebido

  function _nativeAvailable() {
    return typeof global.MMPGNative !== "undefined";
  }

  // Manda a decisão do gatilho pro log de debug já existente em app.js -
  // sem isso, "por que o paywall não apareceu" só dava pra investigar
  // adivinhando; com isso, o próximo log de teste já mostra sessão contada,
  // se o gatilho bateu e se o cooldown liberou, tudo numa linha só.
  function _diag(msg) {
    if (typeof global.logLine === "function") global.logLine("[Paywall] " + msg);
  }

  function _readInt(key, fallback) {
    try {
      var v = parseInt(localStorage.getItem(key) || "", 10);
      return isNaN(v) ? fallback : v;
    } catch (e) { return fallback; }
  }

  function _write(key, value) {
    try { localStorage.setItem(key, String(value)); } catch (e) {}
  }

  function _nextVariant() {
    var ix = _readInt(STORAGE_VARIANT_IX, -1) + 1;
    if (ix >= VARIANTS.length) ix = 0;
    _write(STORAGE_VARIANT_IX, ix);
    return VARIANTS[ix];
  }

  function _shouldShowForSession(sessionCount) {
    if (sessionCount === FIRST_SHOW_SESSION) return true;
    if (sessionCount > FIRST_SHOW_SESSION) {
      return (sessionCount - FIRST_SHOW_SESSION) % REPEAT_EVERY === 0;
    }
    return false;
  }

  function _cooldownElapsed() {
    var last = _readInt(STORAGE_LAST_SHOWN, 0);
    if (!last) return true;
    var minutes = (Date.now() - last) / 60000;
    return minutes >= COOLDOWN_MINUTES;
  }

  /**
   * Chamado uma vez por abertura do app, depois que já se sabe se o
   * usuário é premium (evento "entitlement"). Conta a sessão e decide,
   * sozinho, se é a vez de mostrar o paywall — mesmo espírito do
   * MMPGContributionGate.open(), que também decide sua própria frequência.
   */
  function onAppOpened(isPremium) {
    if (!_nativeAvailable()) { _diag("onAppOpened chamado sem MMPGNative (preview web?) — ignorado"); return; }
    if (isPremium) { _diag("onAppOpened: usuário premium — paywall não avaliado"); return; }
    var now = Date.now();
    if (now - _lastAppOpenedCheckAt < MIN_RECHECK_INTERVAL_MS) {
      _diag("onAppOpened: ignorado por debounce (evento duplicado muito perto do anterior)");
      return;
    }
    _lastAppOpenedCheckAt = now;
    var sessions = _readInt(STORAGE_SESSIONS, 0) + 1;
    _write(STORAGE_SESSIONS, sessions);
    var shouldShow = _shouldShowForSession(sessions);
    var cooldownOk = _cooldownElapsed();
    _diag("onAppOpened: sessão " + sessions + " (gatilho=" + shouldShow + ", cooldown liberado=" + cooldownOk + ")");
    if (!shouldShow) return;
    if (!cooldownOk) return;
    _open(_nextVariant());
  }

  function _formatPriceLabel(plan) {
    if (!_priceCache) return "…";
    var entry = _priceCache[plan];
    return (entry && entry.price) ? entry.price : "…";
  }

  function _bodyHtml(text) {
    return String(text || "")
      .split(/\n\s*\n/)
      .map(function (part) { return "<p>" + part + "</p>"; })
      .join("");
  }

  function _open(variant) {
    if (_overlay) return;
    _injectStyleVars(variant);

    var overlay = document.createElement("div");
    overlay.className = "mmpg-pw-overlay";
    overlay.setAttribute("role", "dialog");
    overlay.setAttribute("aria-modal", "true");
    overlay.style.setProperty("--pw-a", variant.a);
    overlay.style.setProperty("--pw-b", variant.b);

    overlay.innerHTML =
      '<div class="mmpg-pw-shell">' +
      '<div class="mmpg-pw-brand">' +
      '<span class="mmpg-pw-brand-mark">M</span>' +
      '<span><strong>MMPG Remote</strong><small>controle local para sua TV</small></span>' +
      '</div>' +
      '<div class="mmpg-pw-inner">' +
      '<div class="mmpg-pw-badge">Versão gratuita</div>' +
      '<div class="mmpg-pw-hero">' +
      '<div class="mmpg-pw-icon" aria-hidden="true">' + variant.icon + '</div>' +
      '<div class="mmpg-pw-copy">' +
      '<h2 class="mmpg-pw-title">' + variant.title + '</h2>' +
      '<div class="mmpg-pw-body">' + _bodyHtml(variant.body) + '</div>' +
      '</div>' +
      '</div>' +
      '<div class="mmpg-pw-proof" aria-label="Benefícios do apoio">' +
      '<span>Sem anúncios</span>' +
      '<span>Sem rastreamento</span>' +
      '<span>Direto na rede local</span>' +
      '</div>' +
      '<div class="mmpg-pw-plans">' +
      '<button type="button" class="mmpg-pw-plan mmpg-pw-primary" id="mmpg-pw-lifetime">' +
      '<span class="mmpg-pw-plan-text"><span class="mmpg-pw-plan-kicker">Mais simples</span><span class="mmpg-pw-plan-name">Comprar vitalício</span>' +
      '<span class="mmpg-pw-plan-sub">Pagamento único, sem mensalidade</span></span>' +
      '<span class="mmpg-pw-plan-price" id="mmpg-pw-lifetime-price">' + _formatPriceLabel("lifetime") + '</span>' +
      '</button>' +
      '<button type="button" class="mmpg-pw-plan" id="mmpg-pw-monthly">' +
      '<span class="mmpg-pw-plan-text"><span class="mmpg-pw-plan-kicker">Apoio contínuo</span><span class="mmpg-pw-plan-name">Assinatura mensal</span>' +
      '<span class="mmpg-pw-plan-sub">Cancele quando quiser</span></span>' +
      '<span class="mmpg-pw-plan-price" id="mmpg-pw-monthly-price">' + _formatPriceLabel("monthly") + '</span>' +
      '</button>' +
      '</div>' +
      '<div class="mmpg-pw-free">' +
      '<button type="button" class="mmpg-pw-skip" id="mmpg-pw-skip" disabled>Continuar usando grátis</button>' +
      '<div class="mmpg-pw-progress" aria-hidden="true"><span id="mmpg-pw-progress-bar"></span></div>' +
      '<div class="mmpg-pw-countdown" id="mmpg-pw-countdown"></div>' +
      '</div>' +
      '<p class="mmpg-pw-note">O app continua funcionando na versão gratuita. Apoiar remove esta tela e ajuda a manter compatibilidade com mais TVs.</p>' +
      '</div>' +
      '</div>';

    document.body.appendChild(overlay);
    document.body.style.overflow = "hidden";
    _overlay = overlay;

    overlay.querySelector("#mmpg-pw-lifetime").addEventListener("click", function () {
      if (_nativeAvailable()) global.MMPGNative.buyLifetime();
    });
    overlay.querySelector("#mmpg-pw-monthly").addEventListener("click", function () {
      if (_nativeAvailable()) global.MMPGNative.buySubscription();
    });

    var skipBtn = overlay.querySelector("#mmpg-pw-skip");
    skipBtn.addEventListener("click", _close);
    _startCountdown(
      variant.holdSeconds,
      skipBtn,
      overlay.querySelector("#mmpg-pw-countdown"),
      overlay.querySelector("#mmpg-pw-progress-bar")
    );

    if (_nativeAvailable()) global.MMPGNative.getProductPrices();

    requestAnimationFrame(function () {
      requestAnimationFrame(function () { overlay.classList.add("mmpg-pw-vis"); });
    });

    _write(STORAGE_LAST_SHOWN, Date.now());
  }

  function _injectStyleVars() {
    // Placeholder para futura customização global de tema — hoje as cores
    // por variante são aplicadas via --pw-a/--pw-b direto no overlay.
  }

  function _startCountdown(seconds, skipBtn, countdownEl, progressEl) {
    var remaining = seconds;
    var render = function () {
      if (countdownEl) {
        countdownEl.textContent = remaining > 0
          ? "Você pode continuar gratuitamente em " + remaining + "s."
          : "Pronto. Você pode continuar usando grátis.";
      }
      if (progressEl) {
        var elapsed = Math.max(0, seconds - remaining);
        progressEl.style.width = Math.min(100, Math.round((elapsed / seconds) * 100)) + "%";
      }
    };
    render();
    _countdownTimer = setInterval(function () {
      remaining -= 1;
      render();
      if (remaining <= 0) {
        clearInterval(_countdownTimer);
        _countdownTimer = null;
        skipBtn.disabled = false;
        skipBtn.textContent = "Continuar usando grátis";
        if (progressEl) progressEl.style.width = "100%";
      }
    }, 1000);
  }

  function _close() {
    if (!_overlay) return;
    if (_countdownTimer) { clearInterval(_countdownTimer); _countdownTimer = null; }
    var el = _overlay;
    _overlay = null;
    document.body.style.overflow = "";
    el.classList.remove("mmpg-pw-vis");
    setTimeout(function () {
      if (el.parentNode) el.parentNode.removeChild(el);
    }, 280);
  }

  function _updatePrices(data) {
    _priceCache = data;
    if (!_overlay) return;
    var l = _overlay.querySelector("#mmpg-pw-lifetime-price");
    var m = _overlay.querySelector("#mmpg-pw-monthly-price");
    if (l) l.textContent = _formatPriceLabel("lifetime");
    if (m) m.textContent = _formatPriceLabel("monthly");
  }

  /**
   * Repassado pelo dispatcher window.MMPG.onNative() do app.js para os
   * eventos relevantes ao paywall — mantém um único ponto de entrada de
   * eventos nativos no app, como já era o padrão em app.js.
   */
  function handleNative(event, data) {
    if (event === "productPrices") {
      _updatePrices(data);
      return;
    }
    if (event === "purchaseResult") {
      if (data && data.ok && data.premium) _close();
      return;
    }
    if (event === "entitlement") {
      if (data && data.premium) _close();
      return;
    }
  }

  global.MMPGPaywall = {
    onAppOpened: onAppOpened,
    handleNative: handleNative,
    close: _close
  };

}(typeof window !== "undefined" ? window : this));
