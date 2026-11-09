package io.github.ec50n9.roost.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "host_profiles",
    indices = [Index(value = ["name"])],
)
data class HostProfile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val password: String = "",
    val keyId: Long? = null,
    val keyPassphrase: String = "",
    val transport: Transport = Transport.SSH,
    val authMethod: AuthMethod = AuthMethod.Password,
    @ColumnInfo(defaultValue = "0")
    val requireAuthOnConnect: Boolean = false,
    val postConnectCommand: String? = null,
    @ColumnInfo(defaultValue = "NULL")
    val multiplexer: MultiplexerType? = null,
    // Connect this host (and list its multiplexer sessions) when the server list opens.
    // Only meaningful with a multiplexer: a bare SSH host has no "connected but idle" state.
    @ColumnInfo(defaultValue = "0")
    val autoConnect: Boolean = false,
    // PATH prepended to the multiplexer's exec-channel commands. Exec runs without the account's
    // rc files, so mise/nix/asdf/homebrew shims are not on PATH there and tmux/zmx would report
    // "command not found". Written by the app from a probe of the account's interactive shell, not
    // by the user: no UI edits it, it exists so the probe survives the session.
    @ColumnInfo(defaultValue = "NULL")
    val remotePath: String? = null,
)
