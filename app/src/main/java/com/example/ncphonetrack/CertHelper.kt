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

    fun getAcceptedHash(prefs: SharedPreferences): String? =
        prefs.getString(KEY_ACCEPTED_HASH, null)

    fun setAcceptedHash(prefs: SharedPreferences, hash: String) {
        prefs.edit().putString(KEY_ACCEPTED_HASH, hash).apply()
        LogManager.add("Accepted cert hash: $hash")
    }

    fun clearAccepted(prefs: SharedPreferences) {
        prefs.edit().remove(KEY_ACCEPTED_HASH).apply()
    }

    fun sha256Fingerprint(cert: X509Certificate): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(cert.encoded)
        return digest.joinToString(":") { "%02X".format(it) }
    }

    fun buildClient(prefs: SharedPreferences): OkHttpClient {
        val accepted = getAcceptedHash(prefs)
        return if (accepted == null) {
            OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).build()
        } else {
            val trustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    if (chain.isNullOrEmpty()) throw java.security.cert.CertificateException("Empty chain")
                    val hash = sha256Fingerprint(chain[0])
                    if (hash != accepted) {
                        throw java.security.cert.CertificateException("Cert hash mismatch: $hash")
                    }
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<TrustManager>(trustManager), null)
            OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustManager)
                .hostnameVerifier(HostnameVerifier { _, _ -> true })
                .connectTimeout(30, TimeUnit.SECONDS)
                .build()
        }
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

