package com.sidephone.aviary.imessage

import android.content.Context
import java.io.File

/**
 * JNI bridge to libaviary_imessage.so (rustpush + on-device anisette).
 *
 * The native symbol names are Java_com_sidephone_aviary_imessage_ImessageNative_*,
 * so this object MUST live in package com.sidephone.aviary.imessage.
 *
 * All calls block; callers must be on a background dispatcher. Each returns a JSON
 * string of the shape {"ok":true,...} or {"ok":false,"error":"..."}.
 */
object ImessageNative {
    init {
        System.loadLibrary("aviary_imessage")
    }

    /** configPlist = a RelayConfig serialized as an XML plist (see [relayConfigPlist]). */
    external fun nativeInit(configPlist: String, libDir: String, configDir: String): String
    external fun nativeLogin(email: String, password: String): String
    external fun nativeSubmit2fa(code: String): String
    external fun nativeRegister(): String
    external fun nativeSendText(participantsJson: String, text: String, replyGuid: String, guid: String): String
    external fun nativePoll(timeoutMs: Long): String

    /** Send a tapback reaction to [targetGuid] (enable=false removes it). [targetText] is the
     *  reacted-to message's text, which Apple embeds so the tapback renders. */
    external fun nativeSendReaction(
        participantsJson: String, targetGuid: String, targetText: String, emoji: String, enable: Boolean,
    ): String

    /** Send a typing indicator (true = started, false = stopped). */
    external fun nativeSendTyping(participantsJson: String, typing: Boolean): String

    /** Send a read receipt for [guid] back to the sender. */
    external fun nativeSendRead(participantsJson: String, guid: String): String

    /** Edit a previously-sent message ([targetGuid]) to [newText]. */
    external fun nativeSendEdit(participantsJson: String, targetGuid: String, newText: String): String

    /** Unsend (retract) a previously-sent message ([targetGuid]). */
    external fun nativeSendUnsend(participantsJson: String, targetGuid: String): String

    /** Send a media attachment from a local file [path] (uploaded via MMCS), optional [caption]. */
    external fun nativeSendMedia(
        participantsJson: String, path: String, mime: String, filename: String, caption: String, guid: String,
    ): String

    /** Diagnostic: JSON {ok, handles:[...]} — the registered iMessage handles. */
    external fun nativeHandles(): String

    /** IDS lookup: returns JSON {ok, reachable} — whether the address is on iMessage. */
    external fun nativeCanReach(address: String): String

    /** Smoke test: runs x86-64 `mov rax,0x1234;ret` under the embedded emulator. Returns 0x1234 if the CPU emulator executes on-device. */
    external fun nativeUcSmoke(): Long

    /** Diagnostic: generate on-device anisette (ADI) headers; returns JSON {ok, headers}. */
    external fun nativeTestAnisette(): String

    /** The Apple ADI libraries, bundled as assets, that the native provider dlopen-loads. */
    private val ADI_LIBS = listOf(
        "libstoreservicescore.so", // main ADI proxy (11 flat symbols)
        "libCoreADI.so",           // loaded by the above
        "libCoreFoundation.so",
        "libc++_shared.so",
        "libmediaplatform.so",
    )

    /**
     * Extracts the bundled ADI libraries to <filesDir>/adi/lib/arm64-v8a/ (the layout
     * store_services_core.rs expects) and returns the directory to pass as `libDir`.
     * Assets live at assets/adi/arm64-v8a/<lib>.
     */
    fun prepareLibDir(context: Context): String {
        val base = File(context.filesDir, "adi")
        val libArch = File(base, "lib/arm64-v8a").apply { mkdirs() }
        for (name in ADI_LIBS) {
            val out = File(libArch, name)
            if (out.exists() && out.length() > 0) continue
            context.assets.open("adi/arm64-v8a/$name").use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
        }
        return base.absolutePath
    }

    /** Writable directory for anisette provisioning state + saved registration. */
    fun configDir(context: Context): String =
        File(context.filesDir, "imessage").apply { mkdirs() }.absolutePath

    /**
     * Builds a RelayConfig XML plist. `host`/`code` point at the validation-data
     * (NAC) source — a Mac generator or relay (Stage 7). The version block is
     * normally filled from the relay's get-version-info; placeholders here are a
     * stand-in until that call is wired.
     */
    fun relayConfigPlist(
        host: String,
        code: String,
        version: RelayVersions = RelayVersions.PLACEHOLDER,
    ): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<plist version=\"1.0\"><dict>\n")
        append("<key>version</key><dict>\n")
        appendStr("software_build_id", version.softwareBuildId)
        appendStr("software_name", version.softwareName)
        appendStr("software_version", version.softwareVersion)
        appendStr("serial_number", version.serialNumber)
        appendStr("hardware_version", version.hardwareVersion)
        appendStr("unique_device_id", version.uniqueDeviceId)
        append("</dict>\n")
        appendStr("icloud_ua", version.icloudUa)
        appendStr("aoskit_version", version.aoskitVersion)
        appendStr("dev_uuid", version.devUuid)
        append("<key>protocol_version</key><integer>${version.protocolVersion}</integer>\n")
        appendStr("host", host)
        appendStr("code", code)
        append("</dict></plist>\n")
    }

    private fun StringBuilder.appendStr(key: String, value: String) {
        append("<key>").append(key).append("</key><string>")
        append(xmlEscape(value)).append("</string>\n")
    }

    private fun xmlEscape(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

/**
 * Version/hardware identifiers a RelayConfig needs. Real values come from the relay's
 * get-version-info (Beeper/OpenBubbles model); PLACEHOLDER is a compile-time stand-in.
 */
data class RelayVersions(
    val softwareBuildId: String,
    val softwareName: String,
    val softwareVersion: String,
    val serialNumber: String,
    val hardwareVersion: String,
    val uniqueDeviceId: String,
    val icloudUa: String,
    val aoskitVersion: String,
    val devUuid: String,
    val protocolVersion: Int,
) {
    companion object {
        // TODO(stage 7): fetch from the relay instead of hardcoding.
        val PLACEHOLDER = RelayVersions(
            softwareBuildId = "22G120",
            softwareName = "macOS",
            softwareVersion = "13.6.4",
            serialNumber = "0000000000",
            hardwareVersion = "iMac13,1",
            uniqueDeviceId = "00000000-0000-0000-0000-000000000000",
            icloudUa = "com.apple.iCloudHelper/282 CFNetwork/1494.0.7 Darwin/23.4.0",
            aoskitVersion = "com.apple.AOSKit/282 (com.apple.accountsd/113)",
            devUuid = "00000000-0000-0000-0000-000000000000",
            protocolVersion = 1640,
        )
    }
}
