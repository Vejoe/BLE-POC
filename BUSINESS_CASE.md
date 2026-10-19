# Phone-Authorized Web Login — BLE Proof of Concept (Management Overview)

> One-line summary: This project validates a **passwordless login flow in which the phone authorizes web login**. The user opens a login page in a desktop browser, the page discovers a nearby phone over Bluetooth (BLE) and communicates with it, and the phone confirms to complete the authorization. This prototype verifies the feasibility and boundaries of this "phone ⇄ web" proximity authorization path at minimal cost.

---

## 1. Scenario

**User flow (target experience)**

1. The user opens a login page in a desktop browser.
2. The page automatically scans for a nearby phone via Web Bluetooth (no camera, no QR code scanning).
3. The user's phone (with an already signed-in app) appears as a Bluetooth advertisement, acting as the **identity credential**.
4. After the user taps to authorize / confirm, the phone and the web page exchange an authorization message to complete login.

In essence, this is a **proximity passwordless login prototype where the phone is the identity credential** — similar in experience to "scan-to-login", but using Bluetooth proximity instead of a QR code, with the **browser proactively discovering the device** rather than requiring the user to aim a camera.

---

## 2. Needs Addressed (Business Value)

### 2.1 Passwordless login experience
- Users **no longer need to remember or type credentials**; the signed-in phone completes the authorization, lowering the login barrier and reducing password-reset costs.

### 2.2 Proximity anti-phishing (physical presence)
- Authorization requires the phone to be **near the computer**, naturally limiting remote phishing / account takeover and adding a "device + physical location" trust factor to login.

### 2.3 No camera / QR code required
- Compared to traditional scan-to-login, the **browser proactively discovers and connects to the phone**, so users don't need to aim the camera at a screen — smoother in low light and multi-tab scenarios.

### 2.4 Phone as identity credential (device-binding potential)
- Using an already signed-in phone as the login entry point naturally extends to **device binding** and **multi-factor authentication (MFA)**.

### 2.5 Cross-endpoint path is proven
- The native iOS app and the Web Bluetooth page can communicate with each other, validating the core **"web page ⇄ phone app" proximity authorization** path.

### 2.6 Reduced technical uncertainty (pre-research value)
- As a POC, its core value is to **surface feasibility and pitfalls early** — browser compatibility, system permissions, connection stability, security boundaries — before committing to a full project.

---

## 3. Current Capabilities (How They Support Login)

| Capability | Role in the login scenario |
| --- | --- |
| Phone-side advertising (Peripheral) | The phone acts as the "authenticator" and advertises itself for discovery |
| Web-side scan / connect (Central) | The login page discovers and connects to the nearby phone |
| Bidirectional messaging | Carries the authorization confirmation / challenge-response exchange |
| Pair code | Identifies which phone this is (an identifier only, not security) |
| Log panel | Debugs each hop of the authorization flow |

---

## 4. Limitations

> These are objective constraints at the **current POC stage**. For the login scenario, **item 4.1 (security) is the most critical and requires the most attention**.

### 4.1 Security: the current prototype is not production-ready for login ⚠️
- Data is currently transmitted in **plaintext** — no encryption, no signing, no anti-replay mechanism — and is vulnerable to eavesdropping, tampering, and replay.
- The "pair code" is **only a device identifier, not an authentication factor**.
- **"A phone is nearby" over Bluetooth does not equal "this is the right person"** — it only proves that a signed-in phone is nearby; it cannot replace real identity verification.
- Therefore, this prototype is currently **suitable only for demonstrating the interaction flow**. A real login product requires a full security layer (see "Recommendations").

### 4.2 Limited browser compatibility

Web Bluetooth is currently supported only by **Chromium-based browsers**. Support by platform:

| Browser | macOS (MacBook) | Windows | iOS / iPadOS | Android |
| --- | --- | --- | --- | --- |
| Google Chrome | ✅ | ✅ | ❌ | ✅ |
| Microsoft Edge | ✅ | ✅ | ❌ | ✅ |
| Opera | ✅ | ✅ | ❌ | ✅ |
| Brave | ✅ | ✅ | ❌ | ✅ |
| Arc | ✅ | — | ❌ | — |
| Apple Safari | ❌ | — | ❌ | — |
| Mozilla Firefox | ❌ | ❌ | ❌ | ❌ |
| Samsung Internet | — | — | — | ✅ |

✅ = Web Bluetooth supported · ❌ = not supported · — = browser not available on that OS

Key points:

- **Safari and Firefox do not support Web Bluetooth** on any platform — this is a hard limitation of the web-based flow.
- **On iOS / iPadOS, no browser supports Web Bluetooth** (all iOS browsers — including Chrome, Edge, and Firefox — use the WebKit engine, which lacks it). This is why the phone side uses the native iOS app rather than a mobile web page.
- **On Android, Chromium-based browsers support Web Bluetooth** (Chrome, Edge, Opera, Brave, Samsung Internet); Firefox for Android does not.
- **Arc** is macOS-only and Chromium-based, so it works on MacBook.
- The page must be served over `https://` or `localhost`.
- Each connection **requires a user gesture** (a button click) — browsers will not auto-open the device picker.
- This project currently has no native Android app; Android users would rely on the browser side only.

### 4.3 Range and connection stability
- BLE has an effective range of roughly **10–50 meters**, subject to obstruction and electromagnetic interference; signal fluctuation can cause unstable connections and require retries.

### 4.4 iOS system-level restrictions
- When the app **goes to the background**, Bluetooth advertising is restricted, which may affect the "web page discovers phone" experience.
- Bluetooth permission must be granted on first use; if denied, communication is not possible.

### 4.5 Limited platform coverage
- The native app currently supports **iOS only**; Android users have only the browser side — there is no native Android app.

### 4.6 No integration with a real identity system
- There is no server-side login integration (e.g., OIDC / OAuth / WebAuthn / challenge-response signing); only the "web ↔ phone" proximity segment is validated.

### 4.7 Engineering & signing constraints (current environment)
- Signed with a free Apple Developer account, with a **~7-day signing validity**; it expires and must be reinstalled, and cannot be published to the App Store.

### 4.8 Robustness not yet covered
- Not implemented: auto-reconnect, concurrent multi-device support, anti-replay, timeouts, error retries, and other production-grade capabilities.

---

## 5. Conclusion and Recommendations

**Conclusion**: This project successfully validated the proximity interaction path for **phone-authorized web login** — the web page can discover and connect to the phone and exchange messages in both directions. **The interaction and technical path are feasible**, but **security and identity verification are still missing**, so it cannot yet be used as a production login solution.

**Recommended next steps** (if advancing to a production login product):

1. **Add the security layer (top priority)**: introduce an encrypted channel, a server-issued **one-time challenge (nonce)**, device signing, and anti-replay to upgrade the "proximity connection" into a "trusted authorization".
2. **Integrate a real identity system**: combine with OIDC / OAuth / WebAuthn so Bluetooth handles "proximity confirmation" while identity issuance is done by standard protocols.
3. **Decide the browser strategy**: accept "Chrome / Edge only", or evaluate replacing the web side with an app (or another approach) to cover Safari.
4. **Harden engineering**: auto-reconnect, timeouts, concurrent multi-device support, and audit logging.
5. **Obtain an official developer account** to resolve signing validity and App Store distribution.


