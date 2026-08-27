package com.sidephone.aviary.transport.signal

import java.io.ByteArrayOutputStream

/**
 * Minimal protobuf wire-format reader/writer — just enough for Signal's tiny
 * provisioning messages, so we don't need a protoc build step. Handles varint (0),
 * 64-bit (1), length-delimited (2) and 32-bit (5) wire types.
 */
object MiniProto {

    data class Field(val number: Int, val varint: Long? = null, val bytes: ByteArray? = null)

    fun parse(data: ByteArray): List<Field> {
        val fields = mutableListOf<Field>()
        var i = 0
        fun varint(): Long {
            var shift = 0
            var result = 0L
            while (true) {
                val b = data[i++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b < 0x80) return result
                shift += 7
            }
        }
        while (i < data.size) {
            val tag = varint()
            val number = (tag ushr 3).toInt()
            when ((tag and 7).toInt()) {
                0 -> fields += Field(number, varint = varint())
                1 -> { i += 8 }
                2 -> {
                    val len = varint().toInt()
                    fields += Field(number, bytes = data.copyOfRange(i, i + len))
                    i += len
                }
                5 -> { i += 4 }
                else -> return fields // unknown wire type; stop rather than misparse
            }
        }
        return fields
    }

    fun bytesField(fields: List<Field>, number: Int): ByteArray? =
        fields.find { it.number == number }?.bytes

    fun stringField(fields: List<Field>, number: Int): String? =
        bytesField(fields, number)?.decodeToString()

    fun varintField(fields: List<Field>, number: Int): Long? =
        fields.find { it.number == number }?.varint

    class Writer {
        private val out = ByteArrayOutputStream()

        fun varint(number: Int, value: Long) = apply {
            tag(number, 0); writeVarint(value)
        }

        fun bytes(number: Int, value: ByteArray) = apply {
            tag(number, 2); writeVarint(value.size.toLong()); out.write(value)
        }

        fun string(number: Int, value: String) = bytes(number, value.encodeToByteArray())

        fun toByteArray(): ByteArray = out.toByteArray()

        private fun tag(number: Int, wireType: Int) =
            writeVarint(((number shl 3) or wireType).toLong())

        private fun writeVarint(v: Long) {
            var value = v
            while (true) {
                if (value and 0x7F.inv().toLong() == 0L) {
                    out.write(value.toInt()); return
                }
                out.write(((value.toInt() and 0x7F) or 0x80))
                value = value ushr 7
            }
        }
    }
}
