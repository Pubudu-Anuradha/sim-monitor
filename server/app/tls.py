"""Private CA + server certificate for the phone listener.

The CA is created once and pinned by the phones (its SHA-256 goes into the pairing QR code), so
the server certificate can be re-issued (new IP, expiry) without re-pairing phones.
"""

import hashlib
import ipaddress
from datetime import UTC, datetime, timedelta
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID


def _write_key(path: Path, key: ec.EllipticCurvePrivateKey) -> None:
    path.write_bytes(
        key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        )
    )
    path.chmod(0o600)


def _load_key(path: Path) -> ec.EllipticCurvePrivateKey:
    key = serialization.load_pem_private_key(path.read_bytes(), password=None)
    assert isinstance(key, ec.EllipticCurvePrivateKey)
    return key


class Tls:
    def __init__(self, data_dir: Path, host: str):
        self.dir = data_dir / "tls"
        self.host = host
        self.ca_cert_path = self.dir / "ca.crt"
        self.ca_key_path = self.dir / "ca.key"
        self.cert_path = self.dir / "server.crt"
        self.key_path = self.dir / "server.key"
        # server cert + CA: phones pin the CA by fingerprint only, so they need it in the handshake.
        self.chain_path = self.dir / "server-chain.crt"

    def ensure(self) -> None:
        self.dir.mkdir(parents=True, exist_ok=True)
        if not self.ca_cert_path.exists():
            self._make_ca()
        if not self.cert_path.exists() or not self._server_cert_ok():
            self._make_server_cert()
        self.chain_path.write_bytes(self.cert_path.read_bytes() + self.ca_cert_path.read_bytes())

    def ca_sha256(self) -> str:
        """SHA-256 of the CA certificate (DER), hex, as pinned by phones."""
        cert = x509.load_pem_x509_certificate(self.ca_cert_path.read_bytes())
        return hashlib.sha256(cert.public_bytes(serialization.Encoding.DER)).hexdigest()

    def _make_ca(self) -> None:
        key = ec.generate_private_key(ec.SECP256R1())
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "SIM Monitor local CA")])
        now = datetime.now(UTC)
        cert = (
            x509.CertificateBuilder()
            .subject_name(name)
            .issuer_name(name)
            .public_key(key.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(now - timedelta(days=1))
            .not_valid_after(now + timedelta(days=365 * 20))
            .add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True)
            .add_extension(
                x509.KeyUsage(
                    digital_signature=False,
                    content_commitment=False,
                    key_encipherment=False,
                    data_encipherment=False,
                    key_agreement=False,
                    key_cert_sign=True,
                    crl_sign=True,
                    encipher_only=False,
                    decipher_only=False,
                ),
                critical=True,
            )
            .add_extension(
                x509.SubjectKeyIdentifier.from_public_key(key.public_key()), critical=False
            )
            .sign(key, hashes.SHA256())
        )
        _write_key(self.ca_key_path, key)
        self.ca_cert_path.write_bytes(cert.public_bytes(serialization.Encoding.PEM))

    def _server_cert_ok(self) -> bool:
        cert = x509.load_pem_x509_certificate(self.cert_path.read_bytes())
        if cert.not_valid_after_utc < datetime.now(UTC) + timedelta(days=30):
            return False
        san = cert.extensions.get_extension_for_class(x509.SubjectAlternativeName).value
        return ipaddress.ip_address(self.host) in san.get_values_for_type(x509.IPAddress)

    def _make_server_cert(self) -> None:
        ca_key = _load_key(self.ca_key_path)
        ca_cert = x509.load_pem_x509_certificate(self.ca_cert_path.read_bytes())
        key = ec.generate_private_key(ec.SECP256R1())
        now = datetime.now(UTC)
        cert = (
            x509.CertificateBuilder()
            .subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, self.host)]))
            .issuer_name(ca_cert.subject)
            .public_key(key.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(now - timedelta(days=1))
            .not_valid_after(now + timedelta(days=825))
            .add_extension(
                x509.SubjectAlternativeName(
                    [
                        x509.IPAddress(ipaddress.ip_address(self.host)),
                        x509.IPAddress(ipaddress.ip_address("127.0.0.1")),
                        x509.DNSName("localhost"),
                    ]
                ),
                critical=False,
            )
            .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
            .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]), critical=False)
            .add_extension(
                x509.AuthorityKeyIdentifier.from_issuer_public_key(ca_key.public_key()),
                critical=False,
            )
            .sign(ca_key, hashes.SHA256())
        )
        _write_key(self.key_path, key)
        self.cert_path.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
