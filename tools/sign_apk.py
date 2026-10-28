#!/usr/bin/env python3
"""
APK Signature Scheme v2 + v3 signer.

Why this exists: the build machine for this repository cannot reach Google's Maven or the Android
SDK, so Gradle and `apksigner` only exist inside GitHub Actions. Workflow dispatch and repository
secrets are not available to the integration that pushes commits, so the release keystore can never
be handed to the CI job. The APK is therefore built in CI (which also zipaligns it), signed here with
the same keystore that produced the certificate, and pushed back; CI then runs the real `apksigner
verify --print-certs` over the result and publishes its output, so the signature is checked by the
official tool, not by this script.

Algorithm: RSA PKCS#1 v1.5 over SHA-256 (signature algorithm id 0x0103), the mandatory algorithm for
v2. Layout follows AOSP tools/apksig (`V2SchemeSigner`, `V3SchemeSigner`,
`ApkSigningBlockUtils.computeOneMbChunkContentDigests`).

Usage:
  sign_apk.py sign   --in aligned.apk --out signed.apk --keystore k.jks --storepass ... [--min-sdk 26]
  sign_apk.py verify --apk signed.apk [--keystore k.jks --storepass ...]
"""
from __future__ import annotations

import argparse
import hashlib
import struct
import sys
from pathlib import Path

V2_BLOCK_ID = 0x7109871A
V3_BLOCK_ID = 0xF05368C0
V2_STRIPPING_PROTECTION_ID = 0xBEEFF00D
SIG_ALG_RSA_PKCS1_SHA256 = 0x0103
CHUNK_SIZE = 1024 * 1024
MAGIC = b"APK Sig Block 42"
MAX_SDK = 0x7FFFFFFF


def u32(value: int) -> bytes:
    return struct.pack("<I", value)


def u64(value: int) -> bytes:
    return struct.pack("<Q", value)


def lp(blob: bytes) -> bytes:
    return u32(len(blob)) + blob


def read_u32(buf: bytes, off: int) -> int:
    return struct.unpack_from("<I", buf, off)[0]


# --- zip structure --------------------------------------------------------------------------------

class ZipLayout:
    def __init__(self, cd_start: int, cd_size: int, eocd_start: int, eocd_end: int):
        self.cd_start = cd_start
        self.cd_size = cd_size
        self.eocd_start = eocd_start
        self.eocd_end = eocd_end


def find_zip_layout(data: bytes) -> ZipLayout:
    # EOCD is the last record; the comment length tells us exactly how far from the end it starts.
    if len(data) < 22:
        raise SystemExit("file too small to be a zip")
    for comment_len in range(0, 65536):
        off = len(data) - 22 - comment_len
        if off < 0:
            break
        if data[off:off + 4] != b"PK\x05\x06":
            continue
        if struct.unpack_from("<H", data, off + 20)[0] != comment_len:
            continue
        cd_size = read_u32(data, off + 12)
        cd_start = read_u32(data, off + 16)
        return ZipLayout(cd_start, cd_size, off, len(data))
    raise SystemExit("end of central directory not found")


# --- content digest (1 MiB chunked SHA-256) --------------------------------------------------------

def chunked_sha256(sections: list[bytes]) -> bytes:
    chunk_digests = []
    for section in sections:
        for start in range(0, len(section), CHUNK_SIZE):
            chunk = section[start:start + CHUNK_SIZE]
            h = hashlib.sha256()
            h.update(b"\xa5")
            h.update(u32(len(chunk)))
            h.update(chunk)
            chunk_digests.append(h.digest())
    final = hashlib.sha256()
    final.update(b"\x5a")
    final.update(u32(len(chunk_digests)))
    for digest in chunk_digests:
        final.update(digest)
    return final.digest()


# --- signing blocks --------------------------------------------------------------------------------

def build_signer_block(signed_data: bytes, signature: bytes, public_key_der: bytes, v3: bool,
                       min_sdk: int, max_sdk: int) -> bytes:
    # Each signature record is itself length-prefixed: lp(u32 algorithm id + lp(signature)).
    signatures = lp(u32(SIG_ALG_RSA_PKCS1_SHA256) + lp(signature))
    if v3:
        return (
            lp(signed_data)
            + u32(min_sdk)
            + u32(max_sdk)
            + lp(signatures)
            + lp(public_key_der)
        )
    return lp(signed_data) + lp(signatures) + lp(public_key_der)


def signed_data_v2(digest: bytes, cert_der: bytes) -> bytes:
    digests = lp(u32(SIG_ALG_RSA_PKCS1_SHA256) + lp(digest))
    certificates = lp(cert_der)
    # Stripping protection: this APK also carries a v3 signature (id 3).
    attributes = lp(u32(V2_STRIPPING_PROTECTION_ID) + u32(3))
    return lp(digests) + lp(certificates) + lp(attributes) + lp(b"")


def signed_data_v3(digest: bytes, cert_der: bytes, min_sdk: int, max_sdk: int) -> bytes:
    digests = lp(u32(SIG_ALG_RSA_PKCS1_SHA256) + lp(digest))
    certificates = lp(cert_der)
    # v3 signed data holds the raw attribute sequence; the length prefix belongs to the field.
    attributes = b""
    return lp(digests) + lp(certificates) + u32(min_sdk) + u32(max_sdk) + lp(attributes)


def build_signing_block(pairs: list[tuple[int, bytes]]) -> bytes:
    body = b"".join(u64(len(value) + 4) + u32(block_id) + value for block_id, value in pairs)
    size = len(body) + 8 + 16
    return u64(size) + body + u64(size) + MAGIC


def sign(aligned: bytes, key, cert_der: bytes, min_sdk: int, max_sdk: int) -> bytes:
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import padding

    layout = find_zip_layout(aligned)
    eocd = bytearray(aligned[layout.eocd_start:layout.eocd_end])
    # The verifier hashes the EOCD with its central-directory offset pointing at the signing block,
    # which is exactly where the block is inserted.
    struct.pack_into("<I", eocd, 16, layout.cd_start)
    digest = chunked_sha256([
        aligned[:layout.cd_start],
        aligned[layout.cd_start:layout.cd_start + layout.cd_size],
        bytes(eocd),
    ])
    from cryptography.hazmat.primitives import serialization

    public_key_der = key.public_key().public_bytes(
        encoding=serialization.Encoding.DER,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )

    blocks = []
    for v3 in (False, True):
        data = (signed_data_v3(digest, cert_der, min_sdk, max_sdk) if v3
                else signed_data_v2(digest, cert_der))
        signature = key.sign(data, padding.PKCS1v15(), hashes.SHA256())
        signer = build_signer_block(data, signature, public_key_der, v3, min_sdk, max_sdk)
        value = lp(lp(signer))
        blocks.append((V3_BLOCK_ID if v3 else V2_BLOCK_ID, value))

    block = build_signing_block(blocks)
    out = bytearray()
    out += aligned[:layout.cd_start]
    out += block
    out += aligned[layout.cd_start:layout.eocd_start]
    new_eocd = bytearray(eocd)
    struct.pack_into("<I", new_eocd, 16, layout.cd_start + len(block))
    out += new_eocd
    out += aligned[layout.eocd_end:]
    return bytes(out)


# --- verification (self-check; CI runs the real apksigner as the authority) ------------------------

def parse_signing_block(apk: bytes):
    layout = find_zip_layout(apk)
    end = layout.cd_start
    if apk[end - 16:end] != MAGIC:
        return layout, None, {}
    size = struct.unpack_from("<Q", apk, end - 24)[0]
    start = end - size - 8
    body = apk[start + 8:end - 24]
    pairs = {}
    offset = 0
    while offset < len(body):
        length = struct.unpack_from("<Q", body, offset)[0]
        block_id = read_u32(body, offset + 8)
        pairs[block_id] = body[offset + 12:offset + 8 + length]
        offset += 8 + length
    return layout, (start, end), pairs


def verify(apk_path: Path, key=None, cert_der=None) -> int:
    from cryptography.exceptions import InvalidSignature
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import padding
    from cryptography.hazmat.primitives.serialization import load_der_public_key

    apk = apk_path.read_bytes()
    layout, block, pairs = parse_signing_block(apk)
    if not block:
        print("no APK signing block")
        return 1
    start, end = block
    eocd = bytearray(apk[layout.eocd_start:])
    struct.pack_into("<I", eocd, 16, start)
    digest = chunked_sha256([apk[:start], apk[layout.cd_start:layout.eocd_start], bytes(eocd)])
    ok = True
    for block_id, name in ((V2_BLOCK_ID, "v2"), (V3_BLOCK_ID, "v3")):
        value = pairs.get(block_id)
        if value is None:
            print(f"{name}: missing")
            ok = False
            continue
        signers = value[4:]
        signer_len = read_u32(signers, 0)
        signer = signers[4:4 + signer_len]
        signed_len = read_u32(signer, 0)
        signed = signer[4:4 + signed_len]
        pos = 4 + signed_len
        if name == "v3":
            pos += 8
        sigs_len = read_u32(signer, pos)
        sigs = signer[pos + 4:pos + 4 + sigs_len]
        pos += 4 + sigs_len
        pub_len = read_u32(signer, pos)
        pub = signer[pos + 4:pos + 4 + pub_len]
        # first signature record: lp(u32 algorithm id + lp(signature))
        record_len = read_u32(sigs, 0)
        record = sigs[4:4 + record_len]
        alg = read_u32(record, 0)
        sig_len = read_u32(record, 4)
        sig = record[8:8 + sig_len]
        alg_ok = alg == SIG_ALG_RSA_PKCS1_SHA256
        try:
            load_der_public_key(pub).verify(sig, signed, padding.PKCS1v15(), hashes.SHA256())
            sig_ok = True
        except InvalidSignature:
            sig_ok = False
        digests_seq = signed[4:4 + read_u32(signed, 0)]
        first_pair = digests_seq[4:4 + read_u32(digests_seq, 0)]
        embedded_digest = first_pair[8:8 + read_u32(first_pair, 4)]
        digest_ok = embedded_digest == digest
        print(f"{name}: algorithm_ok={alg_ok} signature_ok={sig_ok} content_digest_ok={digest_ok} "
              f"block={start}..{end} apk_size={len(apk)}")
        ok = ok and alg_ok and sig_ok and digest_ok
    if key is not None:
        from cryptography.hazmat.primitives import serialization

        expected = key.public_key().public_bytes(
            serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
        )
        same = expected == pub
        print(f"certificate key matches keystore: {same}")
        ok = ok and same
    print("SELF-CHECK:", "OK" if ok else "FAILED")
    return 0 if ok else 1


def load_keystore(path: Path, password: str):
    from cryptography.hazmat.primitives.serialization import pkcs12

    from cryptography.hazmat.primitives import serialization

    key, cert, _ = pkcs12.load_key_and_certificates(path.read_bytes(), password.encode())
    if key is None or cert is None:
        raise SystemExit("keystore must contain a private key and a certificate")
    return key, cert.public_bytes(serialization.Encoding.DER)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    signer = sub.add_parser("sign")
    signer.add_argument("--in", dest="source", required=True)
    signer.add_argument("--out", dest="out", required=True)
    signer.add_argument("--keystore", required=True)
    signer.add_argument("--storepass", required=True)
    signer.add_argument("--min-sdk", type=int, default=26)
    signer.add_argument("--max-sdk", type=int, default=MAX_SDK)

    verifier = sub.add_parser("verify")
    verifier.add_argument("--apk", required=True)
    verifier.add_argument("--keystore")
    verifier.add_argument("--storepass")

    args = parser.parse_args(argv)
    if args.command == "sign":
        key, cert_der = load_keystore(Path(args.keystore), args.storepass)
        source = Path(args.source).read_bytes()
        signed = sign(source, key, cert_der, args.min_sdk, args.max_sdk)
        Path(args.out).write_bytes(signed)
        print(f"signed {args.source} -> {args.out} ({len(signed)} bytes, {len(signed) - len(source)} added)")
        return verify(Path(args.out), key, cert_der)
    key = cert_der = None
    if args.keystore:
        key, cert_der = load_keystore(Path(args.keystore), args.storepass)
    return verify(Path(args.apk), key, cert_der)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
