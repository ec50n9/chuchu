package io.github.ec50n9.roost.service.ssh

fun interface HostKeyPolicy {
    fun verify(host: String, port: Int, algorithm: String, keyBytes: ByteArray): Boolean
}
