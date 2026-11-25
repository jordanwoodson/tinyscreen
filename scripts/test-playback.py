#!/usr/bin/env python3
"""Exercise the minified smoke build on an emulator. Its media fixture is entirely local."""
import argparse
import re
import subprocess
import time
import xml.etree.ElementTree as ET

APP = "io.github.jordanwoodson.tinyscreen"
PKG = APP + ".smoke"
COMPONENT = PKG + "/" + APP + ".MainActivity"


class Device:
    def __init__(self, serial, package):
        self.package = package
        self.adb = ["adb", "-s", serial] if serial else ["adb", "-e"]

    def run(self, *args):
        return subprocess.check_output([*self.adb, *args], text=True, timeout=35)

    def open(self, identifier, start):
        url = f"https://www.destiny.gg/bigscreen#youtube/{identifier}?t={start}s"
        self.run("shell", "am", "start", "-W", "-a", "android.intent.action.VIEW", "-d", url, "-n", self.package + "/" + APP + ".MainActivity")

    def nodes(self):
        path = "/data/local/tmp/tinyscreen-smoke.xml"
        self.run("shell", "uiautomator", "dump", path)
        return list(ET.fromstring(self.run("exec-out", "cat", path)).iter("node"))

    def labels(self):
        return [node.get("text") or node.get("content-desc") for node in self.nodes()]

    def tap(self, label):
        def locate():
            for node in self.nodes():
                if label in (node.get("text"), node.get("content-desc")):
                    return tuple(map(int, re.findall(r"\d+", node.get("bounds"))))
        x, y, right, bottom = wait_for(locate, label)
        self.run("shell", "input", "tap", str((x + right) // 2), str((y + bottom) // 2))

    def player_settings(self):
        if "Show player controls" in self.labels():
            self.tap("Show player controls")
        self.tap("Player settings")

    def media(self, title, state):
        dump = self.run("shell", "dumpsys", "media_session")
        # Match only the fixture's session, even with another player installed.
        for block in re.split(r"(?=\n\s*ownerPid=)", dump):
            if f"package={self.package}\n" not in block or "description=" + title not in block:
                continue
            code = {"PLAYING": 3, "PAUSED": 2}[state]
            match = re.search(rf"state=PlaybackState \{{state=(?:{state}\({code}\)|{code}), position=(\d+),.*?updated=(\d+)", block)
            if match:
                return tuple(map(int, match.groups()))


def wait_for(check, description, timeout=40):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = check()
        if value is not None:
            return value
        time.sleep(0.4)
    raise RuntimeError("Timed out waiting for " + description)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("-s", "--serial")
    parser.add_argument("--public-youtube", action="store_true", help="Check playback against YouTube using the installed release APK")
    args = parser.parse_args()
    device = Device(args.serial, APP if args.public_youtube else PKG)
    if args.public_youtube:
        device.run("shell", "am", "force-stop", APP)
        device.open("aqz-KE-bpKQ", 30)
        title = "Big Buck Bunny"
        started, _ = wait_for(lambda: device.media(title, "PLAYING"), "public YouTube playback", timeout=60)
        time.sleep(3)
        device.run("shell", "cmd", "media_session", "dispatch", "pause")
        paused, _ = wait_for(lambda: device.media(title, "PAUSED"), "public YouTube pause")
        assert paused >= 30_000 and paused >= started + 1000, (started, paused)
        print(f"Public YouTube playback advanced from {started} ms to {paused} ms.")
        return
    device.run("install", "-r", "app/build/outputs/apk/smoke/app-smoke.apk")
    device.run("shell", "am", "force-stop", PKG)
    device.open("TinyFixture1", 30)
    # This provider's first request fails. Its next request must play through the UI recovery action.
    def web_state(expected):
        for label in device.labels():
            match = re.search(r"Fixture (\d+) " + expected + r" at (\d+)", label or "")
            if match and int(match[2]) >= 30:
                return tuple(map(int, match.groups()))

    wait_for(lambda: web_state("playing"), "local web fallback at the requested time")
    device.tap("More")
    device.tap("Use native player")
    title = "Playback fixture 1"
    started, _ = wait_for(lambda: device.media(title, "PLAYING"), "native fallback recovery")
    time.sleep(2)
    device.run("shell", "cmd", "media_session", "dispatch", "pause")
    paused, generation = wait_for(lambda: device.media(title, "PAUSED"), "pause")
    assert paused >= 30_000 and paused >= started + 1000, (started, paused)
    device.tap("More")
    device.tap("Reload stream")

    def reloaded():
        state = device.media(title, "PAUSED")
        return state if state and state[1] > generation else None

    position, _ = wait_for(reloaded, "reloaded paused session")
    assert abs(position - paused) < 500, (position, paused)
    time.sleep(1)
    assert device.media(title, "PAUSED") is not None
    device.player_settings()
    device.tap("Captions")
    device.tap("English")
    device.player_settings()
    device.tap("Audio track")
    device.tap("Spanish")

    device.tap("More")
    device.tap("Share this moment")
    shared = "https://www.destiny.gg/bigscreen#youtube/TinyFixture1?t="
    # Android's sharesheet renders the actual outgoing text.
    def shared_position():
        for label in device.labels():
            match = re.search(re.escape(shared) + r"(\d+)s", label or "")
            if match:
                return int(match.group(1))
    seconds = wait_for(shared_position, "timestamped share text")
    assert seconds == position // 1000, (seconds, position)
    device.run("shell", "input", "keyevent", "KEYCODE_BACK")

    device.tap("More")
    device.tap("Use web player")
    before, web_seconds = wait_for(lambda: web_state("paused"), "paused web player")
    assert abs(web_seconds - position // 1000) <= 1, (web_seconds, position)
    device.tap("More")
    device.tap("Reload stream")

    def new_document():
        state = web_state("paused")
        return state if state and state[0] != before else None

    _, web_reloaded = wait_for(new_document, "new web document at paused position")
    assert web_reloaded == web_seconds, (web_reloaded, web_seconds)
    device.tap("More")
    device.tap("Use native player")
    restored, _ = wait_for(lambda: device.media(title, "PAUSED"), "paused return from web")
    assert abs(restored - position) < 1000, (restored, position)

    device.open("TinyFixture2", 1)
    wait_for(lambda: device.media("Playback fixture 2", "PLAYING"), "second video")
    device.run("shell", "am", "force-stop", PKG)
    device.run("shell", "am", "start", "-W", "-n", COMPONENT)
    device.tap("Menu")
    device.tap("Continue watching")
    device.tap(title)
    resumed, _ = wait_for(lambda: device.media(title, "PLAYING"), "saved VOD after restart")
    assert abs(resumed - position) < 4000, (resumed, position)
    device.run("shell", "cmd", "media_session", "dispatch", "pause")
    device.player_settings()
    labels = device.labels()
    assert "Spanish" in labels and "English" in labels, labels
    device.run("shell", "input", "keyevent", "KEYCODE_BACK")
    device.tap("Menu")
    device.tap("Continue watching")
    device.tap("Clear history")
    assert "Recently watched videos will appear here." in device.labels()
    print(f"Playback advanced to {paused} ms, then remained paused at {position} ms after reload.")


if __name__ == "__main__":
    main()
