package app.getknit.knit.data.group

import androidx.room3.Entity

/**
 * One founding member's leave and rejoin clocks in one group (ADR 2026-09.v6fu): [leftAt] is the clamped `sentAt`
 * of the member's last signed `groupleave` this device applied, [rejoinedAt] that of the last frame of theirs that
 * took them back. A leave applies only when newer than [rejoinedAt], a rejoin only when newer than [leftAt], and a
 * missing row reads as both 0.
 *
 * They live beside the group, not in the `leave:` / `rejoin:` status lines that once carried them: the retention
 * sweep may drop a request thread's lines while the group lives on, and a frame custody re-served after that would
 * flip the roster on this device alone. A row goes with its group ([GroupDao.deleteClocks]): on delete, and on our
 * own leave, which drops the thread whose lines these were.
 */
@Entity(tableName = "group_member_clocks", primaryKeys = ["groupId", "memberId"])
data class GroupMemberClockEntity(
    val groupId: String,
    val memberId: String,
    val leftAt: Long = 0L,
    val rejoinedAt: Long = 0L,
)
