# Companion Link protocol notes

Working notes for the next network stage: waking a sleeping Apple TV. A sleeping Apple TV
does not listen for BLE, and it ignores Wake-on-LAN magic packets (verified 2026-11-05: a
well-formed packet for its MAC reached the wired segment and nothing happened; the Apple TV's
interface stays fully up in standby, so there is no WoL state to wake from). The iOS Remote
wakes it through the Companion Link protocol instead. Everything below is from pyatv's
protocol documentation (https://pyatv.dev/documentation/protocols/); pyatv's source is the
reference implementation, and tvOS 26.5 added a session-start step to the handshake that
broke older clients (pyatv issue #2847), so expect churn.

## Status (2026-11-05)

Implemented in `network/companion/` and verified on hardware: the phone paired with the
Apple TV 4K (AppleTV6,2) from Settings > Network, entering the PIN the TV showed, and the Wake
button turned the sleeping TV on. The reference client (pyatv 0.18.0 with srptools 0.2.0) did
the same from a laptop first, which is the quickest way to tell a protocol change from a bug
in this code. Note `_sessionStart` in pyatv 0.18.0 sends the local id under `_sid` (the older
docs say `sid`), and the client does the same.

Field note: the phone must be able to reach the TV with unicast IP, not only see it in
Bonjour (mDNS is multicast and gets through where unicast between two Wi-Fi clients does
not). On this LAN the phone could not ping the TV from one access point but could from the
living-room one; check with ping before suspecting the pairing code.

## Discovery

Service `_companion-link._tcp.local.` TXT keys: `rpMd` model (`AppleTV14,1`), `rpVr` version,
`rpFl` flags, `rpBA` Bluetooth address (rotates), `rpHN`, `rpHA`, `rpAD`, `rpHI` rotating
HomeKit/Bonjour tags. Only `rpMd`, `rpVr`, `rpFl` are stable. Apple TVs, iPhones, Macs and
HomePods all advertise it; filter on `rpMd` starting with `AppleTV`.

## Framing

`type (1 byte) | length (3 bytes, big endian) | payload`. Types: 0x03 PS_Start, 0x04 PS_Next,
0x05 PV_Start, 0x06 PV_Next, 0x07 U_OPACK, 0x08 E_OPACK, 0x09 P_OPACK, 0x10/0x11 session
start request/response, 0x12 session data. Payloads are OPACK dictionaries. During pairing the
dictionary key `_pd` holds HAP TLV8 pairing data; pair-setup frames also carry `_pwTy: 1`
(PIN), pair-verify M1 carries `_auTy: 4`.

## Pairing (HAP)

Pair-Setup is HAP SRP-6a: 3072-bit group, SHA-512, username `Pair-Setup`, password = the PIN
shown on the TV. TLV8 tags: 0 method, 1 identifier, 2 salt, 3 public key, 4 proof,
5 encrypted data, 6 state, 10 signature, 11 permissions, 27 (0x1B) flags. M5 encrypted data
is TLV8 with identifier (1), Ed25519 public key (3), signature (10) and a Companion extra,
tag 17, whose value is OPACK (`name`, `model`, `mac`, `accountID`, `altIRK`, `wifiMAC`). The
HAP spec (free non-commercial edition) gives the HKDF salts and infos for M5/M6 and for
Pair-Verify; pyatv's `pyatv/auth/hap_pairing.py` and `hap_srp.py` implement them.

Pair-Verify: X25519 ephemeral exchange, Ed25519 signatures over the public keys, 4 messages.

## Session encryption

After verify, every frame is ChaCha20-Poly1305. Keys by HKDF-SHA512 from the shared secret,
salt empty, info `ClientEncrypt-main` (outgoing) and `ServerEncrypt-main` (incoming). Nonce:
per-direction counter from 0, 12 bytes little endian. AAD: the 4-byte frame header, whose
length includes the 16-byte tag.

## Messages (E_OPACK)

Dictionary keys: `_i` id, `_t` type (1 event, 2 request, 3 response), `_x` transaction id,
`_c` content, `_sid` session id; errors `_em`, `_ec`, `_ed`. First `_systemInfo`, then
`_sessionStart` with `_c: {_srvT: "com.apple.tvremoteservices", sid: <random 32-bit>}` (the
reply's `_sid` << 32 | sid is the session id), then `TVRCSessionStart` with
`{ProtocolVersionKey: "1.2"}` (older TVs answer with an error; harmless).

Buttons: `_i: _hidC`, `_c: {_hidC: <command>, _hBtS: 1 press / 2 release}`. Commands: 1 Up,
2 Down, 3 Left, 4 Right, 5 Menu, 6 Select, 7 Home, 8 VolUp, 9 VolDown, 10 Siri,
11 Screensaver, 12 Sleep, **13 Wake**, 14 PlayPause, 15/16 channel, 17 Guide, 18/19 page.
pyatv's `turn_on` is a press/release of 13. There is no mute command: the media-control
message `_mcc` (1 Play, 2 Pause, 3/4 tracks, 5 GetVolume, 6 SetVolume with `_vol` 0.0–1.0,
7 SkipBy, 8–11 fast-forward/rewind, 12/13 captions) only sets an absolute level, so the remote
has no Mute. `_sessionStop` with the same `_srvT`/`_sid` ends the remote session.

## Remote session (2026-11-06)

`CompanionSession` keeps one verified session open while the Remote tab is on screen in the
foreground, and `RemoteViewModel` sends each button there as `_hidC` down/up (`CompanionKeyMap`
maps `RemoteControl` to the codes above). Keeping the socket: there is no documented ping, and
the client only reads while waiting for a reply, so a peer close shows up as an `IOException`
on the next send; the session then drops the link, reports the press as failed, and reconnects
with a back-off of 1, 2, 5, 10 s. A refused pair-verify stops the retries until a new pairing
is stored. The link lingers 10 s after the remote leaves the screen so a tab switch is free.
Events the TV pushes (`_t` = 1) between requests are skipped by `request()`.

Why this is the only link (2026-11-06): until 0.5.0 the app was a BLE HID keyboard. That link
is owned by the Apple TV, cannot wake it, and tvOS caches "no keyboard" for good whenever the
app's process dies under an open link; a hardware pass showed the Bluetooth link coming back
subscribed after a toggle yet swallowing every key, while the Companion session simply
reconnected. The Bluetooth implementation and its findings live in the `bluetooth-archive`
branch.

## OPACK

Little endian. 0x01 true, 0x02 false, 0x04 null, 0x03 end of endless collection.
0x08–0x2F small ints 0–39; 0x30–0x33 ints of 1/2/4/8 bytes; 0x35 float32, 0x36 float64.
0x40–0x60 strings up to 32 chars inline, 0x61–0x64 strings with 1–4 length bytes.
0x70–0x90 bytes up to 32 inline, 0x91–0x94 with 1–4 length bytes. 0xD0–0xDE arrays of
0–14, 0xDF endless; 0xE0–0xEE dicts of 0–14, 0xEF endless. 0xA0–0xC0 back references into
the table of previously seen strings/data/multi-byte values (0xC1–0xC4 with 1–4 index bytes).
0x05 UUID (16 bytes), 0x06 mach absolute time.

## Crypto on Android 9+

Ed25519, X25519, HKDF-SHA512 and ChaCha20-Poly1305 come from Bouncy Castle's lightweight API
(`org.bouncycastle.crypto`, not the JCA provider, which clashes with Android's bundled copy).
SRP-6a is hand-written in `HapSrpClient`, because HAP's M1 is the RFC 2945 form, which Bouncy
Castle's SRP6Client does not produce. Byte conventions that matter for interop, taken from
srptools 0.2.0 (which real Apple TVs accept): `k = H(N | pad(g))`, `u = H(pad(A) | pad(B))`,
A, B and S hashed as minimal big-endian integers, `x = H(salt | H("Pair-Setup:" + PIN))` with
the PIN as a 4-digit string (leading zeros kept), `K = H(S)`,
`M1 = H(H(N) xor H(g) | H("Pair-Setup") | salt | A | B | K)`, `M2 = H(A | M1 | K)`.
Pair-setup M5/M6 keys: HKDF-SHA512, 32 bytes, salt/info `Pair-Setup-Encrypt-Salt`/`-Info`
(cipher key), `Pair-Setup-Controller-Sign-Salt`/`-Info` (our signing prefix),
`Pair-Setup-Accessory-Sign-Salt`/`-Info` (the TV's, which this client verifies in M6);
nonces `PS-Msg05`/`PS-Msg06`, left-padded to 12 bytes, no AAD. Pair-verify:
`Pair-Verify-Encrypt-Salt`/`-Info`, nonces `PV-Msg02`/`PV-Msg03`. Session: salt empty,
`ClientEncrypt-main`/`ServerEncrypt-main`, 12-byte little-endian counter nonces, header as AAD.
