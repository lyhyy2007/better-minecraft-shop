"""极简 RCON 客户端，用于对本地开发服务器执行命令并打印回包。

用法： python rcon.py <password> <command> [<command> ...]
设置环境变量 RCON_OUT=<文件> 时，同时把回包以 UTF-8 写进文件（避免控制台代码页把中文显示成乱码）。
"""
import os
import socket
import struct
import sys
import time

HOST = "127.0.0.1"
PORT = 25575
collected = []


def pack(req_id, req_type, body):
    payload = struct.pack("<ii", req_id, req_type) + body.encode("utf-8") + b"\x00\x00"
    return struct.pack("<i", len(payload)) + payload


def read_packet(sock):
    raw_len = sock.recv(4)
    if len(raw_len) < 4:
        return None
    (length,) = struct.unpack("<i", raw_len)
    data = b""
    while len(data) < length:
        chunk = sock.recv(length - len(data))
        if not chunk:
            break
        data += chunk
    req_id, req_type = struct.unpack("<ii", data[:8])
    return req_id, req_type, data[8:-2].decode("utf-8", errors="replace")


def main():
    password = sys.argv[1]
    commands = sys.argv[2:]
    sock = socket.create_connection((HOST, PORT), timeout=20)
    sock.sendall(pack(1, 3, password))
    response = read_packet(sock)
    if response is None or response[0] == -1:
        print("AUTH FAILED")
        return 1
    print("AUTH OK")
    for command in commands:
        sock.sendall(pack(2, 2, command))
        time.sleep(0.6)
        packet = read_packet(sock)
        if packet is None:
            collected.append(f"--- {command}\n(no response)")
            continue
        collected.append(f"--- {command}\n{packet[2].strip()}")
    sock.close()
    text = "\n".join(collected)
    for line in text.splitlines():
        print(line.encode("ascii", errors="backslashreplace").decode("ascii"))
    out = os.environ.get("RCON_OUT")
    if out:
        with open(out, "w", encoding="utf-8") as handle:
            handle.write(text + "\n")
        print(f"(written to {out})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
