package org.chronoscompanion.app.ble

import java.util.UUID
import java.util.zip.CRC32

/**
 * Encoder for the binary protocol ChronosESP32 expects from the phone app, reverse
 * engineered from the firmware library's own receive-side parser (ChronosESP32.cpp,
 * onWrite/dataReceived). Nothing here is documented publicly - it was derived by
 * reading how the ESP32 side decodes incoming BLE writes.
 *
 * Packet layout (before chunking):
 *   byte 0     = 0xAB (start marker)
 *   byte 1-2   = big-endian uint16, (totalPacketLength - 3)
 *   byte 3     = 0xFE
 *   byte 4     = command
 *   byte 5.. = command-specific sub-command + payload
 *
 * BLE writes are capped at 20 bytes (matches the default ATT_MTU=23, 20 bytes of
 * usable payload). A packet that doesn't fit in 20 bytes is split:
 *   - chunk 0: the first 20 bytes of the logical packet, written as-is.
 *   - chunk N (N>=1): [N-1 as a single byte, then bytes 20+(N-1)*19 .. +19) of the
 *     logical packet] - i.e. a 1-byte chunk index (0-based, counting from the second
 *     chunk) followed by up to 19 payload bytes.
 */
object ChronosProtocol {

    val SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    val CHARACTERISTIC_UUID_RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    val CHARACTERISTIC_UUID_TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

    private const val CMD_NAV_ICON = 0xEE
    private const val CMD_NAV_DATA = 0xEF
    private const val CMD_SET_TIME = 0x93
    private const val CMD_NOTIFICATION = 0x72

    private const val NAV_DATA_SUB_ACTIVE = 0x80

    // The firmware's incoming-packet buffer (ChronosData.data) is a FIXED 512-byte
    // array (DATA_SIZE in ChronosESP32.h) with no bounds checking against it - a
    // packet that doesn't fit overflows into adjacent memory and crashes the watch.
    // A long WhatsApp message (or one with several 2-byte UTF-8 accented characters)
    // can exceed that on its own, so text is capped well under the limit, leaving
    // headroom for the ~12-byte header/icon/CRC overhead and, for nav data, five other
    // fields sharing the same 512-byte packet.
    private const val MAX_NOTIFICATION_MESSAGE_BYTES = 400
    private const val MAX_NAV_FIELD_BYTES = 60

    /**
     * App icon codes the firmware recognizes (ChronosESP32.cpp's appName(int)) - only
     * the ones relevant to package name mapping below are listed, not the full table.
     */
    object AppIcon {
        const val SMS = 0x03
        const val MAIL = 0x04
        const val SKYPE = 0x08
        const val WECHAT = 0x09
        const val WHATSAPP = 0x0A
        const val GMAIL = 0x0B
        const val LINE = 0x0E
        const val TWITTER = 0x0F
        const val FACEBOOK = 0x10
        const val MESSENGER = 0x11
        const val INSTAGRAM = 0x12
        const val KAKAOTALK = 0x14
        const val VIBER = 0x16
        const val TELEGRAM = 0x18
        const val WHATSAPP_BUSINESS = 0x20
    }

    /**
     * Builds a general (non-navigation) notification packet - command 0x72. Firmware
     * layout: data[6]=icon, data[7]=state (0x02 = new notification), data[8..]=message,
     * which puts icon/state at the start of buildPacket's payload param (icon -> data[6]
     * matches payload[0] once the header (data[0-4]) + sub-command byte (data[5], unused
     * here) are accounted for). [message] should be "Sender: body" where possible - the
     * firmware's splitTitle() splits on the first ':' before index 30 if present,
     * otherwise falls back to the icon's own app name as the title and the whole string
     * as the body.
     */
    fun buildNotification(iconCode: Int, message: String): ByteArray {
        val payload = mutableListOf<Byte>()
        payload.add(iconCode.toByte())
        payload.add(0x02) // state: new notification
        payload.addAll(truncateUtf8(sanitizeForWatch(message), MAX_NOTIFICATION_MESSAGE_BYTES).toByteArray(Charsets.UTF_8).toList())
        return buildPacket(CMD_NOTIFICATION, 0x00, payload.toByteArray())
    }

    /**
     * Builds the "set time" packet (command 0x93). Firmware does:
     *   setTime(sec=data[13], min=data[12], hour=data[11], day=data[10], month=data[9],
     *            year=data[7]*256+data[8])
     * i.e. after the 5-byte header + 1 sub-command byte (both unused/0 here), the
     * payload is [0, yearHi, yearLo, month(1-12), day, hour(0-23), minute, second].
     */
    fun buildSetTime(calendar: java.util.Calendar): ByteArray {
        val year = calendar.get(java.util.Calendar.YEAR)
        val month = calendar.get(java.util.Calendar.MONTH) + 1 // Calendar is 0-based
        val day = calendar.get(java.util.Calendar.DAY_OF_MONTH)
        val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = calendar.get(java.util.Calendar.MINUTE)
        val second = calendar.get(java.util.Calendar.SECOND)

        val payload = byteArrayOf(
            0,
            ((year shr 8) and 0xFF).toByte(),
            (year and 0xFF).toByte(),
            month.toByte(),
            day.toByte(),
            hour.toByte(),
            minute.toByte(),
            second.toByte()
        )
        return buildPacket(CMD_SET_TIME, 0x00, payload)
    }

    /** Builds the raw logical packet bytes for a "navigation active, full data" message. */
    fun buildNavigationData(
        title: String,
        duration: String,
        distance: String,
        eta: String,
        directions: String,
        speed: String,
        hasIcon: Boolean,
        isNavigation: Boolean,
        iconCrc: Long
    ): ByteArray {
        val payload = mutableListOf<Byte>()
        payload.add(if (hasIcon) 1 else 0)
        payload.add(if (isNavigation) 1 else 0)
        payload.add(((iconCrc shr 24) and 0xFF).toByte())
        payload.add(((iconCrc shr 16) and 0xFF).toByte())
        payload.add(((iconCrc shr 8) and 0xFF).toByte())
        payload.add((iconCrc and 0xFF).toByte())
        for (s in listOf(title, duration, distance, eta, directions, speed)) {
            payload.addAll(truncateUtf8(sanitizeForWatch(s), MAX_NAV_FIELD_BYTES).toByteArray(Charsets.UTF_8).toList())
            payload.add(0)
        }
        return buildPacket(CMD_NAV_DATA, NAV_DATA_SUB_ACTIVE, payload.toByteArray())
    }

    /** Stops/clears navigation on the watch (matches the "0x00" sub-command path). */
    fun buildNavigationStop(): ByteArray {
        return buildPacket(CMD_NAV_DATA, 0x00, ByteArray(0))
    }

    /**
     * Builds one 107-byte logical packet per 96-byte chunk (3 chunks total for a
     * 48x48 1bpp = 288-byte icon). [chunkPos] is 0, 1 or 2. [crc] should be the same
     * value across all 3 chunks of one icon (identifies "this is the same icon").
     */
    fun buildNavigationIconChunk(chunkPos: Int, crc: Long, chunkBytes: ByteArray): ByteArray {
        require(chunkBytes.size == 96) { "icon chunk must be exactly 96 bytes" }
        val payload = mutableListOf<Byte>()
        payload.add(chunkPos.toByte())
        payload.add(((crc shr 24) and 0xFF).toByte())
        payload.add(((crc shr 16) and 0xFF).toByte())
        payload.add(((crc shr 8) and 0xFF).toByte())
        payload.add((crc and 0xFF).toByte())
        payload.addAll(chunkBytes.toList())
        // The icon path has no distinct sub-command byte before the payload (data[5] is
        // skipped over/unused by the firmware) - pass 0x00 as a filler byte to occupy it.
        return buildPacket(CMD_NAV_ICON, 0x00, payload.toByteArray())
    }

    /**
     * Packs a 48x48 ARGB [android.graphics.Bitmap]-derived grayscale threshold array
     * (48*48 booleans, row-major, true = lit pixel) into the 288-byte 1bpp buffer the
     * firmware expects: row-major, MSB-first per byte, 6 bytes/row (48/8).
     */
    fun packIconBits(litPixels: BooleanArray): ByteArray {
        require(litPixels.size == 48 * 48)
        val out = ByteArray(288)
        for (y in 0 until 48) {
            for (x in 0 until 48) {
                if (litPixels[y * 48 + x]) {
                    val byteIndex = (y * 48 + x) / 8
                    val bitPos = 7 - (x % 8)
                    out[byteIndex] = (out[byteIndex].toInt() or (1 shl bitPos)).toByte()
                }
            }
        }
        return out
    }

    /**
     * The watch's font only covers ASCII + Latin-1 Supplement (0x20-0x7F, 0xA0-0xFF) -
     * see lv_conf.h/src/fonts_es on the firmware side. Google's notification text
     * commonly uses Unicode spacing/punctuation outside that range (e.g. U+2009 thin
     * space or U+202F narrow no-break space between a number and its unit, "190 m"),
     * which renders as a tofu box on the watch. Replace anything outside that range
     * with a plain space so it degrades gracefully instead of showing a box.
     */
    fun sanitizeForWatch(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            val code = ch.code
            if (Character.isWhitespace(ch) || Character.isSpaceChar(ch)) {
                // Force ANY whitespace variant (thin space, narrow NBSP, plain NBSP,
                // etc.) to a regular space - some of these are nominally inside the
                // "safe" Latin-1 range below but still rendered as a tofu box on the
                // watch, so don't rely on the range check alone for whitespace.
                sb.append(' ')
            } else if ((code in 0x21..0x7E) || (code in 0xA1..0xFF)) {
                sb.append(ch)
            } else {
                sb.append(' ')
            }
        }
        return sb.toString().replace(Regex(" {2,}"), " ").trim()
    }

    /**
     * Truncates [s] to at most [maxBytes] bytes once UTF-8 encoded, without splitting a
     * multi-byte character in half. Needed because the firmware's incoming-packet buffer
     * (ChronosData.data, DATA_SIZE=512) has no bounds checking - an overlong message
     * (especially one inflated by 2-byte accented characters) can overflow it and crash
     * the watch.
     */
    fun truncateUtf8(s: String, maxBytes: Int): String {
        val bytes = s.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return s
        var end = maxBytes
        // Back off while we're in the middle of a multi-byte UTF-8 sequence: continuation
        // bytes have the top two bits set to "10" (0x80..0xBF).
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) {
            end--
        }
        return String(bytes, 0, end, Charsets.UTF_8)
    }

    /** Commands the watch's music/volume buttons send to the phone (Control enum in ChronosESP32.h). */
    enum class WatchCommand {
        MUSIC_PLAY, MUSIC_PAUSE, MUSIC_TOGGLE, MUSIC_PREVIOUS, MUSIC_NEXT,
        VOLUME_UP, VOLUME_DOWN, VOLUME_MUTE
    }

    /**
     * Decodes a watch->phone control packet received as a TX characteristic notification.
     * Mirrors ChronosESP32::musicControl(), which sends exactly 7 raw bytes (no chunking,
     * no 0xFE app-header - that marker is phone->watch only):
     *   [0xAB, 0x00, 0x04, 0xFF, controlEnum >> 8, 0x80, controlEnum & 0xFF]
     */
    fun decodeWatchCommand(bytes: ByteArray): WatchCommand? {
        if (bytes.size != 7) return null
        if (bytes[0] != 0xAB.toByte() || bytes[3] != 0xFF.toByte() || bytes[5] != 0x80.toByte()) return null
        val hi = bytes[4].toInt() and 0xFF
        val lo = bytes[6].toInt() and 0xFF
        return when {
            hi == 0x9D && lo == 0x00 -> WatchCommand.MUSIC_PLAY
            hi == 0x9D && lo == 0x01 -> WatchCommand.MUSIC_PAUSE
            hi == 0x9D && lo == 0x02 -> WatchCommand.MUSIC_PREVIOUS
            hi == 0x9D && lo == 0x03 -> WatchCommand.MUSIC_NEXT
            hi == 0x99 && lo == 0x00 -> WatchCommand.MUSIC_TOGGLE
            hi == 0x99 && lo == 0xA1 -> WatchCommand.VOLUME_UP
            hi == 0x99 && lo == 0xA2 -> WatchCommand.VOLUME_DOWN
            hi == 0x99 && lo == 0xA3 -> WatchCommand.VOLUME_MUTE
            else -> null
        }
    }

    fun crc32Of(data: ByteArray): Long {
        val crc = CRC32()
        crc.update(data)
        return crc.value
    }

    /** Wraps [command]/[subCommand]/[payload] into the [header] marker (0xAB for regular
     * commands, 0xEA for the extended weather sub-commands) and splits into BLE-sized chunks. */
    private fun buildPacket(command: Int, subCommand: Int, payload: ByteArray, header: Int = 0xAB): ByteArray {
        val body = ByteArray(2 + payload.size)
        body[0] = command.toByte()
        body[1] = subCommand.toByte()
        payload.copyInto(body, destinationOffset = 2)

        val totalLen = 5 + body.size
        val lenField = totalLen - 3
        val full = ByteArray(totalLen)
        full[0] = header.toByte()
        full[1] = ((lenField shr 8) and 0xFF).toByte()
        full[2] = (lenField and 0xFF).toByte()
        full[3] = 0xFE.toByte()
        body.copyInto(full, destinationOffset = 4)
        return full
    }

    // -------------------------------------------------------------------
    // Weather
    // -------------------------------------------------------------------

    /** One day's icon (0-7, see [[decodeWatchCommand]] sibling note below) and temperature. */
    data class WeatherDay(val icon: Int, val temp: Int, val high: Int, val low: Int)

    /**
     * Daily icon+temp forecast, command 0x7E under the 0xAB header. Firmware parses pairs of
     * [(icon<<4)|signBit, abs(temp)] starting at data[6], one pair per day, and derives the
     * day-of-week itself from the current date - it does not need day sent explicitly.
     * Icon values 0-7 verified by decoding the actual watch icon images (SquareLine assets
     * dy-0..dy-7): 0=partly cloudy, 1=sunny, 2=snow, 3=rain, 4=cloudy, 5=thunderstorm, 6=fog,
     * 7=sunny with light rain.
     */
    fun buildWeatherDaily(days: List<WeatherDay>): ByteArray {
        val payload = mutableListOf<Byte>()
        for (d in days) {
            val signBit = if (d.temp < 0) 1 else 0
            payload.add((((d.icon and 0x0F) shl 4) or signBit).toByte())
            payload.add((kotlin.math.abs(d.temp) and 0xFF).toByte())
        }
        return buildPacket(0x7E, 0x00, payload.toByteArray())
    }

    /** High/low per day, command 0x88. Firmware: signH=bit7 of byte0, signL=bit7 of byte1. */
    fun buildWeatherHighLow(days: List<WeatherDay>): ByteArray {
        val payload = mutableListOf<Byte>()
        for (d in days) {
            val highByte = (if (d.high < 0) 0x80 else 0) or (kotlin.math.abs(d.high) and 0x7F)
            val lowByte = (if (d.low < 0) 0x80 else 0) or (kotlin.math.abs(d.low) and 0x7F)
            payload.add(highByte.toByte())
            payload.add(lowByte.toByte())
        }
        return buildPacket(0x88, 0x00, payload.toByteArray())
    }

    /** UV index + sea-level pressure (hPa) for today only, command 0x8A. */
    fun buildWeatherUvPressure(uvIndex: Int, pressureHpa: Int): ByteArray {
        val payload = byteArrayOf(
            (uvIndex and 0xFF).toByte(),
            ((pressureHpa shr 8) and 0xFF).toByte(),
            (pressureHpa and 0xFF).toByte()
        )
        return buildPacket(0x8A, 0x00, payload)
    }

    /** City name shown on the weather screen - uses the 0xEA header, command 0x7E, sub 0x01. */
    fun buildWeatherCity(city: String): ByteArray {
        val payload = mutableListOf<Byte>(0x00)
        payload.addAll(truncateUtf8(sanitizeForWatch(city), MAX_NAV_FIELD_BYTES).toByteArray(Charsets.UTF_8).toList())
        return buildPacket(0x7E, 0x01, payload.toByteArray(), header = 0xEA)
    }

    /** Splits a logical packet (as produced by [buildPacket]) into the chunks to send over BLE, in order. */
    fun chunk(logicalPacket: ByteArray): List<ByteArray> {
        if (logicalPacket.size <= 20) {
            return listOf(logicalPacket)
        }
        val chunks = mutableListOf<ByteArray>()
        chunks.add(logicalPacket.copyOfRange(0, 20))
        var offset = 20
        var index = 0
        while (offset < logicalPacket.size) {
            val end = minOf(offset + 19, logicalPacket.size)
            val slice = logicalPacket.copyOfRange(offset, end)
            val out = ByteArray(1 + slice.size)
            out[0] = index.toByte()
            slice.copyInto(out, destinationOffset = 1)
            chunks.add(out)
            offset = end
            index++
        }
        return chunks
    }
}
