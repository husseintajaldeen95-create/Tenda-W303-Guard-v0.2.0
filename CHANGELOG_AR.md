## 0.2.13 — Navigation Recovery
- Restored the nested-frame fallback for router page navigation.
- Open the Tenda top-level section before navigating to its child page, keeping the left navigation aligned with the content frame.
- Retry the exact child-page link while the router menu loads; use the previous content-frame fallback if the link is not exposed.
- Apply the same parent-first navigation to DHCP scans, Wireless scans, and Static Assignment DHCP reads.
- No changes to subscriber live traffic parsing, dashboard UI, or router configuration writes.
- `.github/workflows/android.yaml` preserved unchanged.

