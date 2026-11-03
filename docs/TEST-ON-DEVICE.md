# FlowLink Drop — ten-minute device check

Install `dist/FlowLink-Drop-1.0.0.apk`. Two phones (or a phone and a laptop). The phone running the
app is **the host**.

| # | Do | Expect |
|---|---|---|
| 1 | Open FlowLink Drop, allow the permission prompt | Hotspot card appears with a QR, a network name, a password and an address within ~3 s |
| 2 | Settings → Reachability self-test | "this phone can reach its own page ✓" |
| 3 | Other phone: camera → scan the **first** QR | Android/iOS offers to join the network; accept |
| 4 | Host screen (still step 1) | Header shows "1 device joined" and the Next button reads "Next · someone joined" (if detection works on this ROM; if not, this is still fine) |
| 5 | Tap **Next** | Step 2 with a second QR and the address |
| 6 | Other phone: scan the second QR | The browser opens "Send to <phone name>" |
| 7 | Drop one 50 MB file | Progress on the web page and a live row on the phone; file appears in Photos/Downloads |
| 8 | Drop 20 small files at once | All 20 arrive, none duplicated, none left as `.part` |
| 9 | Turn the host's screen off, send another file | Still arrives |
| 10 | Force-stop the app | The hotspot disappears from the other phone's Wi-Fi list; no notification left behind |
| 11 | Type the address manually instead of scanning | Page opens |
| 12 | Send a file with the other phone's mobile data on | Either works, or the phone's Wi-Fi "no internet" warning is the only issue — note which |

If something fails, Settings → "Requests served" and the on-screen message are the two facts worth
reporting; they say whether the browser ever reached the phone.
