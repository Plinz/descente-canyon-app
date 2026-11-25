"""ECDSA P-256 signatures of the manifest.

The app embeds the public key (base64 of the DER SubjectPublicKeyInfo, read with
X509EncodedKeySpec) and verifies the signature with SHA256withECDSA, available on every Android
version the app supports (Ed25519 is not).
"""
from __future__ import annotations

import base64
import hashlib
import os
from pathlib import Path

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec


def generate_private_key_pem() -> bytes:
    key = ec.generate_private_key(ec.SECP256R1())
    return key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    )


def write_private_key(path: Path) -> ec.EllipticCurvePrivateKey:
    """Creates a new key file readable by its owner only; never overwrites an existing key."""
    path.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "wb") as handle:
        handle.write(generate_private_key_pem())
    return load_private_key(path)


def load_private_key(path: Path) -> ec.EllipticCurvePrivateKey:
    key = serialization.load_pem_private_key(path.read_bytes(), password=None)
    if not isinstance(key, ec.EllipticCurvePrivateKey) or not isinstance(key.curve, ec.SECP256R1):
        raise ValueError(f"{path} is not an ECDSA P-256 private key")
    return key


def public_key_b64(key: ec.EllipticCurvePrivateKey) -> str:
    der = key.public_key().public_bytes(
        encoding=serialization.Encoding.DER,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    return base64.b64encode(der).decode("ascii")


def key_id(public_b64: str) -> str:
    return hashlib.sha256(base64.b64decode(public_b64)).hexdigest()[:16]


def sign(key: ec.EllipticCurvePrivateKey, payload: bytes) -> str:
    return base64.b64encode(key.sign(payload, ec.ECDSA(hashes.SHA256()))).decode("ascii")


def verify(public_b64: str, payload: bytes, signature_b64: str) -> bool:
    public_key = serialization.load_der_public_key(base64.b64decode(public_b64))
    if not isinstance(public_key, ec.EllipticCurvePublicKey):
        return False
    try:
        public_key.verify(base64.b64decode(signature_b64), payload, ec.ECDSA(hashes.SHA256()))
    except (InvalidSignature, ValueError):
        return False
    return True
