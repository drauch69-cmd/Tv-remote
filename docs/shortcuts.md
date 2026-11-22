# Controlling the TV with iOS Shortcuts

The TV app has a small web API, so any iPhone shortcut can press buttons, open
apps or run macros. Use it with Siri ("Hey Siri, pause TV"), Back Tap, Home
Screen widgets, the Action button or automations.

Your iPhone has to be on the same Wi-Fi as the TV (and any VPN off, or set to
allow local network access), like the remote page itself.

## 1. Get your address and token

Open the remote page → gear icon → **iOS Shortcuts**:

- **TV address**, e.g. `http://192.168.1.50:8765`
- **Token**: your phone's pairing key. Anyone with it can control your TV on
  your network, so don't share it. "Forget all paired phones" on the TV
  revokes it (then copy the new one after re-pairing).

**Copy example URL** gives you a ready-made play/pause link.

## 2. The simplest shortcut: one URL

1. Shortcuts app → **+** → **Add Action** → search **Get Contents of URL**.
2. Paste a URL (replace the address and token with yours):
   ```
   http://192.168.1.50:8765/api/cmd?token=YOUR_TOKEN&c=PLAY_PAUSE
   ```
3. Rename the shortcut (tap its name at the top), e.g. **Pause TV**. The name is
   what you say to Siri.

That's all. Some useful URLs (same start, change the end):

| Does | End of URL |
|---|---|
| Play / pause | `&c=PLAY_PAUSE` |
| Back 10 seconds | `&c=REWIND` |
| Home screen | `&c=HOME` |
| Mute | `&c=MUTE` |
| Open Netflix | `&c=LAUNCH&pkg=com.netflix.ninja` |
| Open YouTube | `&c=LAUNCH&pkg=com.google.android.youtube.tv` |
| Run a macro | `&c=MACRO&name=Movie%20night` |
| Type text in the focused box | `&c=TEXT&text=star%20wars` |
| Any key code (needs Shizuku) | `&c=KEY&code=172` |
| Sleep (needs Shizuku) | `&c=SLEEP` |

Spaces in names and text are written as `%20`.

Every command the phone page can send works here. The full list is in
[protocol.md](protocol.md): `DPAD_UP/DOWN/LEFT/RIGHT/CENTER`, `BACK`, `HOME`,
`RECENTS`, `MENU`, `PLAY_PAUSE`, `NEXT`, `PREVIOUS`, `REWIND`, `FAST_FORWARD`,
`VOLUME_UP`, `VOLUME_DOWN`, `MUTE`, `TEXT`, `TEXT_ENTER`, `TEXT_CLEAR`,
`LAUNCH`, `KEY`, `SETTINGS`, `SEARCH`, `SLEEP`, `POWER`, `MACRO`.

**Finding an app's package name:** it's the `pkg` the Apps tab uses. Easiest
way is to make a macro that opens the app, or look the app up on the Play Store
website (the `id=` part of its address).

## 3. Running macros

Make a macro on the phone (More tab → **Record**), then call it by name:

```
http://192.168.1.50:8765/api/cmd?token=YOUR_TOKEN&c=MACRO&name=Movie%20night
```

A shortcut that asks which macro to run:

1. **Get Contents of URL** → `http://…:8765/api/macros?token=YOUR_TOKEN`
2. **Get Dictionary Value** → key `macros`
3. **Repeat with Each** → inside: **Get Dictionary Value** key `name`
   (or simpler: **Choose from List** on the `macros` list, then **Get Dictionary
   Value** `name` of the chosen item)
4. **Get Contents of URL** →
   `http://…:8765/api/cmd?token=YOUR_TOKEN&c=MACRO&name=` + the chosen name
   (insert it as a variable; Shortcuts encodes it for you)

## 4. Optional: the tidier POST form

Instead of putting everything in the URL, set **Get Contents of URL** to:

- URL: `http://192.168.1.50:8765/api/cmd`
- Method: **POST**
- Headers: `Authorization` = `Bearer YOUR_TOKEN`
- Request Body: **JSON**, with fields such as `c` = `LAUNCH`, `pkg` = `com.netflix.ninja`

## Ideas

- **Back Tap:** iPhone Settings → Accessibility → Touch → Back Tap → Double Tap →
  your "Pause TV" shortcut.
- **Home Screen widget:** add a Shortcuts widget with your favorite few shortcuts.
- **Automation:** Shortcuts → Automation → e.g. "When I arrive home" → run
  "Movie night".
- **Siri:** "Hey Siri, Pause TV" runs the shortcut named "Pause TV".

## Troubleshooting

- `{"ok":false,"error":"Missing or wrong token"}`: copy the token again from
  the remote page's settings (it changes if you re-paired).
- "Could not connect to the server": not on the home Wi-Fi, a VPN is on, or the
  TV is off or asleep.
- Shortcut runs but nothing happens: open the remote page and check the same
  button works there; some commands need the Accessibility service or Shizuku.
