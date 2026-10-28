#!/usr/bin/env python3
"""Create the release signing key (PKCS#12) outside the source tree.

Run this once, on a machine you control, and keep the result somewhere you will still have it in
five years: an APK can only be updated in place by an APK signed with the same key. Losing the key
means losing the ability to update an installed app — there is no recovery path, no reset, no support
line that can help.

The script deliberately takes no password on the command line (it would land in shell history and in
the process table). It reads the password from the environment or generates a strong one and prints it
once, to the file you name.

Usage:
  python3 tools/make_keystore.py --out ~/keys/loopjet-release.p12 \
      --credentials ~/keys/loopjet-credentials.txt [--name LoopJet] [--days 10950]

Nothing here is Android-specific: it produces an RSA-4096 self-signed certificate and a PKCS#12
container, which `apksigner`, `jarsigner` and `tools/sign_apk.py` all read.
"""
from __future__ import annotations

import argparse
import datetime as dt
import os
import secrets
import string
import sys
from pathlib import Path


def main(argv: list[str]) -> int:
    try:
        from cryptography import x509
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import rsa
        from cryptography.hazmat.primitives.serialization import pkcs12
        from cryptography.x509.oid import NameOID
    except ImportError:
        print("cryptography is required: pip install cryptography", file=sys.stderr)
        return 2

    parser = argparse.ArgumentParser()
    parser.add_argument("--out", required=True, help="where to write the .p12 keystore")
    parser.add_argument("--credentials", required=True, help="where to write the password + fingerprint")
    parser.add_argument("--name", default="LoopJet")
    parser.add_argument("--alias", default="loopjet")
    parser.add_argument("--days", type=int, default=10950, help="validity (default 30 years)")
    parser.add_argument("--bits", type=int, default=4096)
    args = parser.parse_args(argv)

    out = Path(os.path.expanduser(args.out))
    creds = Path(os.path.expanduser(args.credentials))
    if out.exists():
        print(f"refusing to overwrite {out}", file=sys.stderr)
        return 1

    alphabet = string.ascii_letters + string.digits
    password = os.environ.get("LJ_NEW_PASS") or "".join(secrets.choice(alphabet) for _ in range(32))

    key = rsa.generate_private_key(public_exponent=65537, key_size=args.bits)
    subject = issuer = x509.Name(
        [
            x509.NameAttribute(NameOID.COMMON_NAME, args.name),
            x509.NameAttribute(NameOID.ORGANIZATIONAL_UNIT_NAME, "Release"),
            x509.NameAttribute(NameOID.ORGANIZATION_NAME, args.name),
            x509.NameAttribute(NameOID.LOCALITY_NAME, "Local"),
            x509.NameAttribute(NameOID.STATE_OR_PROVINCE_NAME, "Local"),
            x509.NameAttribute(NameOID.COUNTRY_NAME, "PK"),
        ]
    )
    now = dt.datetime.now(dt.timezone.utc)
    certificate = (
        x509.CertificateBuilder()
        .subject_name(subject)
        .issuer_name(issuer)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - dt.timedelta(days=1))
        .not_valid_after(now + dt.timedelta(days=args.days))
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .sign(key, hashes.SHA256())
    )

    p12 = pkcs12.serialize_key_and_certificates(
        name=args.alias.encode(),
        key=key,
        cert=certificate,
        cas=None,
        encryption_algorithm=serialization.BestAvailableEncryption(password.encode()),
    )

    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(p12)
    out.chmod(0o600)

    fingerprint = certificate.fingerprint(hashes.SHA256()).hex()
    readable = ":".join(fingerprint[i : i + 2].upper() for i in range(0, len(fingerprint), 2))
    creds.parent.mkdir(parents=True, exist_ok=True)
    creds.write_text(
        f"{args.name} release signing key\n"
        f"================================\n\n"
        f"keystore      {out}\n"
        f"format        PKCS#12\n"
        f"alias         {args.alias}\n"
        f"storepass     {password}\n"
        f"keypass       {password}\n"
        f"key           RSA {args.bits}\n"
        f"valid for     {args.days} days (expires {(now + dt.timedelta(days=args.days)).date()})\n\n"
        f"certificate SHA-256 (hex)   {fingerprint}\n"
        f"certificate SHA-256 (colon) {readable}\n\n"
        f"BACK THIS UP NOW, IN TWO PLACES THAT ARE NOT THE BUILD MACHINE.\n"
        f"An installed app can only be updated by an APK signed with this exact key. If it is lost,\n"
        f"every existing installation must be uninstalled before a new build can be installed, and\n"
        f"there is no way to recover it. Do not commit this file or the keystore to git.\n",
        encoding="utf-8",
    )
    creds.chmod(0o600)

    print(f"keystore written to {out}")
    print(f"credentials written to {creds}")
    print(f"certificate SHA-256 {fingerprint}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
