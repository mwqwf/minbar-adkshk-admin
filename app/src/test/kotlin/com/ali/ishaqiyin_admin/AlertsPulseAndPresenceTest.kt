package com.ali.ishaqiyin_admin

import com.ali.ishaqiyin_admin.data.AdminAlertsPollWorker
import com.ali.ishaqiyin_admin.data.DashAdmin
import com.ali.ishaqiyin_admin.ui.presenceLine
import com.ali.ishaqiyin_admin.ui.relativeTime
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

/** منطق خالص بلا أندرويد: تأخير النبض التكيّفي وسطر الحضور. */
class AlertsPulseAndPresenceTest {
    private val now = 1_800_000_000_000L

    @Test
    fun pulseDelayFollowsRecency() {
        val h = TimeUnit.HOURS::toMillis
        assertEquals(TimeUnit.MINUTES.toMillis(30), AdminAlertsPollWorker.delayFor(now - h(2), now))
        assertEquals(h(3), AdminAlertsPollWorker.delayFor(now - h(30), now))
        assertEquals(h(12), AdminAlertsPollWorker.delayFor(now - h(24 * 5), now))
        // بلا نبض معروف: الأبطأ.
        assertEquals(h(12), AdminAlertsPollWorker.delayFor(0L, now))
    }

    @Test
    fun presenceLineReflectsState() {
        fun admin(role: String, lastSeen: Long, online: Boolean) = DashAdmin(
            email = "x@y.z", role = role, displayName = "", lastSeenMs = lastSeen,
            addedAtMs = 0L, sessionMigratedAtMs = 0L, removedAtMs = 0L, removedBy = "", online = online,
        )
        assertEquals("متّصل الآن", presenceLine(admin("supervisor", now, true), now))
        assertEquals("لم يدخل بعد", presenceLine(admin("supervisor", 0L, false), now))
        assertEquals("غادر", presenceLine(admin("removed", now, false), now))
        assertEquals("آخر ظهور قبل 3 ساعات", presenceLine(admin("admin", now - TimeUnit.HOURS.toMillis(3), false), now))
    }

    @Test
    fun relativeTimeBuckets() {
        assertEquals("الآن", relativeTime(now - 10_000, now))
        assertEquals("قبل دقيقتين", relativeTime(now - 2 * 60_000, now))
        assertEquals("أمس", relativeTime(now - TimeUnit.HOURS.toMillis(30), now))
        assertEquals("قبل شهرين", relativeTime(now - TimeUnit.DAYS.toMillis(65), now))
    }
}
