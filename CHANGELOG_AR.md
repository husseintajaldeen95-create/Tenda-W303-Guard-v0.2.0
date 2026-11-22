## 0.2.16 — Frame Navigation Sync + MAC Schedule Audit
- Added the verified Security > MAC Address Filter Settings route `/firewall_mac.asp`.
- Navigation now selects the top-level router tab and then clicks the child item in the router's left menu before verifying the content-frame path.
- Removed the direct-content navigation fallback from the verified-section flow because it could leave the left menu showing a different section from the content frame.
- Expanded the read-only MAC audit with the current policy values and the `curNum` change-handler source.
- Removed malformed duplicate workflow paths containing literal backslashes; kept `.github/workflows/android.yaml` unchanged.
- No MAC policy values are written; no router POST is sent by this release. Automatic reset/restore is still pending validation of the policy-switch and save semantics.

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

