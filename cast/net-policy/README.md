# PlayBridge network policy

Pure Rust destination rules for page-controlled media. There is no resolver,
async runtime, transport or FFI in this crate. `NetworkPolicy::new` creates the
page policy; native/admin registration authority is separate and retains the
proxy's existing exact-origin, LAN-literal host trust and DNS pin semantics.

Transports call `validate_url` before resolving, then `validate_destination` on
every DNS answer immediately before use. A connected peer can be checked with
a one-element address slice. Repeat for every redirect, HLS child and subtitle.
`AddressPolicy` supplies the same hostname/address checks to connector DNS hooks.
Serialization retains the existing proxy capability-token format and cache scope.

`tests/vectors.json` is the shared contract. Each case supplies a URL, the raw
grant list, optional literal DNS answers, an expected Rust result and a note.
When answers are absent, use the URL's IP literal; never resolve fixture names.
An empty answer list denies access. Grant validation precedes the destination
check, including when the destination is public.

Run the three implementations from the repository root:

```sh
cargo test -p playbridge-net-policy --locked
(cd mobile/android && zsh -c 'source ~/.zshrc && ./gradlew :shared:testAndroidHostTest')
bash mobile/apple/tests/run-net-policy-vector-checks.sh
```

The Kotlin and Swift runners check every case. Explicit, named allow-to-deny
differences assert their existing stricter behavior and fail if that behavior
changes or a difference entry becomes stale:

| Copy | Cases | Existing stricter behavior |
| --- | --- | --- |
| Kotlin | `IDNA unicode` | `java.net.URI.host` is absent for a Unicode hostname. |
| Kotlin, Swift | `long URL` | Reject URLs longer than 8192 (characters / UTF-16 code units). |
| Swift | `private granted 100.64.0.1`, `private granted 100.127.255.254` | Forbid CGNAT even with an exact grant. |
| Swift | `transition [::8.8.8.8]` | Forbid IPv4-compatible IPv6. |
| Swift | `inert loopback literal grant`, `inert forbidden literal grant` | Reject grant lists containing forbidden IP literals, even for a public destination. |

The Rust extraction preserves the existing behavior, including its lack of a URL
length cap and its classification of NAT64/6to4 as public. Only mapped IPv4 is
unwrapped. Rust and Kotlin accept inert loopback literal grants, but those grants
never authorize page loopback access; localhost name grants are rejected outright.
Do not weaken a stricter platform check just to match these legacy behaviors.
