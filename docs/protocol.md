# Phone ⇄ TV protocol

The TV app serves the phone page at `http://<tv-ip>:8765/` and a WebSocket at
`ws://<tv-ip>:8765/ws`. Every message is one JSON object with a `t` (type) field.

## Connecting and pairing

1. Phone opens the socket and sends `{"t":"hello","token":"<saved token or empty>"}`.
2. TV answers one of:
   - `{"t":"auth","ok":true,"caps":{...},"name":"onn TV"}` – token is valid, ready.
   - `{"t":"auth","ok":false}` – phone must pair.
3. To pair, phone sends `{"t":"pair_request"}`. TV shows a 4-digit code on the
   TV screen and answers `{"t":"pair_started","ttl":120}` (seconds the code is valid).
4. Phone sends `{"t":"pair","code":"1234"}`. TV answers
   - `{"t":"paired","token":"<hex>","caps":{...}}` – save token to localStorage key `tvr.token`; you're authenticated.
   - `{"t":"pair_failed","reason":"wrong_code"|"expired"|"locked","left":3}`.

Any command sent before authentication gets `{"t":"auth","ok":false}`.
If the TV later revokes the token it sends `{"t":"auth","ok":false}` and closes.

## Keep-alive

Phone sends `{"t":"ping"}` every 3 s; TV answers `{"t":"pong"}`. If no pong
arrives for 7 s, the phone treats the connection as dead and reconnects
(backoff 0.5 s → 1 s → 2 s → max 5 s).

## Commands

`{"t":"cmd","c":"<NAME>"}` — optional `"repeat":true` on auto-repeat presses
(lets the TV skip work if it's busy). TV replies only on error:
`{"t":"err","c":"<NAME>","msg":"..."}`.

Phase 1 names: `DPAD_UP`, `DPAD_DOWN`, `DPAD_LEFT`, `DPAD_RIGHT`, `DPAD_CENTER`,
`BACK`, `HOME`, `RECENTS`, `MENU`.

Later phases add more names (media, volume, text, apps) without changing the format.

## Capabilities

`caps` describes which backends work right now; the TV re-sends it as
`{"t":"caps","caps":{...}}` whenever it changes.

```json
{
  "accessibility": true,   // D-pad, Back, Home, Recents, text input
  "media": false,          // MediaSession: now-playing + seek
  "shizuku": false,        // any key code
  "menu": "search"         // what the MENU button does: "menu" (Shizuku) or "search"
}
```

If `accessibility` is false, the phone should grey out the D-pad and the
Back/Home/Recents buttons and show a short hint: “Turn on the Accessibility
service on the TV”.

## App updates

After authentication the TV sends its update state, and again whenever it changes:

```json
{"t":"update","update":{"phase":"ready","current":5,"latest":6,"msg":null}}
```

`phase` is one of `idle`, `checking`, `up_to_date`, `downloading`, `ready`,
`installing`, `error`. `msg` is an optional human-readable note.

Phone → TV (authenticated only): `{"t":"update_check"}` checks GitHub now;
`{"t":"update_install"}` installs a downloaded update right away.

## Phase 2: media and volume

New command names (same `{"t":"cmd","c":NAME}` format):
`PLAY_PAUSE`, `NEXT`, `PREVIOUS`, `REWIND`, `FAST_FORWARD`,
`VOLUME_UP`, `VOLUME_DOWN`, `MUTE`. `VOLUME_UP`/`VOLUME_DOWN` may be sent with
`"repeat":true` while held.

New caps fields: `"media"` (true when the TV can read the active media session:
now-playing info and 10-second seeking) and `"volume"` (true when volume works).
Playback buttons always work, even when `media` is false (they fall back to media keys).

Now playing — the TV sends this after auth and whenever it changes:

```json
{"t":"now","now":{"title":"Episode 3","subtitle":"Show name","app":"Netflix","playing":true}}
{"t":"now","now":null}
```

`subtitle` and `app` may be empty strings. `now` is null when nothing is playing
or when `media` is false.

## Phase 3: keyboard, apps, favorites

### Text input
- `{"t":"cmd","c":"TEXT","text":"hello wor"}` — makes the focused text field on
  the TV contain exactly this text (the phone sends the whole box contents on
  every change, debounced ~150 ms, so typing on the phone mirrors live).
- `{"t":"cmd","c":"TEXT_ENTER"}` — presses Enter/Search/Go in the field.
- `{"t":"cmd","c":"TEXT_CLEAR"}` — empties the field.
- If no text field is focused on the TV the reply is
  `{"t":"err","c":"TEXT","msg":"No text field is selected on the TV"}`.

### Apps
- Phone → `{"t":"apps_get"}`; TV → `{"t":"apps","apps":[{"pkg":"com.netflix.ninja","label":"Netflix","banner":true}, ...]}`
  sorted by label.
- Images: `GET /icon?pkg=<pkg>&token=<token>` → PNG. When `banner` is true it's a
  wide 16:9 TV banner (320×180); otherwise a square icon (192×192). Requests
  without a valid token get 403.
- Launch: `{"t":"cmd","c":"LAUNCH","pkg":"com.netflix.ninja"}`.

### Favorites
Stored only on the phone: localStorage `tvr.favs` = JSON array of package names,
in display order.

### New caps fields
`"text"`: text input works; `"apps"`: launching works; `"shizuku"`: Shizuku
backend ready (then `"menu"` is `"menu"`).
