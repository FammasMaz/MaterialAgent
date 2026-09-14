#!/usr/bin/env python3
"""Lists Hermes sessions and optionally deletes the ones this project's testing made.

Run with no arguments to just list. Pass --delete to remove sessions whose title
matches a MaterialAgent test pattern; anything else is left alone.
"""
import json
import sys
import urllib.request
import websocket  # type: ignore

# The tunnel script owns the token file; the URL shape is fixed.
TOKEN = open('.hermes-test-token').read().strip()
WS = f'ws://127.0.0.1:19119/api/ws?token={TOKEN}'

# An explicit allow-list, not a fuzzy match: this deletes conversations, and
# `Probe3` or `Afficher testMATERIALAGENT_OK` may well be the user's own.
TITLES = (
    'Choisir base de données',
    'Choisir base de données et langage',
    'Choisir base de données et langage #2',
    'clarify payload probe',
    'clarify batch probe',
    'clarify qid probe',
    'payload probe 2',
)
# Branching leaves behind a 0-message stored session; those are safe to drop too.
EMPTY_BRANCH_TITLES = ('branched', 'branched #2')


def call(ws, rid, method, params, timeout=60):
    ws.send(json.dumps({'jsonrpc': '2.0', 'id': rid, 'method': method, 'params': params}))
    while True:
        msg = json.loads(ws.recv())
        if msg.get('id') == rid:
            return msg.get('result', msg)


def main():
    delete = '--delete' in sys.argv
    ws = websocket.create_connection(WS, timeout=60)
    sessions = call(ws, 1, 'session.list', {}) or {}
    rows = sessions.get('sessions') or sessions.get('items') or []
    print(f'{len(rows)} sessions')
    mine = []
    for row in rows:
        title = str(row.get('title') or '')
        created = str(row.get('started_at') or '')
        empty_branch = title in EMPTY_BRANCH_TITLES and not row.get('message_count')
        if title in TITLES or empty_branch:
            mine.append(row)
            print(f"  MATCH  {row.get('id')}  source={row.get('source')}  "
                  f"msgs={row.get('message_count')}  {title[:56]!r}")
    print(f'{len(mine)} match a test pattern')
    if not delete:
        print('(list only — pass --delete to remove them)')
        return
    for row in mine:
        sid = row.get('id')
        try:
            out = call(ws, 2, 'session.delete', {'session_id': sid})
            print(f'  deleted {sid} -> {json.dumps(out)[:80]}')
        except Exception as exc:  # noqa: BLE001
            print(f'  FAILED  {sid}: {exc}')
    ws.close()


if __name__ == '__main__':
    main()
