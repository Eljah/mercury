#!/usr/bin/env python3
"""Linux end-to-end test of the packaged JAR via a PTY. No physical meter/GPIO.

Usage: python3 tests/serial_integration.py dist/mercury-pi.jar
Only Python's standard library and Java 17+ are required.
"""
import argparse
import json
import os
import pty
import select
import socket
import subprocess
import tempfile
import threading
import time
import tty
import urllib.error
import urllib.request
from pathlib import Path

REQUEST = bytes.fromhex('00 09 F0 89 27 00 25')
PAYLOAD = bytes.fromhex('00 06 21 42 00 02 08 34 00 01 11 11 00 02 22 22')
VALUES = [621.42, 208.34, 111.11, 222.22]
HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))

def crc16(data):
    crc = 0xffff
    for value in data:
        crc ^= value
        for _ in range(8):
            crc = (crc >> 1) ^ (0xa001 if crc & 1 else 0)
    return crc

def reply():
    data = REQUEST[:5] + PAYLOAD
    return data + crc16(data).to_bytes(2, 'little')

def free_port():
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]

def fetch(port, path='/api/v1/readings'):
    try:
        r = HTTP.open(f'http://127.0.0.1:{port}{path}', timeout=1)
    except urllib.error.HTTPError as e:
        r = e
    with r:
        return r.code, json.loads(r.read())

def await_sample(process, port, predicate, timeout=9):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise AssertionError(f'Java process exited early: {process.returncode}')
        try:
            last = fetch(port)
            if predicate(*last):
                return last
        except (OSError, urllib.error.URLError, json.JSONDecodeError):
            pass
        time.sleep(0.05)
    raise AssertionError(f'Expected HTTP state not reached; last response={last}')

def stop(process):
    process.terminate()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)

def test_demo(jar, work):
    port = free_port()
    with (work / 'demo.log').open('w+') as log:
        process = subprocess.Popen(['java', '-jar', str(jar), '--demo', '--bind', '127.0.0.1', '--port', str(port)], stdout=log, stderr=log)
        try:
            _, data = await_sample(process, port, lambda status, data: status == 200 and data['ok'])
            assert data['demo'] is True and data['has_reading'] is True
            client = subprocess.run(['java', '-cp', str(jar), 'ru.mercury.lan.HttpReadingsClient',
                                     f'http://127.0.0.1:{port}/api/v1/readings'], capture_output=True, text=True, timeout=10)
            assert client.returncode == 0, client.stderr
            assert json.loads(client.stdout)['demo'] is True
            return {'packaged_demo_http': 'passed', 'packaged_java_client': 'passed'}
        finally:
            stop(process)

def test_uart(jar, work):
    master, slave = pty.openpty()
    tty.setraw(slave)
    device = os.ttyname(slave)
    mode = ['silent']
    stopped = threading.Event()
    requests = []
    failures = []

    def emulator():
        pending = bytearray()
        while not stopped.is_set():
            ready, _, _ = select.select([master], [], [], 0.1)
            if not ready:
                continue
            try:
                pending.extend(os.read(master, 512))
            except OSError:
                continue
            while len(pending) >= len(REQUEST):
                packet = bytes(pending[:len(REQUEST)])
                del pending[:len(REQUEST)]
                requests.append(packet.hex())
                if packet != REQUEST:
                    failures.append(f'Unexpected packet: {packet.hex()}')
                    continue
                current = mode[0]
                if current == 'silent':
                    continue
                data = reply()
                if current == 'bad_crc':
                    data = data[:-1] + bytes([data[-1] ^ 1])
                elif current == 'good_echo':
                    data = b'\x42\x7f' + REQUEST + data
                # Split the stream independently of frame boundaries.
                for offset in range(0, len(data), 3):
                    os.write(master, data[offset:offset+3])
                    time.sleep(0.004)

    thread = threading.Thread(target=emulator, daemon=True)
    thread.start()
    port = free_port()
    config = work / 'pty.properties'
    config.write_text(f'meter.address=651401\nserial.device={device}\nserial.timeout.ms=250\npoll.seconds=1\nhttp.bind=127.0.0.1\nhttp.port={port}\ndemo=false\n')
    with (work / 'uart.log').open('w+') as log:
        process = subprocess.Popen(['java', '-jar', str(jar), '--config', str(config)], stdout=log, stderr=log)
        try:
            _, initial = await_sample(process, port, lambda status, data: status == 503 and not data['has_reading'])
            assert initial['tariffs_kwh'] is None and initial['total_kwh'] is None
            mode[0] = 'good_echo'
            _, good = await_sample(process, port, lambda status, data: status == 200 and data['ok'])
            assert good['demo'] is False and good['tariffs_kwh'] == VALUES
            assert good['total_kwh'] == 1163.09
            mode[0] = 'bad_crc'
            _, failed = await_sample(process, port, lambda status, data: status == 503 and data['has_reading'])
            assert failed['tariffs_kwh'] == VALUES and failed['ok'] is False
            assert failed['age_seconds'] >= 1
            mode[0] = 'good_no_echo'
            _, recovered = await_sample(process, port, lambda status, data: status == 200 and data['ok'])
            assert recovered['tariffs_kwh'] == VALUES
            assert requests and not failures, failures
            return {'pty_native_serial': 'passed', 'fragmented_echo_and_noise': 'passed',
                    'crc_failure_retains_old_sample_with_http503': 'passed',
                    'recovery_without_echo': 'passed', 'read_only_packets': len(requests)}
        except Exception:
            log.flush()
            log.seek(0)
            print(log.read())
            raise
        finally:
            stop(process)
            stopped.set()
            thread.join(timeout=1)
            os.close(master)
            os.close(slave)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('jar', type=Path)
    args = parser.parse_args()
    jar = args.jar.resolve(strict=True)
    with tempfile.TemporaryDirectory(prefix='mercury-integration-') as directory:
        work = Path(directory)
        results = test_demo(jar, work)
        results.update(test_uart(jar, work))
    results['physical_hardware_tested'] = False
    print(json.dumps(results, indent=2))

if __name__ == '__main__':
    main()
