# Download servers

Updater reads the shared Settings.Global policy and links to Network & internet. Automatic is the default. Europe only and Canada only use one server. Only the fixed release origins are used.

A missing or unknown policy selects Automatic. If the policy cannot be read, no server is contacted.

Automatic sends HEAD for the channel metadata to both servers and starts on the faster. A connection or temporary server failure before the body moves to the other server. A package download that fails or stalls mid-way continues there from the same offset, with the exact `Content-Range` of a package of the same size.

TLS pins cover both origins (vendor/diamaneos DiamaneOSUpdaterOverlay sets them). TLS failures and redirects are refused and never move to the other server. Parsing and verification failures do not either.

Selectors are bounded, untrusted hints. Older timestamps and inconsistent identity fail. Native signed OTA verification of the whole package authorizes installation. HTTP streaming is disabled. Resumed downloads require exact ranges and bounded length. `DiamaneOSDownloadPolicyClient` (vendor/diamaneos/download-policy) supplies the public-SDK policy reader.

Run `python3 -m unittest discover -s tests -p 'test_download_checks.py'` with a host JDK. These fixtures exercise selector and range handling, not Android installation, native OTA signatures or network service behavior.
