package com.example.ncphonetrack

import android.content.Context
import android.content.SharedPreferences
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object CertHelper {
    private const val PREFS = "nc_phonetrack"
    private const val KEY_ACCEPTED_HASH = "accepted_cert_hash"
    private const val KEY_ACCEPTED_CERTS = "accepted_certs"

    fun acceptedCerts(prefs: SharedPreferences): List<ServerCert> {
        val raw = prefs.getString(KEY_ACCEPTED_CERTS, null)
        val stored = mutableListOf<ServerCert>()
        if (!raw.isNullOrBlank()) {
            val arr = org.json.JSONArray(raw)
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val hash = item.optString("hash")
                if (hash.isNotBlank()) stored.add(ServerCert(hash, item.optString("subject"), item.optString("issuer")))
            }
        }
        val legacy = prefs.getString(KEY_ACCEPTED_HASH, null)
        if (!legacy.isNullOrBlank() && stored.none { it.hash == legacy }) {
            stored.add(ServerCert(legacy, "", ""))
        }
        return stored
    }

    fun getAcceptedHash(prefs: SharedPreferences): String? = acceptedCerts(prefs).firstOrNull()?.hash

    fun accept(prefs: SharedPreferences, cert: ServerCert) {
        val next = acceptedCerts(prefs).filterNot { it.hash == cert.hash } + cert
        saveAccepted(prefs, next)
        LogManager.add("Accepted cert hash: ${cert.hash}")
    }

    fun setAcceptedHash(prefs: SharedPreferences, hash: String) {
        accept(prefs, ServerCert(hash, "", ""))
    }

    fun forget(prefs: SharedPreferences, hash: String) {
        saveAccepted(prefs, acceptedCerts(prefs).filterNot { it.hash == hash })
    }

    fun clearAccepted(prefs: SharedPreferences) {
        saveAccepted(prefs, emptyList())
    }

    private fun saveAccepted(prefs: SharedPreferences, certs: List<ServerCert>) {
        val arr = org.json.JSONArray()
        certs.forEach { cert ->
            arr.put(org.json.JSONObject().put("hash", cert.hash).put("subject", cert.subject).put("issuer", cert.issuer))
        }
        prefs.edit().putString(KEY_ACCEPTED_CERTS, arr.toString()).remove(KEY_ACCEPTED_HASH).commit()
    }

    fun looksLikeTlsFailure(message: String?): Boolean {
        val text = message?.lowercase() ?: return false
        return text.contains("ssl") || text.contains("cert") || text.contains("trust anchor") || text.contains("handshake")
    }

    fun sha256Fingerprint(cert: X509Certificate): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(cert.encoded)
        return digest.joinToString(":") { "%02X".format(it) }
    }

    fun buildClient(prefs: SharedPreferences): OkHttpClient {
        val pins = acceptedCerts(prefs).map { it.hash }.toSet()
        val base = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
        if (pins.isEmpty()) return base.build()
        val system = systemTrustManager()
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                system.checkClientTrusted(chain, authType)
            }
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                try {
                    system.checkServerTrusted(chain, authType)
                } catch (e: java.security.cert.CertificateException) {
                    val hash = chain?.firstOrNull()?.let { sha256Fingerprint(it) }
                    if (hash == null || hash !in pins) throw e
                }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
        }
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustManager), null)
        val defaultVerifier = javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier()
        return base
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { host, session ->
                if (defaultVerifier.verify(host, session)) return@hostnameVerifier true
                val leaf = session.peerCertificates.firstOrNull() as? X509Certificate ?: return@hostnameVerifier false
                sha256Fingerprint(leaf) in pins
            }
            .build()
    }

    private fun systemTrustManager(): X509TrustManager {
        val factory = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as java.security.KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    fun probeCertHash(url: String, callback: (String?) -> Unit) {
        inspect(url) { probe -> callback(probe.cert?.hash) }
    }

    fun inspect(url: String, callback: (CertProbe) -> Unit) {
        Thread {
            if (!url.startsWith("https://")) {
                callback(CertProbe(CertTrust.FAILED, null, "Only an https:// address has a certificate"))
                return@Thread
            }
            val request = okhttp3.Request.Builder().url(url).build()
            try {
                OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).build()
                    .newCall(request).execute().use { response ->
                        val cert = leaf(response.handshake?.peerCertificates)
                        callback(CertProbe(CertTrust.TRUSTED, cert, "already trusted"))
                    }
            } catch (e: javax.net.ssl.SSLException) {
                val cert = captureUntrusted(url)
                if (cert == null) {
                    callback(CertProbe(CertTrust.FAILED, null, e.message ?: "TLS failed"))
                } else {
                    callback(CertProbe(CertTrust.UNTRUSTED, cert, e.message ?: "untrusted certificate"))
                }
            } catch (e: Exception) {
                LogManager.add("Cert probe failed: ${e.message}")
                callback(CertProbe(CertTrust.FAILED, null, e.message ?: "connection failed"))
            }
        }.start()
    }

    private fun leaf(certs: List<java.security.cert.Certificate>?): ServerCert? {
        val cert = certs?.firstOrNull() as? X509Certificate ?: return null
        return ServerCert(sha256Fingerprint(cert), cert.subjectX500Principal.name, cert.issuerX500Principal.name)
    }

    private fun captureUntrusted(url: String): ServerCert? {
        return try {
            var captured: X509Certificate? = null
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    captured = chain?.firstOrNull()
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            val ssl = SSLContext.getInstance("TLS")
            ssl.init(null, arrayOf<TrustManager>(trustAll), java.security.SecureRandom())
            val client = OkHttpClient.Builder()
                .sslSocketFactory(ssl.socketFactory, trustAll)
                .hostnameVerifier(HostnameVerifier { _, _ -> true })
                .connectTimeout(15, TimeUnit.SECONDS)
                .build()
            client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { response ->
                leaf(response.handshake?.peerCertificates) ?: captured?.let {
                    ServerCert(sha256Fingerprint(it), it.subjectX500Principal.name, it.issuerX500Principal.name)
                }
            }
        } catch (e: Exception) {
            LogManager.add("Untrusted cert capture failed: ${e.message}")
            null
        }
    }
}

data class ServerCert(val hash: String, val subject: String, val issuer: String)

enum class CertTrust { TRUSTED, UNTRUSTED, FAILED }

data class CertProbe(val trust: CertTrust, val cert: ServerCert?, val message: String)

