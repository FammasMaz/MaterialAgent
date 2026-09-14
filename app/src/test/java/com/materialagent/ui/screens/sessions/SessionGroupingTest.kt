package com.materialagent.ui.screens.sessions

import com.materialagent.core.model.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The grouping rules, tested off-device.
 *
 * Cron runs are the whole point: one job fires on a schedule and leaves a trail
 * of near-identical rows. What is easy to get wrong quietly — and cheap to check
 * here — is the ordering across groups, the ordering inside a group, and the
 * fact that collapsing one group must not swallow another's rows.
 */
class SessionGroupingTest {

    private fun session(
        id: String,
        title: String,
        startedAt: Double,
        source: String,
    ) = SessionSummary(
        id = id,
        title = title,
        preview = "",
        startedAt = startedAt,
        messageCount = 1,
        source = source,
    )

    /** Two runs of one job, one of another, two mobile sessions, one Telegram. */
    private val mixed = listOf(
        session("sess_m2", "Refactor the parser", 50.0, "mobile"),
        session("cron_a_20250101_080000", "Nightly build · Jan 1", 100.0, "cron"),
        session("sess_tg", "Deploy check", 150.0, "telegram"),
        session("cron_b_20250101_120000", "Inbox sweep · Jan 1", 200.0, "cron"),
        session("cron_a_20250102_080000", "Nightly build · Jan 2", 300.0, "cron"),
        session("sess_m1", "Draft the changelog", 400.0, "mobile"),
    )

    @Test
    fun groupsAreOrderedByTheirMostRecentRun() {
        val headers = groupSessions(mixed).filterIsInstance<SessionListItem.GroupHeader>()

        // Mobile's newest (400) beats the nightly job's newest (300), which beats
        // the inbox sweep (200) and the lone Telegram session (150).
        assertEquals(
            listOf("source:mobile", "cron:a", "cron:b"),
            headers.map { it.groupKey },
        )
    }

    @Test
    fun runsStayTogetherAndNewestFirst() {
        val items = groupSessions(mixed)

        val nightly = items
            .dropWhile { (it as? SessionListItem.GroupHeader)?.groupKey != "cron:a" }
            .drop(1)
            .takeWhile { it is SessionListItem.Row }
            .map { (it as SessionListItem.Row).session.id }

        assertEquals(
            listOf("cron_a_20250102_080000", "cron_a_20250101_080000"),
            nightly,
        )
    }

    @Test
    fun collapsingOneGroupHidesExactlyItsRows() {
        val items = groupSessions(mixed, collapsed = setOf("cron:a"))

        val visibleIds = items.filterIsInstance<SessionListItem.Row>().map { it.session.id }
        assertFalse("cron:a ran twice and both rows are folded away", "cron_a_20250102_080000" in visibleIds)
        assertFalse("cron:a ran twice and both rows are folded away", "cron_a_20250101_080000" in visibleIds)
        assertTrue("another cron group is untouched", "cron_b_20250101_120000" in visibleIds)
        assertTrue("plain sessions are untouched", "sess_m1" in visibleIds)
        assertTrue("plain sessions are untouched", "sess_tg" in visibleIds)

        val header = items.filterIsInstance<SessionListItem.GroupHeader>()
            .first { it.groupKey == "cron:a" }
        assertTrue("the collapsed group keeps its header", header.collapsed)
        assertEquals("the header still counts every run", 2, header.runCount)
        assertEquals("Nightly build", header.label)
    }

    @Test
    fun aLoneNonCronSessionGetsNoHeaderAndCannotBeHidden() {
        val headers = groupSessions(mixed).filterIsInstance<SessionListItem.GroupHeader>()
        assertTrue("source:telegram" !in headers.map { it.groupKey })

        // Even a stale collapse entry for a single non-cron group must not hide
        // the only row it has — there is no header left to click it back open.
        val items = groupSessions(mixed, collapsed = setOf("source:telegram"))
        assertTrue(items.any { it is SessionListItem.Row && it.session.id == "sess_tg" })
    }

    @Test
    fun aSourceGroupOfSeveralSessionsIsHeadedToo() {
        val header = groupSessions(mixed)
            .filterIsInstance<SessionListItem.GroupHeader>()
            .first { it.groupKey == "source:mobile" }

        assertFalse(header.isCron)
        assertEquals("This app", header.label)
        assertEquals(2, header.runCount)
    }

    @Test
    fun aSingleCronRunStillGetsAHeader() {
        val header = groupSessions(mixed)
            .filterIsInstance<SessionListItem.GroupHeader>()
            .first { it.groupKey == "cron:b" }

        assertTrue(header.isCron)
        assertEquals(1, header.runCount)
        assertEquals("Inbox sweep", header.label)
    }
}
