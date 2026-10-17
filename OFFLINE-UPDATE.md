# Offline updates

System update has **Install from storage**. The user picks a signed update package in the system file picker, usually on a microSD card or USB drive. The Updater copies it to `/data/ota_package/update.zip`, where downloads go, and from there runs the online install path: whole-file signature check, package checks, `update_engine`, reboot prompt.

## Why this design

Two ways to install a package from a card were compared. (a) The Updater hands the picked package to `update_engine`, as it does a download. (b) Recovery applies it, as with `adb sideload`. GrapheneOS removed recovery's "Apply update from SD card" entry, and `ApplyFromSdcard` is still in the code.

| | (a) Updater and update_engine (chosen) | (b) Recovery from the card |
|---|---|---|
| Signature | `RecoverySystem.verifyPackage` checks the whole file against `/system/etc/security/otacerts.zip`. `update_engine` then checks the metadata and payload signatures against the same file and every operation hash, since `ro.secure=1` makes hash checks mandatory. Builds that trust a public test key refuse, as online. | Recovery's C++ verifier checks the whole file against its copy of `otacerts.zip`, then `update_engine_sideload` checks the payload. Same key, no test-key refusal. |
| Downgrade | The Updater refuses a build that is not newer than `ro.build.date.utc`, downgrade and wipe flags, an older security patch level and the `POWERWASH` and `SPL_DOWNGRADE` headers. `update_engine` refuses an older `max_timestamp` or partition version, since downgrades need `ro.debuggable`, but a signed downgrade package raises its `max_timestamp`, so the Updater's refusal is what stops it. Verified boot rollback indexes apply on a locked bootloader either way. | Recovery accepts the same build again, signed downgrade packages and flagged SPL downgrades, and wipes data when `POWERWASH=1`. |
| A/B | `update_engine` writes the inactive slot through compressed Virtual A/B snapshots and the running slot stays intact. After the reboot `update_verifier` reads the blocks in `care_map.pb`, which the Updater copies as online, and marks the boot successful. Then the snapshot merges. A slot that fails to boot falls back to the old one. | Recovery turns snapshot compression off. When the snapshots cannot be prepared, `update_engine_sideload` deletes the running slot's dynamic partitions and writes directly, so a failed boot can have no slot to fall back to. No `care_map.pb` is written. |
| Battery | Like Check for updates, a tap installs at once. The battery and charging settings apply to automatic checks. Power loss leaves the running slot untouched. | Recovery checks the battery only for packages the system hands over, not for menu installs. |
| Storage | The copy needs free space the size of the package. `StorageManager.allocateBytes` frees caches and reserves it before copying, or the install stops with Android's "storage is full" text. Snapshot space is needed as for an online update (`update_engine` error 60). | No copy. Uncompressed snapshots need more space, with the fallback above when it is short. |
| Malicious file | The kernel parses the card's filesystem when it is mounted, for any use. Before verification only the Updater reads the file: footer, end of central directory and PKCS#7 signature, in Java, in the `updater_app` app domain. After verification it parses the zip and two small text entries, bounded to 64 KiB, UTF-8, `KEY=VALUE`, no repeated keys. `update_engine` reads only the copy and, before its own signature check, only the payload header and signature blob, as online. | The card is mounted and browsed in recovery, which runs as root with a broad policy, and the signature is parsed there. Needs the menu entry back, an `/sdcard` fstab entry and exFAT support in recovery. |
| SELinux | No change. `updater_app` reads the file picker's descriptor like any app and writes `ota_package_file`. `update_engine` reads `ota_package_file` and calls back into `updater_app`, all as for downloads. | Recovery's policy already allows it. |
| Progress and errors | Shown at once after the pick, in the Install from storage row and a notification: "Processing the update package…" with copy progress, then verifying, installing, validating and finalizing with `update_engine`'s progress, then the reboot prompt. A failure names the stage, says the selected update was not installed and the installed system is unchanged, and gives the reason. Nothing is retried. Another pick while an update runs says "An update is already being installed". | Recovery's text log. Nothing in Android. |

Passing the picked file descriptor to `update_engine` (`applyPayload(AssetFileDescriptor, ...)`) would save the copy and its space. It was not chosen. `update_engine` would need new access to removable media: `fd use` on `platform_app`, where ExternalStorageProvider runs, and `read getattr` on `vfat` and `exfat` files. The file can also change between the Updater's checks and `update_engine`'s reads, through another app with storage access or a swapped card. `update_engine` re-checks the payload, but the headers the Updater passes, `POWERWASH` among them, are trusted only through the whole-file signature. With the copy the verified bytes are the bytes installed, and removing the card can only interrupt the copy.

## Refusals

Before anything reaches `update_engine` the Updater refuses a picked package that:

- is not signed with a key in `otacerts.zip`, or arrives on a build that trusts a public test key
- is for another device (`pre-device`), another source build (`pre-build`, `pre-build-incremental`) or carries a serial number constraint
- is not an A/B package
- is not newer than the installed build, has an older security patch level, or carries `ota-downgrade`, `spl-downgrade`, `ota-wipe`, `POWERWASH` or `SPL_DOWNGRADE`
- has a malformed or repeated metadata line, no payload location, a payload outside the file, a `FILE_SIZE` that differs from it, or missing hashes or metadata size
- is empty, larger than 8 GiB, changes while it is copied, or does not fit in free storage

Downloads share the parsing and device checks and must still match the build the server named.

## Tests

Run `python3 -m unittest discover -s tests -p 'test_*.py'` with a host JDK. The package fixtures cover metadata parsing and every refusal above that does not need Android. They do not cover signatures, the file picker, the copy or `update_engine`.
