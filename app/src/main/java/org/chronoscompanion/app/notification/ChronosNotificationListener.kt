package org.chronoscompanion.app.notification

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Color
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.chronoscompanion.app.ble.ChronosBleService
import org.chronoscompanion.app.ble.ChronosProtocol

/**
 * Listens for ALL notifications (Android's API doesn't let a listener subscribe to a
 * single package) and handles two cases differently:
 *   - Google Maps: treated as turn-by-turn navigation data (see handleMapsNotification).
 *   - Everything else in [PACKAGE_TO_ICON]: forwarded as a plain watch notification
 *     (WhatsApp, SMS, Telegram, etc. - see handleGeneralNotification).
 * Anything not in that map is ignored, so e.g. silent/system notifications don't spam
 * the watch.
 */
class ChronosNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "ChronosNotifListener"
        private const val MAPS_PACKAGE = "com.google.android.apps.maps"

        // Package name -> ChronosProtocol.AppIcon code. Extend this list as needed;
        // anything not listed here is silently ignored.
        private val PACKAGE_TO_ICON = mapOf(
            "com.whatsapp" to ChronosProtocol.AppIcon.WHATSAPP,
            "com.whatsapp.w4b" to ChronosProtocol.AppIcon.WHATSAPP_BUSINESS,
            "com.google.android.apps.messaging" to ChronosProtocol.AppIcon.SMS,
            "com.android.mms" to ChronosProtocol.AppIcon.SMS,
            "com.google.android.gm" to ChronosProtocol.AppIcon.GMAIL,
            "com.android.email" to ChronosProtocol.AppIcon.MAIL,
            "org.telegram.messenger" to ChronosProtocol.AppIcon.TELEGRAM,
            "com.facebook.orca" to ChronosProtocol.AppIcon.MESSENGER,
            "com.facebook.katana" to ChronosProtocol.AppIcon.FACEBOOK,
            "com.instagram.android" to ChronosProtocol.AppIcon.INSTAGRAM,
            "com.twitter.android" to ChronosProtocol.AppIcon.TWITTER,
            "com.viber.voip" to ChronosProtocol.AppIcon.VIBER,
            "com.skype.raider" to ChronosProtocol.AppIcon.SKYPE,
            "com.kakao.talk" to ChronosProtocol.AppIcon.KAKAOTALK,
        )
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Notification listener connected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == MAPS_PACKAGE) {
            handleMapsNotification(sbn)
            return
        }
        val iconCode = PACKAGE_TO_ICON[sbn.packageName] ?: return
        handleGeneralNotification(sbn, iconCode)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName != MAPS_PACKAGE) return
        Log.i(TAG, "Maps notification removed")
        ChronosBleService.instance?.sendNavigationStop()
    }

    // -------------------------------------------------------------------
    // General app notifications (WhatsApp, SMS, etc.)
    // -------------------------------------------------------------------

    private fun handleGeneralNotification(sbn: StatusBarNotification, iconCode: Int) {
        val notification = sbn.notification

        // Skip ongoing (foreground-service-style) and group-summary notifications - these
        // aren't "a new message arrived" events and would otherwise spam the watch every
        // time e.g. a call-in-progress notification updates its timer.
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

        if (title.isEmpty() && text.isEmpty()) return

        Log.i(TAG, "General notification [${sbn.packageName}]: title='$title' text='$text'")

        val service = ChronosBleService.instance
        if (service == null) {
            Log.w(TAG, "BLE service not running, dropping notification")
            return
        }

        // "Title: body" so the firmware's splitTitle() (splits on the first ':' before
        // index 30) shows the sender/title separately from the message body.
        val message = if (title.isNotEmpty()) "$title: $text" else text
        service.sendNotification(iconCode, message)
    }

    // -------------------------------------------------------------------
    // Google Maps navigation
    // -------------------------------------------------------------------

    private fun handleMapsNotification(sbn: StatusBarNotification) {
        val notification = sbn.notification
        val extras = notification.extras

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()

        Log.i(TAG, "Maps notification: title='$title' text='$text' subText='$subText'")

        if (text.isEmpty()) {
            // Without a street/direction line this isn't real turn-by-turn data - it's a
            // transient state like "Iniciando navegación…" (which also has no subText).
            // Also sidesteps sending accented words into the watch's `title` field, whose
            // font (size 30) was intentionally kept ASCII-only to save flash - Maps' real
            // per-turn `title` values are just numbers ("0 m"), never accented text.
            return
        }

        val service = ChronosBleService.instance
        if (service == null) {
            Log.w(TAG, "BLE service not running, dropping notification")
            return
        }

        val iconBitmap = extractIconBitmap(notification)
        var hasIcon = false
        var crc = 0L

        if (iconBitmap != null) {
            val packed = bitmapTo1bpp(iconBitmap)
            crc = ChronosProtocol.crc32Of(packed)
            service.sendNavigationIcon(packed, crc)
            hasIcon = true
        }

        // title = next-turn distance (e.g. "0 m"), text = street/direction. subText is
        // one combined string "<duration> · <distance> · Llegada: <eta>" (confirmed via
        // logcat against a real device) - split it into the 3 fields the watch expects
        // separately, since it re-joins them itself as "eta\nduration distance".
        val subParts = subText.split("·").map { it.trim() }
        val duration = subParts.getOrElse(0) { "" }
        val distance = subParts.getOrElse(1) { "" }
        val eta = subParts.getOrElse(2) { "" }

        service.sendNavigationData(
            title = title,
            duration = duration,
            distance = distance,
            eta = eta,
            directions = text,
            speed = "",
            hasIcon = hasIcon,
            iconCrc = crc
        )
    }

    private fun extractIconBitmap(notification: Notification): Bitmap? {
        return try {
            val icon = notification.getLargeIcon() ?: notification.smallIcon ?: return null
            val drawable = icon.loadDrawable(this) ?: return null
            val width = drawable.intrinsicWidth.coerceAtLeast(1)
            val height = drawable.intrinsicHeight.coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(canvas)
            bitmap
        } catch (e: Exception) {
            Log.w(TAG, "Could not extract notification icon", e)
            null
        }
    }

    /**
     * Resizes [src] to 48x48 and thresholds it to black/white, matching what the watch
     * canvas expects (see ChronosProtocol.packIconBits / the firmware's setNavIconPx loop).
     */
    private fun bitmapTo1bpp(src: Bitmap): ByteArray {
        val resized = Bitmap.createScaledBitmap(src, 48, 48, true)
        val lit = BooleanArray(48 * 48)
        for (y in 0 until 48) {
            for (x in 0 until 48) {
                val pixel = resized.getPixel(x, y)
                val alpha = Color.alpha(pixel)
                val luminance = (Color.red(pixel) * 0.299 + Color.green(pixel) * 0.587 + Color.blue(pixel) * 0.114)
                lit[y * 48 + x] = alpha > 64 && luminance > 128
            }
        }
        return ChronosProtocol.packIconBits(lit)
    }
}
