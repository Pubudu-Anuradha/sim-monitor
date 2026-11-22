import hashlib
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import serialization

from app.tls import Tls


def test_chain_contains_server_then_pinned_ca(tmp_path: Path) -> None:
    tls = Tls(tmp_path, "192.0.2.10")
    tls.ensure()
    chain = x509.load_pem_x509_certificates(tls.chain_path.read_bytes())
    assert len(chain) == 2
    assert chain[0].subject.rfc4514_string() == "CN=192.0.2.10"
    der = chain[1].public_bytes(serialization.Encoding.DER)
    assert hashlib.sha256(der).hexdigest() == tls.ca_sha256()


def test_new_host_reissues_cert_but_keeps_ca(tmp_path: Path) -> None:
    a = Tls(tmp_path, "192.0.2.10")
    a.ensure()
    pin = a.ca_sha256()
    b = Tls(tmp_path, "192.0.2.20")
    b.ensure()
    assert b.ca_sha256() == pin
    cert = x509.load_pem_x509_certificate(b.cert_path.read_bytes())
    assert cert.subject.rfc4514_string() == "CN=192.0.2.20"
