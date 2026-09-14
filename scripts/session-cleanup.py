#!/usr/bin/env python3
"""Lists Hermes sessions and optionally deletes the ones this project's testing made.

Run with no arguments to just list. Pass --delete to remove sessions whose title
matches a MaterialAgent test pattern; anything else is left alone.
"""
import http.cookiejar
import json
import os
import sys
import urllib.parse
import urllib.request
import websocket  # type: ignore

# Password sign-in only: enabling authentication retired the loopback `?token=`
# path, so this walks the same three steps the app does — a sign-in cookie is
# what mints the single-use socket ticket, and without one the socket answers 403.
BASE = os.environ.get('HERMES_TEST_HTTP', 'http://127.0.0.1:19119').rstrip('/')
USER = os.environ.get('HERMES_TEST_USER', '').strip()
PASSWORD = os.environ.get('HERMES_TEST_PASSWORD', '')


def _post(path, body=None, opener=None):
    data = json.dumps(body).encode() if body is not None else b''
    req = urllib.request.Request(f'{BASE}{path}', data=data, method='POST',
                                 headers={'Content-Type': 'application/json'})
    # An OpenerDirector carries the cookie jar and exposes open(); the module-level
    # helper is urlopen(). Different names, so pick one explicitly.
    send = opener.open if opener is not None else urllib.request.urlopen
    return send(req, timeout=20)


def socket_url():
    """Signs in, mints a ticket, and returns a ready-to-open WebSocket URL."""
    if not USER or not PASSWORD:
        sys.exit('Set HERMES_TEST_USER and HERMES_TEST_PASSWORD (the live-test gate).')
    jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    with _post('/auth/password-login',
               {'provider': 'basic', 'username': USER, 'password': PASSWORD},
               opener) as r:
        if r.status != 200:
            sys.exit(f'Sign-in failed: HTTP {r.status}')
    with _post('/api/auth/ws-ticket', opener=opener) as r:
        ticket = (json.load(r) or {}).get('ticket')
    if not ticket:
        sys.exit('The gateway did not return a ticket.')
    ws_base = BASE.replace('https://', 'wss://').replace('http://', 'ws://')
    return f'{ws_base}/api/ws?ticket={urllib.parse.quote(ticket)}'


WS = socket_url()

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
    # Created by the live suite itself: the turn round trip titles its session
    # after the prompt, and the branch test forks one called "Branched".
    'Répondre pong',
    'Branched',
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
