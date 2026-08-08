package com.ycode.app.remote

import android.content.Context
import android.util.Base64
import com.google.gson.JsonObject
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.PublicKey
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

class RemoteToolExecutor(context: Context) {
    private val store = RemoteProfileStore(context)

    fun execute(tool: String, args: JsonObject): Any {
        if (tool == "remote_connections") return store.all().map { mapOf("id" to it.id, "name" to it.name, "protocol" to it.protocol, "host" to it.host, "port" to it.port, "allowCommands" to it.allowCommands, "allowWrite" to it.allowWrite, "allowDelete" to it.allowDelete) }
        val profile = if (tool.startsWith("direct_")) directProfile(args) else profile(args.string("connection_id"))
        return when (tool) {
            "ssh_exec", "direct_ssh_exec" -> sshExec(profile, args.string("command"), args.int("timeout_seconds", 30).coerceIn(1, 45))
            "direct_remote" -> directRemote(profile, args)
            "remote_list" -> remote(profile, args.string("path"), Operation.LIST)
            "remote_read" -> remote(profile, args.string("path"), Operation.READ)
            "remote_write" -> { require(profile.allowWrite) { "该连接未允许 AI 写入" }; remote(profile, args.string("path"), Operation.WRITE, args.string("content")) }
            "remote_mkdir" -> { require(profile.allowWrite) { "该连接未允许 AI 写入" }; remote(profile, args.string("path"), Operation.MKDIR) }
            "remote_move" -> { require(profile.allowWrite) { "该连接未允许 AI 重命名" }; move(profile, args.string("source"), args.string("target")) }
            "remote_delete" -> { require(profile.allowDelete) { "该连接未允许 AI 删除" }; remote(profile, args.string("path"), Operation.DELETE) }
            else -> error("未知远程工具：$tool")
        }
    }

    private fun profile(id: String) = store.all().firstOrNull { it.id == id } ?: error("连接不存在：$id，请先在 Ycode 的远程连接中配置")
    private fun directProfile(args: JsonObject): ConnectionProfile {
        val protocol = args.string("protocol").lowercase()
        require(protocol in setOf("ssh", "sftp", "ftp")) { "protocol 只允许 ssh、sftp 或 ftp" }
        val host = args.string("host")
        require(host.length <= 253 && host.none { it.isWhitespace() || it == '\u0000' }) { "服务器地址无效" }
        val port = args.int("port", if (protocol == "ftp") 21 else 22)
        require(port in 1..65535) { "端口必须在 1 到 65535 之间" }
        val fingerprint = args.get("host_key_sha256")?.asString.orEmpty()
        if (protocol != "ftp") require(fingerprint.isNotBlank()) { "临时 SSH/SFTP 连接必须提供 host_key_sha256，防止中间人攻击" }
        return ConnectionProfile("temporary", "临时连接", protocol, host, port, args.string("username"), args.string("password"), fingerprint, true, true, args.get("confirm_delete")?.asBoolean == true)
    }

    private fun directRemote(profile: ConnectionProfile, args: JsonObject): Any = when (args.string("action").lowercase()) {
        "list" -> remote(profile, args.string("path"), Operation.LIST)
        "read" -> remote(profile, args.string("path"), Operation.READ)
        "write" -> remote(profile, args.string("path"), Operation.WRITE, args.string("content"))
        "mkdir" -> remote(profile, args.string("path"), Operation.MKDIR)
        "move" -> move(profile, args.string("source"), args.string("target"))
        "delete" -> { require(profile.allowDelete) { "删除操作必须明确传入 confirm_delete=true" }; remote(profile, args.string("path"), Operation.DELETE) }
        else -> error("action 只允许 list、read、write、mkdir、move 或 delete")
    }

    private fun sshExec(profile: ConnectionProfile, command: String, timeout: Int): Any {
        require(profile.protocol in setOf("ssh", "sftp")) { "该连接不是 SSH/SFTP" }
        require(profile.allowCommands) { "该连接未允许 AI 执行命令" }
        require(command.length in 1..4000 && '\u0000' !in command) { "命令无效或超过 4000 字符" }
        return withSsh(profile) { ssh -> ssh.startSession().use { session ->
            val process = session.exec(command)
            val readers = Executors.newFixedThreadPool(2)
            try {
                val stdout = readers.submit<CommandOutput> { process.inputStream.readCommandOutput() }
                val stderr = readers.submit<CommandOutput> { process.errorStream.readCommandOutput() }
                process.join(timeout.toLong(), TimeUnit.SECONDS)
                if (process.isOpen) { process.close(); error("SSH 命令执行超过 $timeout 秒") }
                val out = stdout.get(3, TimeUnit.SECONDS); val err = stderr.get(3, TimeUnit.SECONDS)
                mapOf("exitCode" to process.exitStatus, "stdout" to out.text, "stderr" to err.text, "stdoutTruncated" to out.truncated, "stderrTruncated" to err.truncated)
            } finally { readers.shutdownNow() }
        } }
    }

    private fun remote(profile: ConnectionProfile, path: String, operation: Operation, content: String = ""): Any {
        validatePath(path, operation == Operation.DELETE)
        return if (profile.protocol == "ftp") withFtp(profile) { ftp -> ftpOperation(ftp, path, operation, content) } else {
            require(profile.protocol == "sftp") { "远程文件工具需要 SFTP 或 FTP 连接" }
            withSsh(profile) { ssh -> ssh.newSFTPClient().use { sftp -> when (operation) {
                Operation.LIST -> sftp.ls(path).filterNot { it.name in setOf(".", "..") }.take(1000).map(::sftpEntry)
                Operation.READ -> sftp.open(path, EnumSet.of(OpenMode.READ)).use { file ->
                    require(file.length() <= MAX_FILE) { "远程文件超过 512KB" }; val output = ByteArrayOutputStream(); val buffer = ByteArray(8192); var offset = 0L
                    while (true) { val count = file.read(offset, buffer, 0, buffer.size); if (count <= 0) break; output.write(buffer, 0, count); offset += count }
                    mapOf("path" to path, "content" to output.toString(Charsets.UTF_8.name()), "bytes" to output.size())
                }
                Operation.WRITE -> { val bytes = checkedContent(content); sftp.open(path, EnumSet.of(OpenMode.CREAT, OpenMode.TRUNC, OpenMode.WRITE)).use { it.write(0, bytes, 0, bytes.size) }; mapOf("path" to path, "bytes" to bytes.size, "written" to true) }
                Operation.MKDIR -> { sftp.mkdirs(path); mapOf("path" to path, "created" to true) }
                Operation.DELETE -> { val counts = intArrayOf(0, 0); deleteSftp(sftp, path, counts); mapOf("path" to path, "deletedFiles" to counts[0], "deletedDirectories" to counts[1]) }
            } } }
        }
    }

    private fun move(profile: ConnectionProfile, source: String, target: String): Any {
        validatePath(source, true); validatePath(target, true)
        return if (profile.protocol == "ftp") withFtp(profile) { require(it.rename(source, target)) { "FTP 重命名失败：${it.replyString}" }; mapOf("source" to source, "target" to target, "moved" to true) }
        else { require(profile.protocol == "sftp") { "远程文件工具需要 SFTP 或 FTP 连接" }; withSsh(profile) { ssh -> ssh.newSFTPClient().use { it.rename(source, target) }; mapOf("source" to source, "target" to target, "moved" to true) } }
    }

    private fun <T> withSsh(profile: ConnectionProfile, block: (SSHClient) -> T): T {
        val expected = profile.hostKeySha256.takeIf(String::isNotBlank)?.let(::normalizeFingerprint)
        val ssh = SSHClient().apply {
            addHostKeyVerifier(object : HostKeyVerifier {
                override fun verify(hostname: String, port: Int, key: PublicKey): Boolean = expected == null || normalizeFingerprint("SHA256:" + Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(key.encoded), Base64.NO_WRAP or Base64.NO_PADDING)) == expected
                override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
            }); connectTimeout = 15_000; timeout = 45_000
        }
        return try { ssh.connect(profile.host, profile.port); ssh.authPassword(profile.username, profile.password); block(ssh) } finally { runCatching { ssh.disconnect() }; runCatching { ssh.close() } }
    }

    private fun <T> withFtp(profile: ConnectionProfile, block: (FTPClient) -> T): T {
        @Suppress("DEPRECATION") val ftp = FTPClient().apply { connectTimeout = 15_000; defaultTimeout = 15_000; setDataTimeout(45_000) }
        return try { ftp.connect(profile.host, profile.port); require(ftp.login(profile.username, profile.password)) { "FTP 登录失败" }; ftp.enterLocalPassiveMode(); ftp.setFileType(FTP.BINARY_FILE_TYPE); block(ftp) }
        finally { if (ftp.isConnected) { runCatching { ftp.logout() }; runCatching { ftp.disconnect() } } }
    }

    private fun ftpOperation(ftp: FTPClient, path: String, operation: Operation, content: String): Any = when (operation) {
        Operation.LIST -> ftp.listFiles(path).take(1000).map { mapOf("name" to it.name, "type" to if (it.isDirectory) "directory" else "file", "size" to it.size, "modified" to it.timestampInstant?.toString()) }
        Operation.READ -> {
            val input = ftp.retrieveFileStream(path) ?: error("FTP 无法读取文件")
            val bytes = input.use { it.readLimited() }
            require(ftp.completePendingCommand()) { "FTP 读取未完成" }
            mapOf("path" to path, "content" to String(bytes, Charsets.UTF_8), "bytes" to bytes.size)
        }
        Operation.WRITE -> checkedContent(content).inputStream().use { require(ftp.storeFile(path, it)) { "FTP 写入失败：${ftp.replyString}" }; mapOf("path" to path, "bytes" to content.toByteArray().size, "written" to true) }
        Operation.MKDIR -> { require(ftp.makeDirectory(path) || ftp.changeWorkingDirectory(path)) { "FTP 创建目录失败：${ftp.replyString}" }; mapOf("path" to path, "created" to true) }
        Operation.DELETE -> { val counts = intArrayOf(0, 0); deleteFtp(ftp, path, counts); mapOf("path" to path, "deletedFiles" to counts[0], "deletedDirectories" to counts[1]) }
    }

    private fun deleteSftp(sftp: net.schmizz.sshj.sftp.SFTPClient, path: String, counts: IntArray) { val directory = runCatching { sftp.stat(path).type == net.schmizz.sshj.sftp.FileMode.Type.DIRECTORY }.getOrDefault(false); if (directory) { sftp.ls(path).filterNot { it.name in setOf(".", "..") }.forEach { deleteSftp(sftp, path.trimEnd('/') + "/" + it.name, counts) }; sftp.rmdir(path); counts[1]++ } else { sftp.rm(path); counts[0]++ } }
    private fun deleteFtp(ftp: FTPClient, path: String, counts: IntArray) { val entries = ftp.listFiles(path); val directory = entries.isNotEmpty() || ftp.changeWorkingDirectory(path).also { if (it) ftp.changeToParentDirectory() }; if (directory) { entries.filterNot { it.name in setOf(".", "..") }.forEach { deleteFtp(ftp, path.trimEnd('/') + "/" + it.name, counts) }; require(ftp.removeDirectory(path)) { "FTP 删除目录失败：${ftp.replyString}" }; counts[1]++ } else { require(ftp.deleteFile(path)) { "FTP 删除文件失败：${ftp.replyString}" }; counts[0]++ } }
    private fun sftpEntry(value: RemoteResourceInfo) = mapOf("name" to value.name, "path" to value.path, "type" to if (value.isDirectory) "directory" else "file", "size" to value.attributes.size, "modified" to value.attributes.mtime)
    private fun checkedContent(value: String) = value.toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_FILE) { "内容超过 512KB" } }
    private fun java.io.InputStream.readLimited(): ByteArray { val out = ByteArrayOutputStream(); val buffer = ByteArray(8192); while (true) { val count = read(buffer); if (count < 0) break; require(out.size() + count <= MAX_FILE) { "远程文件超过 512KB" }; out.write(buffer, 0, count) }; return out.toByteArray() }
    private fun java.io.InputStream.readCommandOutput(): CommandOutput {
        val out = ByteArrayOutputStream(); val buffer = ByteArray(8192); var truncated = false
        while (true) { val count = read(buffer); if (count < 0) break; val keep = (MAX_FILE - out.size()).coerceAtLeast(0).coerceAtMost(count); if (keep > 0) out.write(buffer, 0, keep); if (keep < count) truncated = true }
        return CommandOutput(String(out.toByteArray(), Charsets.UTF_8), truncated)
    }
    private fun validatePath(path: String, rejectRoot: Boolean = false) {
        require(path.isNotBlank() && path.length <= 2048 && '\u0000' !in path) { "远程路径无效" }
        if (rejectRoot) {
            val normalized = path.replace('\\', '/').replace(Regex("/+"), "/").trim().trimEnd('/')
            val segments = normalized.split('/').filter(String::isNotBlank)
            require(normalized !in setOf("", ".") && segments.isNotEmpty()) { "拒绝修改或删除远程根目录" }
            require(segments.none { it == "." || it == ".." }) { "修改或删除路径不能包含 . 或 .." }
        }
    }
    private fun normalizeFingerprint(value: String) = value.trim().removePrefix("SHA256:").replace("=", "").lowercase()
    private fun JsonObject.string(key: String) = get(key)?.asString?.takeIf { it.isNotBlank() } ?: error("$key 必须提供")
    private fun JsonObject.int(key: String, fallback: Int) = get(key)?.asInt ?: fallback
    private enum class Operation { LIST, READ, WRITE, MKDIR, DELETE }
    private data class CommandOutput(val text: String, val truncated: Boolean)
    companion object { private const val MAX_FILE = 512 * 1024 }
}
