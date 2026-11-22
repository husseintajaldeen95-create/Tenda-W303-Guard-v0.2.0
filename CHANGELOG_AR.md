## 0.2.19 — Restore v0.2.17 router navigation mapping

- Restored the exact `Security > MAC Address Filter Settings` route mapping from v0.2.17: `/firewall_mac.asp`.
- Kept the v0.2.18 MAC schedule auto-zero/restore implementation unchanged.
- No other router-page route, menu label, traffic-monitoring function, DHCP/Wireless reader, or automation behavior was edited in this patch.
- Source correction only; APK build and real-router navigation still require device verification.

## 0.2.18 — MAC Schedule Auto Zero and Restore

- Kept the v0.2.15 navigation implementation unchanged.
- Added a separate MAC schedule automation panel; it does not replace or redesign existing router menus.
- Reads the ten MAC policy slots through the router's existing `curNum` selector and `onChangeNum` handler.
- Captures the original start time, MAC address, policy number, enabled state, weekday range, end time, and filter mode before any write.
- Supports only the router's `Permit Only` mode and enabled policies with a valid MAC and a non-zero start time.
- At each policy's own start time, changes only `sh` and `sm` to `00:00`; at 23:00 it restores that policy's original `sh` and `sm`.
- Keeps end time, MAC address, policy name, days, Enable checkbox, and global Filter Mode unchanged.
- Uses the router's Time Settings page as the clock source; if the clock cannot be parsed, no policy is changed.
- Uses the firmware's existing `/goform/SafeMacFilter` POST and `preSubmit`/OK handler, then reloads and verifies each changed policy.
- Persists original values and pending operations so an interrupted write can be recovered when the app reconnects.
- Adds regression tests for per-policy start times, weekday ranges, and 23:00 restoration.
- Automation requires the app to remain running and connected to the router; Android force-stop or process termination can prevent a scheduled action.

## 0.2.15 — MAC Schedule Form Audit (Read Only)
- Expanded the current-page diagnostic to report form name/id/target/enctype and input event handlers.
- Added read-only capture of the MAC schedule page's `preSubmit`, `onSelected`, `onDel`, and `init` function source.
- Captures the ten policy selector options and checkbox checked state without invoking router functions.
- Continues to redact hidden/password/auth-related field values.
- Does not submit, delete, switch policy selections, or modify router settings.
- `.github/workflows/android.yaml` is unchanged.

## 0.2.14 — MAC Schedule Page Discovery
- Added a separate shortcut for `Security > MAC Address Filter Settings` using the router's visible menu labels because its exact page path has not yet been verified.
- Added label aliases for firmware menu variants.
- Added an explicit read-only notice: no MAC policy, time, or router setting is changed in this stage.
- Existing live upload/download monitoring, DHCP/Wireless parsing, and router navigation recovery are preserved.
- `.github/workflows/android.yaml` is unchanged.

## 0.2.13 — Navigation Recovery
- Restored the nested-frame fallback for router page navigation.
- Open the Tenda top-level section before navigating to its child page, keeping the left navigation aligned with the content frame.
- Retry the exact child-page link while the router menu loads; use the previous content-frame fallback if the link is not exposed.
- Apply the same parent-first navigation to DHCP scans, Wireless scans, and Static Assignment DHCP reads.
- No changes to subscriber live traffic parsing, dashboard UI, or router configuration writes.
- `.github/workflows/android.yaml` preserved unchanged.

