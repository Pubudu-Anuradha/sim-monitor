"""Server settings, from environment variables with sensible defaults."""

import os
import secrets
from dataclasses import dataclass, field
from pathlib import Path

SERVER_DIR = Path(__file__).resolve().parent.parent
REPO_DIR = SERVER_DIR.parent


@dataclass
class Settings:
    # This desktop's fixed LAN address (give it a DHCP reservation on the router). Goes into the
    # TLS certificate and the pairing QR code. Required to serve phones; see .env.example.
    public_host: str = field(default_factory=lambda: os.environ.get("SIM_MONITOR_HOST", ""))
    # Dashboard: plain HTTP, loopback only.
    dashboard_port: int = field(
        default_factory=lambda: int(os.environ.get("SIM_MONITOR_PORT", "8000"))
    )
    # Phones: WebSocket over TLS on all interfaces.
    phone_port: int = field(
        default_factory=lambda: int(os.environ.get("SIM_MONITOR_PHONE_PORT", "8443"))
    )
    data_dir: Path = field(
        default_factory=lambda: Path(os.environ.get("SIM_MONITOR_DATA", SERVER_DIR / "data"))
    )
    web_dist: Path = field(default_factory=lambda: REPO_DIR / "web" / "dist")
    low_balance: float = field(
        default_factory=lambda: float(os.environ.get("SIM_MONITOR_LOW_BALANCE", "50"))
    )
    expiry_warn_days: int = 3

    @property
    def db_path(self) -> Path:
        return self.data_dir / "sim-monitor.db"

    def token(self) -> str:
        """Shared phone token, created on first use and kept in data/token."""
        if env := os.environ.get("SIM_MONITOR_TOKEN"):
            return env
        path = self.data_dir / "token"
        if not path.exists():
            self.data_dir.mkdir(parents=True, exist_ok=True)
            path.write_text(secrets.token_urlsafe(32))
            path.chmod(0o600)
        return path.read_text().strip()


settings = Settings()
