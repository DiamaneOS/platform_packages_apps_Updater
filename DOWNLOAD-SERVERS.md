# Download servers

Updater reads the shared Settings.Global policy and links to Network & internet. Europe is the default; Canada and fallback are optional. Only the fixed release origins are used.

Missing or unknown policy selects Europe with fallback off. Authority read failures stop the request before contacting a server.

TLS pins cover both origins. Redirects and TLS failures are refused. One alternate attempt can precede body consumption after a connection or temporary server failure. Parsing and verification failures do not trigger fallback.

Selectors are bounded, untrusted hints. Older timestamps and inconsistent identity fail. Native signed OTA verification authorizes installation; HTTP streaming is disabled. Resumed downloads require exact ranges and bounded length. Settings' `DiamaneOSDownloadPolicyClient` supplies the public-SDK policy reader.

Run `python3 -m unittest discover -s tests -p 'test_download_checks.py'` with a host JDK. These fixtures exercise selector and range handling, not Android installation, native OTA signatures or network service behavior.
