"""Pseudonymous identifiers of contributors.

An app installation sends a random key (32 bytes, base64url) with its contributions only, never
with plain reads. The server keeps an HMAC of it, and of the client's network (IPv4 /24, IPv6 /48)
to limit what one network can weigh in the votes. Without the pepper, neither can be linked back to
a key or an address.
"""
from __future__ import annotations

import base64
import binascii
import hashlib
import hmac
import ipaddress
import re

INSTALL_KEY_PATTERN = re.compile(r"^[A-Za-z0-9_-]{43}$")
IPV4_PREFIX = 24
IPV6_PREFIX = 48


def _hmac(pepper: bytes, label: str, value: str) -> str:
    return hmac.new(pepper, f"{label}:{value}".encode("utf-8"), hashlib.sha256).hexdigest()


def hash_install_key(pepper: bytes, key: str | None) -> str | None:
    """Returns the HMAC of a well-formed installation key, None otherwise."""
    if not key or not INSTALL_KEY_PATTERN.match(key):
        return None
    try:
        raw = base64.urlsafe_b64decode(key + "=")
    except (binascii.Error, ValueError):
        return None
    if len(raw) != 32:
        return None
    return _hmac(pepper, "install", key)


def network_of(ip: str) -> str:
    try:
        address = ipaddress.ip_address(ip.strip())
    except ValueError:
        return "unknown"
    if isinstance(address, ipaddress.IPv6Address) and address.ipv4_mapped is not None:
        address = address.ipv4_mapped
    prefix = IPV4_PREFIX if address.version == 4 else IPV6_PREFIX
    return str(ipaddress.ip_network(f"{address}/{prefix}", strict=False))


def hash_network(pepper: bytes, ip: str) -> str:
    return _hmac(pepper, "net", network_of(ip))
