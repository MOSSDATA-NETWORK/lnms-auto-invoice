# Playwright Chromium seccomp profile

`seccomp_profile.json` is a pinned, one-rule derivative of the official Playwright `v1.59.0` Docker profile:

- Source: `https://github.com/microsoft/playwright/blob/v1.59.0/utils/docker/seccomp_profile.json`
- Upstream raw SHA-256: `cc3e61cabda6bbc1e53e54d27ba4d55a9d3be829b6dd1a596f4a7b31b1cc7849`
- Hardened repository profile SHA-256: `f922c7c9bfc1ece0b72b244e56dc85756ded002c837fb46054cfd8b275c74b6b`

The only delta is that the `chroot` syscall is allowed without Docker's outer
`CAP_SYS_CHROOT` seccomp predicate. The render worker still runs as `pwuser`, drops every Linux
capability (`CapEff=0`), sets `no-new-privileges`, and keeps Chromium's sandbox enabled. The kernel
therefore still enforces `chroot` permissions in the applicable namespace, while Chromium can use
the capability it receives only inside its newly created user namespace. Do not add
`CAP_SYS_CHROOT`, `--no-sandbox`, or `seccomp=unconfined`.

The worker image also copies Playwright Java's pinned Linux Node binary into the read-only image at
`/opt/playwright/node` and sets `PLAYWRIGHT_NODEJS_PATH` plus
`PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`. This lets the driver run while `/tmp` remains `noexec`.

Update the Playwright image, Java dependency, extracted Node path, upstream profile, recorded hashes,
and the container smoke test together.
