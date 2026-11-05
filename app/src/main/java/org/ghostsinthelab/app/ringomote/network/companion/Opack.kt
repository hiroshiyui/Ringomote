package org.ghostsinthelab.app.ringomote.network.companion

import java.io.ByteArrayOutputStream

/**
 * OPACK, Apple's private serialisation used by Companion Link messages.
 *
 * Values map to Kotlin as: `null`, [Boolean], [Long] (any integer; [Int] is accepted when
 * encoding), [Double], [String], [ByteArray], [List] and [Map] with [String] keys in practice.
 * Little endian throughout. The encoder emits a back reference (0xA0+) whenever an identical
 * multi-byte encoding was emitted before, as the reference client does; the decoder keeps the
 * same object table so both directions agree. Integers are encoded in the smallest form, which
 * is what the Apple TV sends too.
 */
object Opack {
    fun encode(value: Any?): ByteArray {
        val out = ByteArrayOutputStream()
        Encoder(out).write(value)
        return out.toByteArray()
    }

    fun decode(data: ByteArray): Any? = Decoder(data).read()

    private class Encoder(private val out: ByteArrayOutputStream) {
        private val objects = ArrayList<ByteArray>()

        fun write(value: Any?) {
            val packed = pack(value)
            // Collections are never back-referenced: the decoder keeps only scalars in its table.
            if (value is List<*> || value is Map<*, *>) {
                out.write(packed)
                return
            }
            val index = objects.indexOfFirst { it.contentEquals(packed) }
            when {
                index in 0..0x20 -> out.write(0xA0 + index)
                index in 0x21..0xFF -> { out.write(0xC1); out.write(index) }
                index > 0xFF -> { out.write(0xC2); out.write(index and 0xFF); out.write(index ushr 8) }
                else -> {
                    if (packed.size > 1) objects.add(packed)
                    out.write(packed)
                }
            }
        }

        private fun pack(value: Any?): ByteArray {
            val buf = ByteArrayOutputStream()
            when (value) {
                null -> buf.write(0x04)
                is Boolean -> buf.write(if (value) 0x01 else 0x02)
                is Int -> return pack(value.toLong())
                is Long -> {
                    when {
                        value in 0 until 0x28 -> buf.write((value + 8).toInt())
                        value in 0..0xFF -> { buf.write(0x30); buf.write(value.toInt()) }
                        value in 0..0xFFFF -> { buf.write(0x31); buf.writeLe(value, 2) }
                        value in 0..0xFFFFFFFFL -> { buf.write(0x32); buf.writeLe(value, 4) }
                        else -> { buf.write(0x33); buf.writeLe(value, 8) }
                    }
                }
                is Double -> { buf.write(0x36); buf.writeLe(java.lang.Double.doubleToLongBits(value), 8) }
                is String -> {
                    val bytes = value.toByteArray(Charsets.UTF_8)
                    when {
                        bytes.size <= 0x20 -> buf.write(0x40 + bytes.size)
                        bytes.size <= 0xFF -> { buf.write(0x61); buf.write(bytes.size) }
                        bytes.size <= 0xFFFF -> { buf.write(0x62); buf.writeLe(bytes.size.toLong(), 2) }
                        else -> { buf.write(0x63); buf.writeLe(bytes.size.toLong(), 3) }
                    }
                    buf.write(bytes)
                }
                is ByteArray -> {
                    when {
                        value.size <= 0x20 -> buf.write(0x70 + value.size)
                        value.size <= 0xFF -> { buf.write(0x91); buf.write(value.size) }
                        value.size <= 0xFFFF -> { buf.write(0x92); buf.writeLe(value.size.toLong(), 2) }
                        else -> { buf.write(0x93); buf.writeLe(value.size.toLong(), 4) }
                    }
                    buf.write(value)
                }
                is List<*> -> {
                    // Nested values go through write() so they share the back-reference table.
                    val inner = Encoder(buf).also { it.objects.addAll(objects) }
                    buf.write(0xD0 + minOf(value.size, 0xF))
                    value.forEach { inner.write(it) }
                    if (value.size >= 0xF) buf.write(0x03)
                    objects.clear(); objects.addAll(inner.objects)
                }
                is Map<*, *> -> {
                    val inner = Encoder(buf).also { it.objects.addAll(objects) }
                    buf.write(0xE0 + minOf(value.size, 0xF))
                    value.forEach { (k, v) -> inner.write(k); inner.write(v) }
                    if (value.size >= 0xF) buf.write(0x03)
                    objects.clear(); objects.addAll(inner.objects)
                }
                else -> throw IllegalArgumentException("Cannot encode ${value::class}")
            }
            return buf.toByteArray()
        }

        private fun ByteArrayOutputStream.writeLe(value: Long, bytes: Int) {
            for (i in 0 until bytes) write(((value ushr (8 * i)) and 0xFF).toInt())
        }
    }

    private class Decoder(private val data: ByteArray) {
        private var pos = 0
        private val objects = ArrayList<Any?>()

        fun read(): Any? {
            val tag = next()
            var remember = true
            val value: Any? = when {
                tag == 0x01 -> true.also { remember = false }
                tag == 0x02 -> false.also { remember = false }
                tag == 0x04 -> null.also { remember = false }
                tag == 0x05 -> take(16)
                tag == 0x06 -> readLe(8)
                tag in 0x08..0x2F -> (tag - 8).toLong().also { remember = false }
                tag == 0x35 -> java.lang.Float.intBitsToFloat(readLe(4).toInt()).toDouble()
                tag == 0x36 -> java.lang.Double.longBitsToDouble(readLe(8))
                tag and 0xF0 == 0x30 -> readLe(1 shl (tag and 0xF))
                tag in 0x40..0x60 -> String(take(tag - 0x40), Charsets.UTF_8)
                tag in 0x61..0x64 -> String(take(readLe(tag and 0xF).toInt()), Charsets.UTF_8)
                tag in 0x70..0x90 -> take(tag - 0x70)
                tag in 0x91..0x94 -> take(readLe(1 shl ((tag and 0xF) - 1)).toInt())
                tag and 0xF0 == 0xD0 -> readList(tag and 0xF).also { remember = false }
                tag and 0xF0 == 0xE0 -> readMap(tag and 0xF).also { remember = false }
                tag in 0xA0..0xC0 -> objects[tag - 0xA0].also { remember = false }
                tag in 0xC1..0xC4 -> objects[readLe(tag - 0xC0).toInt()].also { remember = false }
                else -> throw IllegalArgumentException("Unknown OPACK tag 0x%02x at ${pos - 1}".format(tag))
            }
            if (remember && objects.none { same(it, value) }) objects.add(value)
            return value
        }

        private fun readList(count: Int): List<Any?> {
            val list = ArrayList<Any?>()
            if (count == 0xF) {
                while (peek() != 0x03) list.add(read())
                pos++
            } else repeat(count) { list.add(read()) }
            return list
        }

        private fun readMap(count: Int): Map<Any?, Any?> {
            val map = LinkedHashMap<Any?, Any?>()
            if (count == 0xF) {
                while (peek() != 0x03) map[read()] = read()
                pos++
            } else repeat(count) { map[read()] = read() }
            return map
        }

        private fun same(a: Any?, b: Any?): Boolean =
            if (a is ByteArray && b is ByteArray) a.contentEquals(b) else a == b

        private fun peek(): Int {
            require(pos < data.size) { "Truncated OPACK data" }
            return data[pos].toInt() and 0xFF
        }

        private fun next(): Int = peek().also { pos++ }

        private fun take(n: Int): ByteArray {
            require(pos + n <= data.size) { "Truncated OPACK data" }
            return data.copyOfRange(pos, pos + n).also { pos += n }
        }

        private fun readLe(bytes: Int): Long {
            var v = 0L
            for (i in 0 until bytes) v = v or ((next().toLong()) shl (8 * i))
            return v
        }
    }
}
