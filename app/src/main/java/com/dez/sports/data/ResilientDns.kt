package com.dez.sports.data

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The phone's DNS first; if that can't find ESPN ("Unable to resolve host") or is slow to answer, ask Cloudflare
 * directly over HTTPS, and failing that reuse the last address that worked.
 */
object ResilientDns : Dns {
    private val known = ConcurrentHashMap<String, List<InetAddress>>()
    private val pool = Executors.newCachedThreadPool()

    /** When the phone's DNS last failed. For a few minutes after, Cloudflare goes first for every host. */
    @Volatile private var systemFailedAt = 0L

    private val doh by lazy {
        DnsOverHttps.Builder()
            .client(OkHttpClient())
            .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
            .bootstrapDnsHosts(listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111").map { InetAddress.getByName(it) })
            .build()
    }

    private fun cloudflare(host: String) = runCatching { doh.lookup(host) }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** The phone's own lookup, given up on after [waitMs] (it keeps running and still fills [known] if it answers). */
    private fun system(host: String, waitMs: Long): List<InetAddress>? {
        val f = pool.submit<List<InetAddress>?> {
            (try { Dns.SYSTEM.lookup(host) } catch (e: UnknownHostException) { null })?.takeIf { it.isNotEmpty() }
                ?.also { known[host] = it } ?: run { systemFailedAt = System.currentTimeMillis(); null }
        }
        return runCatching { f.get(waitMs, TimeUnit.MILLISECONDS) }.onFailure { systemFailedAt = System.currentTimeMillis() }.getOrNull()
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val unhealthy = System.currentTimeMillis() - systemFailedAt < 5 * 60_000
        val found = if (unhealthy) cloudflare(hostname) ?: system(hostname, 3_000)
            else system(hostname, 1_500) ?: cloudflare(hostname) ?: system(hostname, 3_000)
        if (found != null) { known[hostname] = found; return found }
        return known[hostname] ?: throw UnknownHostException("Couldn't find $hostname. Check your connection.")
    }
}
