package com.example.ncphonetrack

import android.content.Context
import android.content.SharedPreferences
import okhttp3.OkHttpClient
import org.json.JSONArray
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object CertHelper {
    private const val PREFS = "nc_phonetrack"
    private const val KEY_ACCEPTED_HASHES = "accepted_cert_hashes"

    fun getAcceptedHashes(prefs: SharedPreferences): Set<String> {
        val raw = prefs.getString(KEY_ACCEPTED_HASHES, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    fun addAcceptedHash(prefs: SharedPreferences, hash: String) {
        val current = getAcceptedHashes(prefs).toMutableSet()
        current.add(hash)
        prefs.edit().putString(KEY_ACCEPTED_HASHES, JSONArray(current.toList()).toString()).apply()
        LogManager.add("Accepted cert hash: $hash (total: ${current.size})")
    }

    fun clearAccepted(prefs: SharedPreferences) {
        prefs.edit().remove(KEY_ACCEPTED_HASHES).apply()
    }

    fun sha256Fingerprint(cert: X509Certificate): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(cert.encoded)
        return digest.joinToString(":") { "%02X".format(it) }
    }

    fun buildClient(prefs: SharedPreferences): OkHttpClient {
        val accepted = getAcceptedHashes(prefs)
        return if (accepted.isEmpty()) {
            OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).build()
        } else {
            val trustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    if (chain.isNullOrEmpty()) throw java.security.cert.CertificateException("Empty chain")
                    val hash = sha256Fingerprint(chain[0])
                    if (hash !in accepted) {
                        throw java.security.cert.CertificateException("Cert hash not accepted: $hash")
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
        Thread {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .build()
                val request = okhttp3.Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    val handshake = response.handshake
                    val cert = handshake?.peerCertificates?.firstOrNull() as? X509Certificate
                    val hash = cert?.let { sha256Fingerprint(it) }
                    callback(hash)
                }
            } catch (e: Exception) {
                LogManager.add("Cert probe failed: ${e.message}")
                callback(null)
            }
        }.start()
    }
}
