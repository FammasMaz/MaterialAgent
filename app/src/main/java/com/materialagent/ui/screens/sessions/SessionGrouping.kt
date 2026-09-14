package com.materialagent.ui.screens.sessions

import com.materialagent.core.model.SessionSummary

/**
 * One entry of the grouped sessions list: either a group's header or a session.
 *
 * The list is built by [groupSessions] rather than inside a composable so the
 * ordering and collapse rules are ordinary functions that can be tested without
 * a device.
 */
sealed interface SessionListItem {
    /** Unique across the list, so LazyColumn can keep identity through a refresh. */
    val key: String

    data class GroupHeader(
        val groupKey: String,
        val label: String,
        val runCount: Int,
        val isCron: Boolean,
        val collapsed: Boolean,
    ) : SessionListItem {
        override val key: String get() = "header:$groupKey"
    }

    data class Row(val session: SessionSummary) : SessionListItem {
        override val key: String get() = session.id
    }
}

/**
 * Folds sessions into sections by [SessionSummary.groupKey].
 *
 * Ordering is by each group's most recent run, and runs inside a group stay
 * newest-first, so the freshest activity is always at the top. Non-cron groups
 * are only headed when they hold more than one session — a lone Telegram or
 * in-app session reads better as a plain row than under a header for one item —
 * while a cron group is always headed, because even a single scheduled run is
 * marked as such in the current list.
 *
 * A group that is [collapsed] keeps its header and drops its rows; a lone
 * non-cron session has no header to click, so it is never hidden.
 */
internal fun groupSessions(
    sessions: List<SessionSummary>,
    collapsed: Set<String> = emptySet(),
): List<SessionListItem> {
    val ordered = sessions.sortedByDescending { it.startedAt }
    return ordered
        .groupBy { it.groupKey }
        .entries
        .sortedByDescending { it.value.first().startedAt }
        .flatMap { (key, runs) ->
            val isCron = runs.first().cronJobId != null
            val header: List<SessionListItem> = if (isCron || runs.size > 1) {
                listOf(
                    SessionListItem.GroupHeader(
                        groupKey = key,
                        label = runs.first().groupLabel,
                        runCount = runs.size,
                        isCron = isCron,
                        collapsed = key in collapsed,
                    ),
                )
            } else {
                emptyList()
            }
            if (header.isEmpty() || key !in collapsed) {
                header + runs.map { SessionListItem.Row(it) }
            } else {
                header
            }
        }
}
