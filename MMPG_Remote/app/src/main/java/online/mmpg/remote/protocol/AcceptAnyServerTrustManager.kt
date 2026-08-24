package online.mmpg.remote.protocol

import android.annotation.SuppressLint
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * Trust manager for Android TV Remote Protocol v2 connections (pairing on
 * port 6467, remote-control on port 6466).
 *
 * The protocol has the TV present a self-signed certificate; there is no
 * public CA involved and no CA the app could pin against - this is the
 * protocol's actual, documented design (confirmed against
 * tronikos/androidtvremote2 and louis49/androidtv-remote, both of which
 * disable normal certificate-chain validation for exactly this reason; see
 * THIRD_PARTY_NOTICES.md), not a security shortcut taken here. Trust is
 * instead established out-of-band: the user reads a PIN off the TV screen
 * during pairing and types it into this app, and the pairing handshake's
 * cryptographic challenge (see [PairingMessages.computePinSecret]) proves
 * both sides saw the same PIN and the same pair of certificates. After that,
 * [CertificateStore.isPaired] gates whether this app will reconnect to a
 * given host at all.
 *
 * This trust manager therefore accepts any server certificate presented; it
 * must only ever be used for LAN connections to a TV the user has explicitly
 * chosen to pair with or reconnect to, never for general internet traffic.
 *
 * `lint` flags `TrustAllX509TrustManager`/`CustomX509TrustManager` here (both
 * are, generically, correct things to flag - blindly trusting any
 * certificate is normally a serious bug). Suppressed deliberately, not
 * ignored: this is the one narrow, reviewed case where it is the protocol's
 * actual design (see above and THIRD_PARTY_NOTICES.md), gated end-to-end by
 * [CertificateStore.isPaired] plus the PIN challenge in
 * [PairingMessages.computePinSecret], and only ever reachable from
 * [PairingClient]/[RemoteClient] over LAN sockets the user explicitly chose.
 */
@SuppressLint("TrustAllX509TrustManager", "CustomX509TrustManager")
internal object AcceptAnyServerTrustManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        // Not applicable: this trust manager is only ever installed client-side.
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain.isNullOrEmpty()) {
            throw CertificateException("A TV não apresentou certificado durante o handshake TLS.")
        }
        // Intentionally accepted unconditionally; see class doc for why this is
        // correct for this protocol rather than a shortcut.
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
}
