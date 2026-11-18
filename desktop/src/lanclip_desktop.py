#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
LanClip Share Desktop (macOS & Linux)
100% compatível com o protocolo do aplicativo Android LanClip Share:
- Descoberta automática via UDP Broadcast na porta 45454 (tipo LANCLIP_BEACON)
- Servidor e Cliente TCP na porta 45455 (framing: 4 bytes Big-Endian length + JSON TransferHeader + stream binário)
- Integração nativa com a área de transferência do macOS (pbcopy/pbpaste) e Linux (wl-copy/xclip/xsel/Tk)
- Interface gráfica Desktop moderna (Dark Mode Material 3 idêntico ao app Android)
"""

import cgi
import html
import http.server
import json
import mimetypes
import os
import platform
import shutil
import socket
import struct
import subprocess
import sys
import threading
import time
import urllib.parse
import uuid
import webbrowser
from pathlib import Path

DEFAULT_TCP_PORT = 45455
DEFAULT_UDP_DISCOVERY_PORT = 45454
UI_HTTP_PORT = 45456


def get_os_brand() -> str:
    sys_name = platform.system().lower()
    if "darwin" in sys_name:
        return "macOS"
    if "linux" in sys_name:
        return "Linux"
    if "windows" in sys_name:
        return "Windows"
    return platform.system() or "Desktop"


def get_default_device_name() -> str:
    host = socket.gethostname().split(".")[0].strip()
    brand = get_os_brand()
    if not host or host.lower() == "localhost":
        return f"{brand} Desktop"
    return host


def get_local_ipv4() -> str:
    # Método 1: rota de saída UDP (não envia pacotes reais, apenas consulta tabela de roteamento local)
    for target in [("192.168.1.1", 80), ("10.0.0.1", 80), ("8.8.8.8", 80)]:
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.settimeout(0.2)
            s.connect(target)
            ip = s.getsockname()[0]
            s.close()
            if ip and not ip.startswith("127.") and not ip.startswith("169.254."):
                return ip
        except Exception:
            pass

    # Método 2: ip route / ifconfig
    try:
        if platform.system().lower() == "darwin":
            for iface in ["en0", "en1", "en2"]:
                res = subprocess.run(
                    ["ipconfig", "getifaddr", iface],
                    capture_output=True, text=True, timeout=1.0
                )
                ip = res.stdout.strip()
                if ip and not ip.startswith("127."):
                    return ip
        else:
            res = subprocess.run(
                ["hostname", "-I"],
                capture_output=True, text=True, timeout=1.0
            )
            for token in res.stdout.split():
                if "." in token and not token.startswith("127.") and not token.startswith("169.254."):
                    return token.strip()
    except Exception:
        pass

    return "127.0.0.1"


def get_subnet_broadcast_addresses(local_ip: str) -> list:
    addrs = {"255.255.255.255"}
    parts = local_ip.split(".")
    if len(parts) == 4 and not local_ip.startswith("127."):
        addrs.add(f"{parts[0]}.{parts[1]}.{parts[2]}.255")
    return list(addrs)


def classify_content_type(file_name: str, mime_type: str) -> str:
    lower_name = file_name.lower()
    lower_mime = (mime_type or "").lower()
    if lower_name.endswith(".apk") or lower_mime == "application/vnd.android.package-archive":
        return "APK"
    if lower_mime.startswith("image/") or lower_name.endswith((".jpg", ".jpeg", ".png", ".webp", ".gif")):
        return "IMAGE"
    if lower_name.endswith((".xlsx", ".xls", ".csv", ".ods")) or any(
        k in lower_mime for k in ("spreadsheet", "excel", "csv")
    ):
        return "SPREADSHEET"
    return "FILE"


def format_bytes(num_bytes: int) -> str:
    if num_bytes < 1024:
        return f"{num_bytes} B"
    kb = num_bytes / 1024.0
    if kb < 1024:
        return f"{kb:.1f} KB"
    mb = kb / 1024.0
    if mb < 1024:
        return f"{mb:.2f} MB"
    gb = mb / 1024.0
    return f"{gb:.2f} GB"


class SystemClipboard:
    """Leitura e escrita nativa na área de transferência do macOS e Linux."""

    @staticmethod
    def read() -> str:
        sys_name = platform.system().lower()
        try:
            if "darwin" in sys_name:
                res = subprocess.run(["pbpaste"], capture_output=True, text=True, timeout=1.5)
                if res.returncode == 0:
                    return res.stdout
            else:
                if os.environ.get("WAYLAND_DISPLAY") and shutil.which("wl-paste"):
                    res = subprocess.run(
                        ["wl-paste", "--no-newline"],
                        capture_output=True, text=True, timeout=1.5
                    )
                    if res.returncode == 0:
                        return res.stdout
                if shutil.which("xclip"):
                    res = subprocess.run(
                        ["xclip", "-selection", "clipboard", "-o"],
                        capture_output=True, text=True, timeout=1.5
                    )
                    if res.returncode == 0:
                        return res.stdout
                if shutil.which("xsel"):
                    res = subprocess.run(
                        ["xsel", "--clipboard", "--output"],
                        capture_output=True, text=True, timeout=1.5
                    )
                    if res.returncode == 0:
                        return res.stdout
        except Exception:
            pass
        return ""

    @staticmethod
    def write(text: str) -> bool:
        if text is None:
            return False
        sys_name = platform.system().lower()
        try:
            if "darwin" in sys_name:
                p = subprocess.Popen(["pbcopy"], stdin=subprocess.PIPE)
                p.communicate(input=text.encode("utf-8"), timeout=1.5)
                return p.returncode == 0
            else:
                if os.environ.get("WAYLAND_DISPLAY") and shutil.which("wl-copy"):
                    p = subprocess.Popen(["wl-copy"], stdin=subprocess.PIPE)
                    p.communicate(input=text.encode("utf-8"), timeout=1.5)
                    return p.returncode == 0
                if shutil.which("xclip"):
                    p = subprocess.Popen(
                        ["xclip", "-selection", "clipboard", "-i"],
                        stdin=subprocess.PIPE
                    )
                    p.communicate(input=text.encode("utf-8"), timeout=1.5)
                    return p.returncode == 0
                if shutil.which("xsel"):
                    p = subprocess.Popen(
                        ["xsel", "--clipboard", "--input"],
                        stdin=subprocess.PIPE
                    )
                    p.communicate(input=text.encode("utf-8"), timeout=1.5)
                    return p.returncode == 0
        except Exception:
            pass
        return False


class DesktopState:
    def __init__(self):
        self.config_dir = Path.home() / ".config" / "lanclip-share"
        self.config_dir.mkdir(parents=True, exist_ok=True)
        self.config_file = self.config_dir / "settings.json"
        self.downloads_dir = Path.home() / "Downloads" / "LanClipShare"
        self.downloads_dir.mkdir(parents=True, exist_ok=True)

        self.lock = threading.Lock()
        self.device_id = str(uuid.uuid4())
        self.device_name = get_default_device_name()
        self.device_brand = get_os_brand()
        self.auto_copy_received = True
        self.auto_sync_clipboard = False
        self.selected_peer_id = None
        self.peers = {}  # id -> dict
        self.transfers = []  # list of dicts (newest first)
        self.last_clipboard_text = ""
        self.running = True

        self._load_config()

    def _load_config(self):
        if not self.config_file.exists():
            self._save_config()
            return
        try:
            data = json.loads(self.config_file.read_text(encoding="utf-8"))
            self.device_id = data.get("device_id") or self.device_id
            self.device_name = data.get("device_name") or self.device_name
            self.auto_copy_received = bool(data.get("auto_copy_received", True))
            self.auto_sync_clipboard = bool(data.get("auto_sync_clipboard", False))
            for peer in data.get("saved_peers", []):
                pid = peer.get("id")
                if pid:
                    peer["lastSeenMs"] = int(time.time() * 1000)
                    peer["isManualOrQr"] = True
                    peer["isFavorite"] = True
                    self.peers[pid] = peer
        except Exception:
            pass

    def _save_config(self):
        try:
            saved_peers = [
                p for p in self.peers.values()
                if p.get("isFavorite") or p.get("isManualOrQr")
            ]
            payload = {
                "device_id": self.device_id,
                "device_name": self.device_name,
                "auto_copy_received": self.auto_copy_received,
                "auto_sync_clipboard": self.auto_sync_clipboard,
                "saved_peers": saved_peers,
            }
            self.config_file.write_text(json.dumps(payload, indent=2), encoding="utf-8")
        except Exception:
            pass

    def get_local_device(self) -> dict:
        ip = get_local_ipv4()
        return {
            "id": self.device_id,
            "name": self.device_name,
            "brand": self.device_brand,
            "ipAddress": ip,
            "port": DEFAULT_TCP_PORT,
        }

    def get_qr_uri(self) -> str:
        dev = self.get_local_device()
        params = urllib.parse.urlencode({
            "ip": dev["ipAddress"],
            "port": str(dev["port"]),
            "id": dev["id"],
            "name": dev["name"],
            "brand": dev["brand"],
        })
        return f"lanclip://connect?{params}"

    def upsert_peer(self, peer: dict, is_manual: bool = False):
        with self.lock:
            pid = peer.get("id") or f"{peer.get('ipAddress')}:{peer.get('port', DEFAULT_TCP_PORT)}"
            if pid == self.device_id:
                return
            ip = peer.get("ipAddress", "")
            if not ip:
                return
            # Remove duplicate manual IP entry if same IP responded with real device ID
            to_remove = [
                k for k, v in self.peers.items()
                if k != pid and v.get("ipAddress") == ip and k == f"{ip}:{v.get('port', DEFAULT_TCP_PORT)}"
            ]
            for k in to_remove:
                del self.peers[k]

            existing = self.peers.get(pid, {})
            name = peer.get("name") or existing.get("name") or f"Dispositivo ({ip})"
            brand = peer.get("brand") or existing.get("brand") or "Android"
            display_title = f"{brand} • {name}" if brand and brand.lower() not in name.lower() else name

            self.peers[pid] = {
                "id": pid,
                "name": name,
                "brand": brand,
                "displayTitle": display_title,
                "ipAddress": ip,
                "port": int(peer.get("port") or existing.get("port") or DEFAULT_TCP_PORT),
                "lastSeenMs": int(time.time() * 1000),
                "isManualOrQr": is_manual or existing.get("isManualOrQr", False),
                "isFavorite": is_manual or existing.get("isFavorite", False),
            }
            if is_manual or not self.selected_peer_id:
                self.selected_peer_id = pid
            if is_manual:
                self._save_config()

    def get_sorted_peers(self) -> list:
        now_ms = int(time.time() * 1000)
        with self.lock:
            stale = [
                k for k, p in self.peers.items()
                if not p.get("isFavorite") and not p.get("isManualOrQr")
                and (now_ms - p.get("lastSeenMs", 0) > 28000)
            ]
            for k in stale:
                del self.peers[k]
            peers_list = list(self.peers.values())
        peers_list.sort(key=lambda x: (not x.get("isFavorite", False), -x.get("lastSeenMs", 0)))
        return peers_list

    def resolve_targets(self, explicit_peer_id: str = None) -> list:
        peers = self.get_sorted_peers()
        if not peers:
            return []
        target_id = explicit_peer_id or self.selected_peer_id
        if target_id:
            for p in peers:
                if p["id"] == target_id:
                    return [p]
        favs = [p for p in peers if p.get("isFavorite")]
        if favs:
            return favs
        return [peers[0]]

    def upsert_transfer(self, item: dict):
        with self.lock:
            for idx, existing in enumerate(self.transfers):
                if existing["id"] == item["id"]:
                    self.transfers[idx] = item
                    return
            self.transfers.insert(0, item)
            if len(self.transfers) > 100:
                self.transfers = self.transfers[:100]


class LanDiscoveryService:
    """Descoberta UDP na porta 45454 idêntica ao HybridDiscoveryManager.kt do Android."""

    def __init__(self, state: DesktopState):
        self.state = state

    def start(self):
        threading.Thread(target=self._udp_listener_loop, daemon=True).start()
        threading.Thread(target=self._udp_beacon_loop, daemon=True).start()

    def send_immediate_beacon(self, target_ip: str = None):
        threading.Thread(target=self._send_beacon_packet, args=(target_ip,), daemon=True).start()

    def _send_beacon_packet(self, target_ip: str = None):
        local = self.state.get_local_device()
        payload = json.dumps({
            "type": "LANCLIP_BEACON",
            "id": local["id"],
            "name": local["name"],
            "brand": local["brand"],
            "ipAddress": local["ipAddress"],
            "port": local["port"],
        }).encode("utf-8")

        targets = [target_ip] if target_ip else get_subnet_broadcast_addresses(local["ipAddress"])
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            sock.settimeout(1.0)
            for addr in targets:
                try:
                    sock.sendto(payload, (addr, DEFAULT_UDP_DISCOVERY_PORT))
                except Exception:
                    pass
            sock.close()
        except Exception:
            pass

    def _udp_listener_loop(self):
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            if hasattr(socket, "SO_REUSEPORT"):
                try:
                    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
                except Exception:
                    pass
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            sock.bind(("", DEFAULT_UDP_DISCOVERY_PORT))
            while self.state.running:
                data, addr = sock.recvfrom(4096)
                sender_ip = addr[0]
                try:
                    msg = json.loads(data.decode("utf-8", errors="ignore"))
                    if msg.get("type") == "LANCLIP_BEACON":
                        msg["ipAddress"] = sender_ip or msg.get("ipAddress", "")
                        self.state.upsert_peer(msg, is_manual=False)
                except Exception:
                    pass
        except Exception:
            pass
        finally:
            try:
                sock.close()
            except Exception:
                pass

    def _udp_beacon_loop(self):
        while self.state.running:
            self._send_beacon_packet(None)
            for p in self.state.get_sorted_peers():
                if p.get("isManualOrQr") or p.get("isFavorite"):
                    self._send_beacon_packet(p.get("ipAddress"))
            time.sleep(3.5)


class LanTransferService:
    """Servidor e Cliente TCP na porta 45455 100% compatível com LanTransferEngine.kt do Android."""

    def __init__(self, state: DesktopState, discovery: LanDiscoveryService):
        self.state = state
        self.discovery = discovery

    def start(self):
        threading.Thread(target=self._tcp_server_loop, daemon=True).start()
        threading.Thread(target=self._clipboard_watcher_loop, daemon=True).start()

    def _tcp_server_loop(self):
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            srv.bind(("0.0.0.0", DEFAULT_TCP_PORT))
            srv.listen(16)
            while self.state.running:
                client, addr = srv.accept()
                threading.Thread(
                    target=self._handle_incoming_client,
                    args=(client, addr[0]),
                    daemon=True
                ).start()
        except Exception as e:
            print(f"[LanClip] Erro no servidor TCP {DEFAULT_TCP_PORT}: {e}", file=sys.stderr)
        finally:
            try:
                srv.close()
            except Exception:
                pass

    def _recv_exact(self, sock: socket.socket, n: int) -> bytes:
        buf = bytearray()
        while len(buf) < n:
            chunk = sock.recv(n - len(buf))
            if not chunk:
                raise ConnectionError("Conexão encerrada prematuramente")
            buf.extend(chunk)
        return bytes(buf)

    def _handle_incoming_client(self, client: socket.socket, remote_ip: str):
        with client:
            try:
                raw_len = self._recv_exact(client, 4)
                (header_size,) = struct.unpack(">i", raw_len)
                if header_size <= 0 or header_size > 1024 * 1024:
                    return
                header_bytes = self._recv_exact(client, header_size)
                header = json.loads(header_bytes.decode("utf-8"))

                sender_id = header.get("senderId") or f"{remote_ip}:{header.get('senderPort', DEFAULT_TCP_PORT)}"
                sender_name = header.get("senderName") or f"Dispositivo ({remote_ip})"
                sender_brand = header.get("senderBrand") or "Android"
                sender_port = int(header.get("senderPort") or DEFAULT_TCP_PORT)

                self.state.upsert_peer({
                    "id": sender_id,
                    "name": sender_name,
                    "brand": sender_brand,
                    "ipAddress": remote_ip,
                    "port": sender_port,
                })

                sender_label = (
                    f"{sender_brand} • {sender_name}"
                    if sender_brand and sender_brand.lower() not in sender_name.lower()
                    else sender_name
                )
                content_type = header.get("contentType", "FILE")
                transfer_id = header.get("transferId") or str(uuid.uuid4())

                if content_type == "CLIPBOARD_TEXT":
                    text_content = header.get("textContent") or ""
                    encoded_len = len(text_content.encode("utf-8"))
                    self.state.last_clipboard_text = text_content
                    if self.state.auto_copy_received:
                        SystemClipboard.write(text_content)

                    self.state.upsert_transfer({
                        "id": transfer_id,
                        "contentType": "CLIPBOARD_TEXT",
                        "title": text_content[:90].replace("\n", " "),
                        "mimeType": "text/plain",
                        "totalBytes": encoded_len,
                        "transferredBytes": encoded_len,
                        "speedMbps": 0.0,
                        "status": "COMPLETED",
                        "direction": "RECEIVED",
                        "peerName": sender_label,
                        "textContent": text_content,
                        "savedFilePath": None,
                        "timestampMs": int(time.time() * 1000),
                    })
                    return

                # Recebimento de Arquivo / Foto / Planilha / APK
                orig_name = header.get("fileName") or "arquivo_recebido"
                safe_name = "".join(c if c not in '\\/:*?"<>|' else "_" for c in orig_name).strip() or "arquivo"
                dest_path = self._unique_file_path(self.state.downloads_dir, safe_name)
                payload_size = int(header.get("payloadSize") or 0)
                mime_type = header.get("mimeType") or "*/*"

                item = {
                    "id": transfer_id,
                    "contentType": content_type,
                    "title": dest_path.name,
                    "mimeType": mime_type,
                    "totalBytes": payload_size,
                    "transferredBytes": 0,
                    "speedMbps": 0.0,
                    "status": "IN_PROGRESS",
                    "direction": "RECEIVED",
                    "peerName": sender_label,
                    "textContent": None,
                    "savedFilePath": str(dest_path),
                    "timestampMs": int(time.time() * 1000),
                }
                self.state.upsert_transfer(dict(item))

                start_t = time.time()
                last_ui = start_t
                total_recv = 0
                with open(dest_path, "wb") as f_out:
                    while True:
                        chunk = client.recv(65536)
                        if not chunk:
                            break
                        f_out.write(chunk)
                        total_recv += len(chunk)
                        now = time.time()
                        if now - last_ui >= 0.15:
                            elapsed = max(0.1, now - start_t)
                            item["transferredBytes"] = total_recv
                            item["speedMbps"] = (total_recv / (1024.0 * 1024.0)) / elapsed
                            self.state.upsert_transfer(dict(item))
                            last_ui = now

                elapsed = max(0.1, time.time() - start_t)
                item["transferredBytes"] = total_recv
                item["totalBytes"] = payload_size if payload_size > 0 else total_recv
                item["speedMbps"] = (total_recv / (1024.0 * 1024.0)) / elapsed
                item["status"] = "COMPLETED"
                self.state.upsert_transfer(dict(item))
            except Exception as e:
                print(f"[LanClip] Erro ao receber conexão de {remote_ip}: {e}", file=sys.stderr)

    def send_clipboard_text(self, text: str, peer: dict) -> bool:
        local = self.state.get_local_device()
        transfer_id = str(uuid.uuid4())
        text_bytes = text.encode("utf-8")
        header = {
            "transferId": transfer_id,
            "contentType": "CLIPBOARD_TEXT",
            "fileName": "Clipboard.txt",
            "mimeType": "text/plain",
            "payloadSize": len(text_bytes),
            "textContent": text,
            "senderId": local["id"],
            "senderName": local["name"],
            "senderBrand": local["brand"],
            "senderPort": local["port"],
        }

        item = {
            "id": transfer_id,
            "contentType": "CLIPBOARD_TEXT",
            "title": text[:90].replace("\n", " "),
            "mimeType": "text/plain",
            "totalBytes": len(text_bytes),
            "transferredBytes": 0,
            "speedMbps": 0.0,
            "status": "IN_PROGRESS",
            "direction": "SENT",
            "peerName": peer.get("displayTitle") or peer.get("name"),
            "textContent": text,
            "savedFilePath": None,
            "timestampMs": int(time.time() * 1000),
        }
        self.state.upsert_transfer(dict(item))

        try:
            header_bytes = json.dumps(header).encode("utf-8")
            with socket.create_connection((peer["ipAddress"], int(peer["port"])), timeout=5.0) as sock:
                sock.sendall(struct.pack(">i", len(header_bytes)))
                sock.sendall(header_bytes)
            item["transferredBytes"] = len(text_bytes)
            item["status"] = "COMPLETED"
            self.state.upsert_transfer(dict(item))
            return True
        except Exception as e:
            item["status"] = "FAILED"
            item["errorMessage"] = str(e)
            self.state.upsert_transfer(dict(item))
            return False

    def send_file_stream(self, file_name: str, file_bytes: bytes, mime_type: str, peer: dict) -> bool:
        local = self.state.get_local_device()
        transfer_id = str(uuid.uuid4())
        total_size = len(file_bytes)
        content_type = classify_content_type(file_name, mime_type)

        header = {
            "transferId": transfer_id,
            "contentType": content_type,
            "fileName": file_name,
            "mimeType": mime_type or "application/octet-stream",
            "payloadSize": total_size,
            "senderId": local["id"],
            "senderName": local["name"],
            "senderBrand": local["brand"],
            "senderPort": local["port"],
        }

        item = {
            "id": transfer_id,
            "contentType": content_type,
            "title": file_name,
            "mimeType": mime_type or "application/octet-stream",
            "totalBytes": total_size,
            "transferredBytes": 0,
            "speedMbps": 0.0,
            "status": "IN_PROGRESS",
            "direction": "SENT",
            "peerName": peer.get("displayTitle") or peer.get("name"),
            "textContent": None,
            "savedFilePath": None,
            "timestampMs": int(time.time() * 1000),
        }
        self.state.upsert_transfer(dict(item))

        try:
            header_bytes = json.dumps(header).encode("utf-8")
            start_t = time.time()
            with socket.create_connection((peer["ipAddress"], int(peer["port"])), timeout=6.0) as sock:
                sock.sendall(struct.pack(">i", len(header_bytes)))
                sock.sendall(header_bytes)
                offset = 0
                chunk_size = 65536
                last_ui = start_t
                while offset < total_size:
                    end = min(offset + chunk_size, total_size)
                    sock.sendall(memoryview(file_bytes)[offset:end])
                    offset = end
                    now = time.time()
                    if now - last_ui >= 0.15:
                        elapsed = max(0.1, now - start_t)
                        item["transferredBytes"] = offset
                        item["speedMbps"] = (offset / (1024.0 * 1024.0)) / elapsed
                        self.state.upsert_transfer(dict(item))
                        last_ui = now

            elapsed = max(0.1, time.time() - start_t)
            item["transferredBytes"] = total_size
            item["speedMbps"] = (total_size / (1024.0 * 1024.0)) / elapsed
            item["status"] = "COMPLETED"
            self.state.upsert_transfer(dict(item))
            return True
        except Exception as e:
            item["status"] = "FAILED"
            item["errorMessage"] = str(e)
            self.state.upsert_transfer(dict(item))
            return False

    def _clipboard_watcher_loop(self):
        while self.state.running:
            try:
                if self.state.auto_sync_clipboard:
                    curr = SystemClipboard.read()
                    if curr and curr != self.state.last_clipboard_text:
                        self.state.last_clipboard_text = curr
                        targets = self.state.resolve_targets()
                        for t in targets:
                            self.send_clipboard_text(curr, t)
            except Exception:
                pass
            time.sleep(1.5)

    @staticmethod
    def _unique_file_path(directory: Path, filename: str) -> Path:
        candidate = directory / filename
        if not candidate.exists():
            return candidate
        stem = candidate.stem
        suffix = candidate.suffix
        counter = 1
        while candidate.exists():
            candidate = directory / f"{stem}_({counter}){suffix}"
            counter += 1
        return candidate


DESKTOP_UI_HTML = r"""<!DOCTYPE html>
<html lang="pt-BR">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>LanClip Share Desktop</title>
<style>
  :root {
    --bg: #0B1120;
    --surface: #111827;
    --surface-var: #1E293B;
    --primary: #38BDF8;
    --primary-dark: #0284C7;
    --secondary: #4ADE80;
    --text: #F8FAFC;
    --muted: #94A3B8;
    --danger: #F87171;
    --border: rgba(148, 163, 184, 0.16);
  }
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body {
    background: var(--bg);
    color: var(--text);
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Ubuntu, sans-serif;
    min-height: 100vh;
    display: flex;
    flex-direction: column;
  }
  header {
    background: var(--surface);
    border-bottom: 1px solid var(--border);
    padding: 14px 22px;
    display: flex;
    align-items: center;
    justify-content: space-between;
  }
  .brand-row { display: flex; align-items: center; gap: 12px; }
  .logo-badge {
    width: 38px; height: 38px; border-radius: 10px;
    background: linear-gradient(135deg, #070E1D, #0284C7);
    border: 1px solid rgba(56, 189, 248, 0.45);
    display: flex; align-items: center; justify-content: center;
    font-size: 20px;
  }
  .title-col h1 { font-size: 17px; font-weight: 700; }
  .title-col p { font-size: 12px; color: var(--muted); font-family: monospace; margin-top: 2px; }
  .top-actions { display: flex; gap: 8px; }
  button {
    cursor: pointer;
    border: none;
    border-radius: 10px;
    font-weight: 600;
    font-size: 13px;
    padding: 9px 14px;
    transition: all 0.15s ease;
    display: inline-flex;
    align-items: center;
    gap: 6px;
  }
  .btn-primary { background: var(--primary); color: #062032; }
  .btn-primary:hover { background: #7DD3FC; }
  .btn-tonal { background: var(--surface-var); color: var(--text); border: 1px solid var(--border); }
  .btn-tonal:hover { border-color: var(--primary); }
  .peer-strip {
    background: rgba(30, 41, 59, 0.65);
    border-bottom: 1px solid var(--border);
    padding: 10px 22px;
    display: flex;
    align-items: center;
    gap: 10px;
    overflow-x: auto;
  }
  .status-dot {
    width: 10px; height: 10px; border-radius: 50%;
    background: #FBBF24;
    flex-shrink: 0;
  }
  .status-dot.online { background: var(--secondary); box-shadow: 0 0 8px var(--secondary); }
  .peer-chip {
    padding: 6px 12px;
    border-radius: 999px;
    background: var(--surface);
    border: 1px solid var(--border);
    color: var(--text);
    font-size: 12.5px;
    cursor: pointer;
    white-space: nowrap;
  }
  .peer-chip.active {
    border-color: var(--primary);
    background: rgba(56, 189, 248, 0.16);
    color: #E0F2FE;
  }
  main {
    flex: 1;
    max-width: 1080px;
    width: 100%;
    margin: 0 auto;
    padding: 20px 22px;
    display: grid;
    grid-template-columns: 1.15fr 0.85fr;
    gap: 18px;
  }
  @media (max-width: 820px) {
    main { grid-template-columns: 1fr; }
  }
  .card {
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: 14px;
    padding: 18px;
    margin-bottom: 16px;
  }
  .card h2 {
    font-size: 15px;
    margin-bottom: 10px;
    display: flex;
    align-items: center;
    justify-content: space-between;
  }
  textarea {
    width: 100%;
    min-height: 110px;
    background: #0B1120;
    border: 1px solid var(--border);
    border-radius: 10px;
    color: var(--text);
    padding: 12px;
    font-size: 13.5px;
    resize: vertical;
    outline: none;
  }
  textarea:focus, input[type="text"]:focus { border-color: var(--primary); }
  .dropzone {
    border: 2px dashed rgba(56, 189, 248, 0.4);
    border-radius: 12px;
    padding: 22px;
    text-align: center;
    background: rgba(12, 74, 110, 0.12);
    cursor: pointer;
    transition: 0.2s;
  }
  .dropzone.dragover {
    border-color: var(--secondary);
    background: rgba(74, 222, 128, 0.12);
  }
  .transfer-list { display: flex; flex-direction: column; gap: 10px; max-height: 430px; overflow-y: auto; }
  .transfer-item {
    background: var(--surface-var);
    border: 1px solid var(--border);
    border-radius: 11px;
    padding: 12px;
  }
  .transfer-top { display: flex; justify-content: space-between; gap: 8px; align-items: flex-start; }
  .transfer-title { font-weight: 600; font-size: 13.5px; word-break: break-all; }
  .transfer-meta { font-size: 11.5px; color: var(--muted); margin-top: 4px; }
  .progress-bar {
    height: 5px; background: #0B1120; border-radius: 4px; overflow: hidden; margin-top: 8px;
  }
  .progress-fill { height: 100%; background: var(--primary); transition: width 0.15s; }
  .input-row { display: flex; gap: 8px; margin-top: 8px; }
  input[type="text"] {
    flex: 1;
    background: #0B1120;
    border: 1px solid var(--border);
    border-radius: 10px;
    color: var(--text);
    padding: 9px 12px;
    font-size: 13px;
    outline: none;
  }
  .toggle-row {
    display: flex; align-items: center; justify-content: space-between;
    padding: 10px 0; border-bottom: 1px solid var(--border);
    font-size: 13px;
  }
  .toggle-row:last-child { border-bottom: none; }
  .uri-box {
    font-family: monospace; font-size: 11.5px; background: #0B1120;
    padding: 10px; border-radius: 8px; border: 1px solid var(--border);
    word-break: break-all; color: var(--primary); margin-top: 8px;
  }
</style>
</head>
<body>
<header>
  <div class="brand-row">
    <div class="logo-badge">📋</div>
    <div class="title-col">
      <h1 id="hdrTitle">LanClip Share Desktop</h1>
      <p id="hdrSub">Carregando IP da rede...</p>
    </div>
  </div>
  <div class="top-actions">
    <button class="btn-tonal" onclick="pingNetwork()">🔄 Buscar na Rede</button>
    <button class="btn-tonal" onclick="openDownloadsFolder()">📂 Pasta Downloads</button>
  </div>
</header>

<div class="peer-strip" id="peerStrip">
  <span class="status-dot" id="statusDot"></span>
  <span id="peerEmptyLabel" style="font-size:12.5px;color:var(--muted);">Procurando Xiaomi / Motorola no Wi-Fi...</span>
  <div id="peerChips" style="display:flex;gap:8px;"></div>
</div>

<main>
  <div>
    <div class="card">
      <h2>
        <span>📋 Área de Transferência (Clipboard)</span>
        <button class="btn-tonal" style="padding:5px 10px;font-size:12px;" onclick="readSystemClip()">Ler Ctrl+C Atual</button>
      </h2>
      <textarea id="clipInput" placeholder="Cole ou digite qualquer texto, link ou código para enviar instantaneamente para o celular/tablet..."></textarea>
      <div style="display:flex;gap:8px;margin-top:10px;">
        <button class="btn-primary" style="flex:1;justify-content:center;" onclick="sendClip()">🚀 Enviar Área de Transferência</button>
        <button class="btn-tonal" onclick="copyLocalClip()">Copiar no PC</button>
      </div>
    </div>

    <div class="card">
      <h2><span>📦 Enviar Arquivos (Fotos, Planilhas, PDFs, APKs)</span></h2>
      <div class="dropzone" id="dropzone" onclick="document.getElementById('filePicker').click()">
        <p style="font-size:15px;font-weight:600;color:var(--primary);">Arraste e solte arquivos aqui ou clique para escolher</p>
        <p style="font-size:12px;color:var(--muted);margin-top:6px;">Suporta qualquer tamanho de arquivo na velocidade máxima do Wi-Fi local (porta TCP 45455)</p>
        <input type="file" id="filePicker" multiple style="display:none" onchange="uploadFiles(this.files)">
      </div>
    </div>

    <div class="card">
      <h2>
        <span>🕓 Histórico de Transferências</span>
        <button class="btn-tonal" style="padding:4px 9px;font-size:11.5px;" onclick="clearHistory()">Limpar</button>
      </h2>
      <div class="transfer-list" id="transferList"></div>
    </div>
  </div>

  <div>
    <div class="card">
      <h2><span>📱 Conectar Dispositivo (Auto Wi-Fi ou IP Manual)</span></h2>
      <p style="font-size:12px;color:var(--muted);line-height:1.45;">
        Se o seu tablet Xiaomi ou celular Motorola estiver no mesmo Wi-Fi, ele aparecerá automaticamente acima. Caso seu roteador isole multicast, digite o IP exibido no topo do app Android:
      </p>
      <div class="input-row">
        <input type="text" id="manualIpInput" placeholder="Ex: 192.168.1.15 ou lanclip://connect?...">
        <button class="btn-primary" onclick="addManualPeer()">Conectar IP</button>
      </div>
      <div style="margin-top:14px;">
        <div style="font-size:12px;font-weight:600;color:var(--muted);">URI de Pareamento deste Computador (para colar ou ler no Android):</div>
        <div class="uri-box" id="pairingUriBox">lanclip://connect?...</div>
      </div>
    </div>

    <div class="card">
      <h2><span>⚙️ Configurações do Desktop</span></h2>
      <div class="input-row" style="margin-bottom:10px;">
        <input type="text" id="deviceNameInput" placeholder="Nome deste computador na rede">
        <button class="btn-tonal" onclick="saveDeviceName()">Salvar Nome</button>
      </div>
      <div class="toggle-row">
        <div>
          <div style="font-weight:600;">Auto-copiar textos recebidos</div>
          <div style="font-size:11.5px;color:var(--muted);">Copia direto para o Ctrl+V do sistema quando o celular envia um texto</div>
        </div>
        <input type="checkbox" id="chkAutoCopy" onchange="saveToggles()">
      </div>
      <div class="toggle-row">
        <div>
          <div style="font-weight:600;">Sincronizar Ctrl+C automaticamente</div>
          <div style="font-size:11.5px;color:var(--muted);">Envia automaticamente para o celular sempre que você copiar um novo texto</div>
        </div>
        <input type="checkbox" id="chkAutoSync" onchange="saveToggles()">
      </div>
    </div>
  </div>
</main>

<script>
let currentSelectedPeerId = null;
let initializedName = false;

async function fetchState() {
  try {
    const res = await fetch('/api/state');
    const data = await res.json();
    document.getElementById('hdrTitle').textContent = `LanClip Share • ${data.localDevice.brand}`;
    document.getElementById('hdrSub').textContent = `${data.localDevice.name} • IP: ${data.localDevice.ipAddress}:${data.localDevice.port}`;
    document.getElementById('pairingUriBox').textContent = data.qrUri;

    if (!initializedName) {
      document.getElementById('deviceNameInput').value = data.localDevice.name;
      initializedName = true;
    }
    document.getElementById('chkAutoCopy').checked = data.autoCopyReceived;
    document.getElementById('chkAutoSync').checked = data.autoSyncClipboard;

    currentSelectedPeerId = data.selectedPeerId;
    renderPeers(data.peers, data.selectedPeerId);
    renderTransfers(data.transfers);
  } catch (e) {}
}

function renderPeers(peers, selectedId) {
  const dot = document.getElementById('statusDot');
  const emptyLbl = document.getElementById('peerEmptyLabel');
  const container = document.getElementById('peerChips');
  container.innerHTML = '';

  if (!peers || peers.length === 0) {
    dot.className = 'status-dot';
    emptyLbl.style.display = 'inline';
    return;
  }
  dot.className = 'status-dot online';
  emptyLbl.style.display = 'none';

  peers.forEach((p, idx) => {
    const chip = document.createElement('button');
    const isActive = (selectedId && p.id === selectedId) || (!selectedId && idx === 0);
    chip.className = 'peer-chip' + (isActive ? ' active' : '');
    chip.textContent = `${p.isFavorite ? '⭐ ' : '📱 '}${p.displayTitle} (${p.ipAddress})`;
    chip.onclick = () => selectPeer(p.id);
    container.appendChild(chip);
  });
}

function renderTransfers(transfers) {
  const list = document.getElementById('transferList');
  if (!transfers || transfers.length === 0) {
    list.innerHTML = '<div style="text-align:center;color:var(--muted);padding:24px;font-size:13px;">Nenhuma transferência ainda.</div>';
    return;
  }
  list.innerHTML = transfers.map(t => {
    const pct = t.totalBytes > 0 ? Math.min(100, Math.round((t.transferredBytes / t.totalBytes) * 100)) : 100;
    const dir = t.direction === 'SENT' ? 'Enviado para' : 'Recebido de';
    const statusBadge = t.status === 'COMPLETED' ? '✅' : (t.status === 'FAILED' ? '❌' : `⏳ ${pct}%`);
    const speed = t.speedMbps > 0.05 ? ` • ${t.speedMbps.toFixed(1)} MB/s` : '';
    let actions = '';
    if (t.status === 'COMPLETED') {
      if (t.contentType === 'CLIPBOARD_TEXT' && t.textContent) {
        const enc = encodeURIComponent(t.textContent);
        actions = `<div style="margin-top:8px;text-align:right;"><button class="btn-tonal" style="padding:4px 10px;font-size:11.5px;" onclick="copyTextValue(decodeURIComponent('${enc}'))">📋 Copiar Texto</button></div>`;
      } else if (t.savedFilePath) {
        const encPath = encodeURIComponent(t.savedFilePath);
        actions = `<div style="margin-top:8px;text-align:right;"><button class="btn-primary" style="padding:4px 10px;font-size:11.5px;" onclick="openSavedFile(decodeURIComponent('${encPath}'))">📂 Abrir Arquivo</button></div>`;
      }
    }
    const prog = t.status === 'IN_PROGRESS' ? `<div class="progress-bar"><div class="progress-fill" style="width:${pct}%"></div></div>` : '';
    return `<div class="transfer-item">
      <div class="transfer-top">
        <div class="transfer-title">${escapeHtml(t.title)}</div>
        <div>${statusBadge}</div>
      </div>
      <div class="transfer-meta">${dir} ${escapeHtml(t.peerName)} • ${formatBytes(t.totalBytes)}${speed}</div>
      ${prog}
      ${actions}
    </div>`;
  }).join('');
}

function escapeHtml(s) {
  return (s || '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
}

function formatBytes(b) {
  if (!b || b < 1024) return (b || 0) + ' B';
  if (b < 1048576) return (b / 1024).toFixed(1) + ' KB';
  if (b < 1073741824) return (b / 1048576).toFixed(2) + ' MB';
  return (b / 1073741824).toFixed(2) + ' GB';
}

async function readSystemClip() {
  const res = await fetch('/api/clipboard/read');
  const data = await res.json();
  if (data.text) document.getElementById('clipInput').value = data.text;
}

async function sendClip() {
  const text = document.getElementById('clipInput').value.trim();
  if (!text) return;
  await fetch('/api/clipboard/send', {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({text})
  });
  fetchState();
}

async function copyLocalClip() {
  const text = document.getElementById('clipInput').value;
  await copyTextValue(text);
}

async function copyTextValue(text) {
  await fetch('/api/clipboard/write', {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({text})
  });
}

async function uploadFiles(fileList) {
  if (!fileList || fileList.length === 0) return;
  for (let i = 0; i < fileList.length; i++) {
    const file = fileList[i];
    const fd = new FormData();
    fd.append('file', file, file.name);
    await fetch('/api/files/send', { method: 'POST', body: fd });
  }
  fetchState();
}

async function addManualPeer() {
  const raw = document.getElementById('manualIpInput').value.trim();
  if (!raw) return;
  await fetch('/api/peers/add', {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({input: raw})
  });
  document.getElementById('manualIpInput').value = '';
  fetchState();
}

async function selectPeer(id) {
  await fetch('/api/peers/select', {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({id})
  });
  fetchState();
}

async function saveDeviceName() {
  const name = document.getElementById('deviceNameInput').value.trim();
  await fetch('/api/settings', {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({deviceName: name})
  });
  fetchState();
}

async function saveToggles() {
  await fetch('/api/settings', {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({
      autoCopyReceived: document.getElementById('chkAutoCopy').checked,
      autoSyncClipboard: document.getElementById('chkAutoSync').checked
    })
  });
}

async function pingNetwork() {
  await fetch('/api/network/ping', {method: 'POST'});
  fetchState();
}

async function openDownloadsFolder() {
  await fetch('/api/files/open-folder', {method: 'POST'});
}

async function openSavedFile(path) {
  await fetch('/api/files/open', {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({path})
  });
}

async function clearHistory() {
  await fetch('/api/transfers/clear', {method: 'POST'});
  fetchState();
}

const dz = document.getElementById('dropzone');
dz.addEventListener('dragover', e => { e.preventDefault(); dz.classList.add('dragover'); });
dz.addEventListener('dragleave', () => dz.classList.remove('dragover'));
dz.addEventListener('drop', e => {
  e.preventDefault();
  dz.classList.remove('dragover');
  if (e.dataTransfer.files.length) uploadFiles(e.dataTransfer.files);
});

fetchState();
readSystemClip();
setInterval(fetchState, 1200);
</script>
</body>
</html>
"""


def parse_pairing_input(raw: str):
    trimmed = (raw or "").strip()
    if not trimmed:
        return None
    if trimmed.lower().startswith("lanclip://"):
        parsed = urllib.parse.urlparse(trimmed)
        qs = urllib.parse.parse_qs(parsed.query)
        ip = (qs.get("ip") or [""])[0].strip()
        if not ip:
            return None
        port = int((qs.get("port") or [DEFAULT_TCP_PORT])[0])
        pid = (qs.get("id") or [f"{ip}:{port}"])[0]
        name = (qs.get("name") or [f"Dispositivo ({ip})"])[0]
        brand = (qs.get("brand") or ["Android"])[0]
        return {"id": pid, "name": name, "brand": brand, "ipAddress": ip, "port": port}

    parts = trimmed.split(":")
    ip = parts[0].strip()
    port = int(parts[1]) if len(parts) > 1 and parts[1].strip().isdigit() else DEFAULT_TCP_PORT
    return {
        "id": f"{ip}:{port}",
        "name": f"Dispositivo ({ip})",
        "brand": "Android",
        "ipAddress": ip,
        "port": port,
    }


def open_path_in_os(target_path: str):
    sys_name = platform.system().lower()
    try:
        if "darwin" in sys_name:
            subprocess.Popen(["open", target_path])
        else:
            subprocess.Popen(["xdg-open", target_path])
    except Exception:
        pass


class DesktopUiHandler(http.server.BaseHTTPRequestHandler):
    state: DesktopState = None
    discovery: LanDiscoveryService = None
    transfer: LanTransferService = None

    def log_message(self, format, *args):
        pass

    def _send_json(self, payload: dict, status: int = 200):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_json_body(self) -> dict:
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0:
            return {}
        return json.loads(self.rfile.read(length).decode("utf-8"))

    def do_GET(self):
        if self.path == "/" or self.path.startswith("/index.html"):
            body = DESKTOP_UI_HTML.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return

        if self.path == "/api/state":
            self._send_json({
                "localDevice": self.state.get_local_device(),
                "qrUri": self.state.get_qr_uri(),
                "peers": self.state.get_sorted_peers(),
                "selectedPeerId": self.state.selected_peer_id,
                "transfers": self.state.transfers[:40],
                "autoCopyReceived": self.state.auto_copy_received,
                "autoSyncClipboard": self.state.auto_sync_clipboard,
            })
            return

        if self.path == "/api/clipboard/read":
            self._send_json({"text": SystemClipboard.read()})
            return

        self.send_error(404)

    def do_POST(self):
        if self.path == "/api/clipboard/write":
            body = self._read_json_body()
            text = body.get("text") or ""
            self.state.last_clipboard_text = text
            ok = SystemClipboard.write(text)
            self._send_json({"ok": ok})
            return

        if self.path == "/api/clipboard/send":
            body = self._read_json_body()
            text = (body.get("text") or "").strip()
            if not text:
                self._send_json({"ok": False, "error": "Texto vazio"}, 400)
                return
            self.state.last_clipboard_text = text
            SystemClipboard.write(text)
            targets = self.state.resolve_targets()
            for t in targets:
                threading.Thread(
                    target=self.transfer.send_clipboard_text,
                    args=(text, t),
                    daemon=True
                ).start()
            self._send_json({"ok": len(targets) > 0, "targets": len(targets)})
            return

        if self.path == "/api/files/send":
            ctype, pdict = cgi.parse_header(self.headers.get("Content-Type", ""))
            if ctype == "multipart/form-data":
                form = cgi.FieldStorage(
                    fp=self.rfile,
                    headers=self.headers,
                    environ={"REQUEST_METHOD": "POST", "CONTENT_TYPE": self.headers.get("Content-Type")}
                )
                file_item = form["file"] if "file" in form else None
                if file_item is not None and file_item.filename:
                    fname = os.path.basename(file_item.filename)
                    fbytes = file_item.file.read()
                    mime = mimetypes.guess_type(fname)[0] or "application/octet-stream"
                    targets = self.state.resolve_targets()
                    for t in targets:
                        threading.Thread(
                            target=self.transfer.send_file_stream,
                            args=(fname, fbytes, mime, t),
                            daemon=True
                        ).start()
                    self._send_json({"ok": True})
                    return
            self._send_json({"ok": False}, 400)
            return

        if self.path == "/api/peers/add":
            body = self._read_json_body()
            peer = parse_pairing_input(body.get("input", ""))
            if peer:
                self.state.upsert_peer(peer, is_manual=True)
                self.discovery.send_immediate_beacon(peer["ipAddress"])
                self._send_json({"ok": True, "peer": peer})
            else:
                self._send_json({"ok": False}, 400)
            return

        if self.path == "/api/peers/select":
            body = self._read_json_body()
            self.state.selected_peer_id = body.get("id")
            self._send_json({"ok": True})
            return

        if self.path == "/api/settings":
            body = self._read_json_body()
            if "deviceName" in body and body["deviceName"]:
                self.state.device_name = body["deviceName"].strip()
            if "autoCopyReceived" in body:
                self.state.auto_copy_received = bool(body["autoCopyReceived"])
            if "autoSyncClipboard" in body:
                self.state.auto_sync_clipboard = bool(body["autoSyncClipboard"])
            self.state._save_config()
            self.discovery.send_immediate_beacon(None)
            self._send_json({"ok": True})
            return

        if self.path == "/api/network/ping":
            self.discovery.send_immediate_beacon(None)
            self._send_json({"ok": True})
            return

        if self.path == "/api/files/open-folder":
            open_path_in_os(str(self.state.downloads_dir))
            self._send_json({"ok": True})
            return

        if self.path == "/api/files/open":
            body = self._read_json_body()
            p = body.get("path")
            if p and os.path.exists(p):
                open_path_in_os(p)
            self._send_json({"ok": True})
            return

        if self.path == "/api/transfers/clear":
            with self.state.lock:
                self.state.transfers.clear()
            self._send_json({"ok": True})
            return

        self.send_error(404)


def launch_desktop_window(url: str):
    """Abre o LanClip Share em uma janela dedicada de aplicativo no macOS ou Linux."""
    sys_name = platform.system().lower()
    if "darwin" in sys_name:
        for app_path in [
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
            "/Applications/Brave Browser.app/Contents/MacOS/Brave Browser",
            "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
        ]:
            if os.path.exists(app_path):
                subprocess.Popen([app_path, f"--app={url}", "--window-size=1060,760"])
                return
        subprocess.Popen(["open", url])
        return

    for bin_name in ["google-chrome", "chromium", "chromium-browser", "brave-browser", "microsoft-edge"]:
        if shutil.which(bin_name):
            subprocess.Popen([bin_name, f"--app={url}", "--window-size=1060,760"])
            return
    webbrowser.open(url)


def main():
    state = DesktopState()
    discovery = LanDiscoveryService(state)
    transfer = LanTransferService(state, discovery)

    discovery.start()
    transfer.start()

    DesktopUiHandler.state = state
    DesktopUiHandler.discovery = discovery
    DesktopUiHandler.transfer = transfer

    ui_port = UI_HTTP_PORT
    server = None
    for candidate_port in range(UI_HTTP_PORT, UI_HTTP_PORT + 20):
        try:
            server = http.server.ThreadingHTTPServer(("127.0.0.1", candidate_port), DesktopUiHandler)
            ui_port = candidate_port
            break
        except OSError:
            continue

    if server is None:
        print("Não foi possível iniciar a interface local do LanClip Share.", file=sys.stderr)
        sys.exit(1)

    url = f"http://127.0.0.1:{ui_port}"
    print(f"====================================================")
    print(f" LanClip Share Desktop ({state.device_brand}) Ativo!")
    print(f" Nome na Rede : {state.device_name}")
    print(f" IP Local     : {state.get_local_device()['ipAddress']} (TCP {DEFAULT_TCP_PORT} / UDP {DEFAULT_UDP_DISCOVERY_PORT})")
    print(f" Interface UI : {url}")
    print(f" Pasta Salva  : {state.downloads_dir}")
    print(f"====================================================")

    if "--no-browser" not in sys.argv:
        threading.Timer(0.4, lambda: launch_desktop_window(url)).start()

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        state.running = False
        server.shutdown()


if __name__ == "__main__":
    main()
