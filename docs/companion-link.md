# Companion Link protocol notes

Working notes for the next network stage: waking a sleeping Apple TV. A sleeping Apple TV
does not listen for BLE, and it ignores Wake-on-LAN magic packets (verified 2026-11-05: a
well-formed packet for its MAC reached the wired segment and nothing happened; the Apple TV's
interface stays fully up in standby, so there is no WoL state to wake from). The iOS Remote
wakes it through the Companion Link protocol instead. Everything below is from pyatv's
protocol documentation (https://pyatv.dev/documentation/protocols/); pyatv's source is the
reference implementation, and tvOS 26.5 added a session-start step to the handshake that
broke older clients (pyatv issue #2847), so expect churn.

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
pyatv's `turn_on` is a press/release of 13.

Power state: `FetchAttentionState` returns `{state: 1 asleep, 2 screensaver, 3 awake}`;
subscribe with `_interest` `_regEvents: ["SystemStatus"]`.

## OPACK

Little endian. 0x01 true, 0x02 false, 0x04 null, 0x03 end of endless collection.
0x08–0x2F small ints 0–39; 0x30–0x33 ints of 1/2/4/8 bytes; 0x35 float32, 0x36 float64.
0x40–0x60 strings up to 32 chars inline, 0x61–0x64 strings with 1–4 length bytes.
0x70–0x90 bytes up to 32 inline, 0x91–0x94 with 1–4 length bytes. 0xD0–0xDE arrays of
0–14, 0xDF endless; 0xE0–0xEE dicts of 0–14, 0xEF endless. 0xA0–0xC0 back references into
the table of previously seen strings/data/multi-byte values (0xC1–0xC4 with 1–4 index bytes).
0x05 UUID (16 bytes), 0x06 mach absolute time.

## Crypto on Android 9+

Ed25519, X25519, HKDF-SHA512, ChaCha20-Poly1305 and SRP-6a with SHA-512 are not all in the
platform JCA on API 28, so plan on Bouncy Castle's lightweight API (`org.bouncycastle.crypto`,
not the JCA provider, which clashes with Android's bundled copy) and a hand-written SRP client
(HAP's M1 is the RFC 2945 form, which Bouncy Castle's SRP6Client does not produce).
