package com.example.vrsbsplayer.profile

import java.io.ByteArrayOutputStream

/**
 * Hand-written protobuf (proto2, wire format) codec for exactly one message:
 * `cardboard.DeviceParams`, as defined by Google's open-source Cardboard SDK
 * (https://github.com/googlevr/cardboard/blob/master/proto/cardboard_device.proto).
 *
 * Field numbers below are copied verbatim from that .proto file. We do not
 * invent them. Rather than pull in the full protobuf-javalite toolchain (and
 * the protoc gradle plugin) for a single 8-field message, we implement the
 * wire format directly: it's simple (varint + length-delimited fields) and
 * this keeps the QR-import path dependency-free.
 *
 * message DeviceParams {
 *   optional string vendor = 1;
 *   optional string model = 2;
 *   optional float screen_to_lens_distance = 3;
 *   optional float inter_lens_distance = 4;
 *   repeated float left_eye_field_of_view_angles = 5 [packed = true]; // left, right, bottom, top (degrees)
 *   optional float tray_to_lens_distance = 6;
 *   repeated float distortion_coefficients = 7 [packed = true];      // K1, K2, K3, ...
 *   optional VerticalAlignmentType vertical_alignment = 11 [default = BOTTOM]; // 0=BOTTOM,1=CENTER,2=TOP
 *   optional ButtonType primary_button = 12 [default = MAGNET];      // 0=NONE,1=MAGNET,2=TOUCH,3=INDIRECT_TOUCH
 * }
 *
 * All lengths in the original proto are meters; this app stores/display them
 * in millimeters and converts at the boundary (see ViewerProfile).
 */
object CardboardProto {

    data class DeviceParams(
        val vendor: String? = null,
        val model: String? = null,
        val screenToLensDistanceM: Float? = null,
        val interLensDistanceM: Float? = null,
        // [left, right, bottom, top] in degrees
        val fovAnglesDeg: FloatArray? = null,
        val trayToLensDistanceM: Float? = null,
        val distortionCoefficients: FloatArray? = null,
        val verticalAlignment: Int = 0, // 0 BOTTOM, 1 CENTER, 2 TOP
        val primaryButton: Int = 1      // 0 NONE, 1 MAGNET, 2 TOUCH, 3 INDIRECT_TOUCH
    )

    private const val WT_VARINT = 0
    private const val WT_FIXED32 = 5
    private const val WT_LEN = 2

    /** Decodes a serialized DeviceParams protobuf message. Returns null on malformed input. */
    fun decode(bytes: ByteArray): DeviceParams? {
        return try {
            var pos = 0
            var vendor: String? = null
            var model: String? = null
            var screenToLens: Float? = null
            var interLens: Float? = null
            var fov: FloatArray? = null
            var trayToLens: Float? = null
            var distortion: FloatArray? = null
            var vAlign = 0
            var button = 1

            fun readVarint(): Long {
                var result = 0L
                var shift = 0
                while (true) {
                    val b = bytes[pos].toInt() and 0xFF
                    pos++
                    result = result or ((b.toLong() and 0x7F) shl shift)
                    if (b and 0x80 == 0) break
                    shift += 7
                }
                return result
            }

            fun readFixed32(): Int {
                val v = (bytes[pos].toInt() and 0xFF) or
                        ((bytes[pos + 1].toInt() and 0xFF) shl 8) or
                        ((bytes[pos + 2].toInt() and 0xFF) shl 16) or
                        ((bytes[pos + 3].toInt() and 0xFF) shl 24)
                pos += 4
                return v
            }

            fun readPackedFloats(len: Int): FloatArray {
                val end = pos + len
                val list = ArrayList<Float>()
                while (pos < end) {
                    list.add(Float.fromBits(readFixed32()))
                }
                pos = end
                return list.toFloatArray()
            }

            while (pos < bytes.size) {
                val tag = readVarint()
                val fieldNum = (tag shr 3).toInt()
                val wireType = (tag and 0x7).toInt()
                when (wireType) {
                    WT_VARINT -> {
                        val v = readVarint()
                        when (fieldNum) {
                            11 -> vAlign = v.toInt()
                            12 -> button = v.toInt()
                        }
                    }
                    WT_FIXED32 -> {
                        val bits = readFixed32()
                        val f = Float.fromBits(bits)
                        when (fieldNum) {
                            3 -> screenToLens = f
                            4 -> interLens = f
                            6 -> trayToLens = f
                        }
                    }
                    WT_LEN -> {
                        val len = readVarint().toInt()
                        when (fieldNum) {
                            1 -> { vendor = String(bytes, pos, len, Charsets.UTF_8); pos += len }
                            2 -> { model = String(bytes, pos, len, Charsets.UTF_8); pos += len }
                            5 -> fov = readPackedFloats(len)          // advances pos itself
                            7 -> distortion = readPackedFloats(len)   // advances pos itself
                            else -> pos += len                        // unknown field: skip
                        }
                    }
                    else -> return null // unsupported wire type; bail rather than guess
                }
            }

            DeviceParams(
                vendor = vendor,
                model = model,
                screenToLensDistanceM = screenToLens,
                interLensDistanceM = interLens,
                fovAnglesDeg = fov,
                trayToLensDistanceM = trayToLens,
                distortionCoefficients = distortion,
                verticalAlignment = vAlign,
                primaryButton = button
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Encodes a DeviceParams message (used when saving/exporting a manually-entered profile). */
    fun encode(p: DeviceParams): ByteArray {
        val out = ByteArrayOutputStream()

        fun writeVarint(v: Long, stream: ByteArrayOutputStream) {
            var value = v
            while (true) {
                val b = (value and 0x7F).toInt()
                value = value ushr 7
                if (value == 0L) {
                    stream.write(b)
                    break
                } else {
                    stream.write(b or 0x80)
                }
            }
        }

        fun writeTag(field: Int, wireType: Int) = writeVarint(((field.toLong() shl 3) or wireType.toLong()), out)

        fun writeFloat(field: Int, f: Float) {
            writeTag(field, WT_FIXED32)
            val bits = f.toRawBits()
            out.write(bits and 0xFF)
            out.write((bits shr 8) and 0xFF)
            out.write((bits shr 16) and 0xFF)
            out.write((bits shr 24) and 0xFF)
        }

        fun writeString(field: Int, s: String) {
            val bytes = s.toByteArray(Charsets.UTF_8)
            writeTag(field, WT_LEN)
            writeVarint(bytes.size.toLong(), out)
            out.write(bytes)
        }

        fun writePackedFloats(field: Int, values: FloatArray) {
            writeTag(field, WT_LEN)
            writeVarint((values.size * 4).toLong(), out)
            for (f in values) {
                val bits = f.toRawBits()
                out.write(bits and 0xFF)
                out.write((bits shr 8) and 0xFF)
                out.write((bits shr 16) and 0xFF)
                out.write((bits shr 24) and 0xFF)
            }
        }

        p.vendor?.let { writeString(1, it) }
        p.model?.let { writeString(2, it) }
        p.screenToLensDistanceM?.let { writeFloat(3, it) }
        p.interLensDistanceM?.let { writeFloat(4, it) }
        p.fovAnglesDeg?.let { writePackedFloats(5, it) }
        p.trayToLensDistanceM?.let { writeFloat(6, it) }
        p.distortionCoefficients?.let { writePackedFloats(7, it) }
        writeVarint(((11L shl 3) or WT_VARINT.toLong()), out); writeVarint(p.verticalAlignment.toLong(), out)
        writeVarint(((12L shl 3) or WT_VARINT.toLong()), out); writeVarint(p.primaryButton.toLong(), out)

        return out.toByteArray()
    }
}
