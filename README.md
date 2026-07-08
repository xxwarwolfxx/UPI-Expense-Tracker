# UPI Expense Tracker

*A wallet you glance at.*

An Android-first, **100% offline** personal expense tracker for UPI payments. It quietly captures
every payment you make, sorts it, and shows your daily / weekly / monthly spend plus an
at-a-glance available balance — on the home screen and in home-screen widgets. No account, no
cloud, no ads, no tracking. Everything stays on your phone.

> **Sideload-only.** This app reads your payment screens and bank SMS to work, which is not
> permitted on the Google Play Store — so it is distributed only through open-source channels
> (see [Install](#install)). It is a single-user tool you install for yourself.

---

## What it does

- **Captures payments automatically.** An Accessibility Service reads the on-screen confirmation
  from your UPI app (Google Pay, PhonePe, Paytm, CRED) and your bank's payment SMS, then records
  the amount, payee, and time. Duplicates are removed by the bank reference number.
- **Shows your spend.** This-week / this-month totals, a spend chart, recent transactions, and
  automatic categories (Food, Groceries, Transport, Bills, and so on).
- **Available-balance wallet.** A manual baseline minus your captured spends; re-anchor to the
  real figure any time.
- **Home-screen widgets.** Spend and available-balance widgets, plus a monthly-budget widget.
- **Budgets.** Set a monthly cap and watch it fill on the home card and a widget.

## Privacy — nothing leaves your phone

- The **`INTERNET` permission is actively stripped** from the app, so it is *incapable* of sending
  data off the device. This is enforced in the manifest, not just a promise.
- **No cloud, no analytics, no crash reporting, no ads, no third-party tracking.** Data lives in a
  local database only.
- **Export is local and manual** — you can export a CSV and share it yourself; the app never
  uploads anything.
- The donate button simply opens a payment page in your browser; there is no payment SDK in the app.

## Permissions, and why

| Permission | Why | Optional? |
|---|---|---|
| Accessibility Service | Read the UPI app's payment-confirmation screen to capture a payment | Required for auto-capture; you can also add payments manually |
| `RECEIVE_SMS` | Receive incoming bank payment SMS (reference number, credits) — the delivery broadcast only, not your inbox | Required for SMS capture |
| `ACCESS_FINE/COARSE_LOCATION` | Pin *where* a payment happened, for the optional Insights map | Opt-in; no background location |
| `POST_NOTIFICATIONS` | Optional budget-nudge alerts | Opt-in |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Keep the capture service alive in the background | Opt-in |

## Install

- **Direct / Obtainium** — grab the signed APK from [Releases](../../releases), or add this repo in
  [Obtainium](https://github.com/ImranR98/Obtainium) for automatic updates.
- **IzzyOnDroid** *(coming soon)* — add the [IzzyOnDroid](https://android.izzysoft.de/) repository
  in your F-Droid client.
- **F-Droid** *(under review)* — will appear in the main F-Droid repository once the submission is
  merged.

After installing, enable the Accessibility capture service and grant SMS access when prompted.

## Build it yourself

Requires **JDK 21** and the Android SDK (platform 36).

```
./gradlew :app:assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

## Tech

Kotlin · Jetpack Compose (Material 3) · Room · WorkManager · AccessibilityService + SMS receiver ·
[Haze](https://github.com/chrisbanes/haze) for the glass UI. All dependencies are free and
open-source. No proprietary Google Play Services, Firebase, or networking libraries.

## Legal (one line — not legal advice)

A single-user, sideloaded app that processes *your own* SMS and notifications on *your own* device
falls under the personal-use exemption of India's DPDP Act §3(c)(i); it only reads
completed-payment artifacts and holds no RBI-regulated role.

## License

[GNU General Public License v3.0](LICENSE) (`GPL-3.0-only`).
Copyright © 2026 Goushik. You may use, study, share, and modify this software; any distributed
derivative must also remain open-source under the GPL.
